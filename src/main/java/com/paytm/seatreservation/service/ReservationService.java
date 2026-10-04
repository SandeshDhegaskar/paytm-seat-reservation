package com.paytm.seatreservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.seatreservation.dto.*;
import com.paytm.seatreservation.dto.ApiModels.*;
import com.paytm.seatreservation.exception.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReservationService {
    private final JdbcTemplate db;
    private final MetricsService metrics;
    private final ObjectMapper mapper;

    public ReservationService(JdbcTemplate db, MetricsService metrics, ObjectMapper mapper) {
        this.db = db;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest r) {
        UUID id = UUID.randomUUID();
        int limit = r.per_user_limit() == null ? 4 : r.per_user_limit();
        if (new HashSet<>(r.seats()).size() != r.seats().size()) {
            throw new DomainException("INVALID_SEATS", "Duplicate seat numbers are not allowed", 400);
        }

        db.update("INSERT INTO shows(id,name,price_paise,per_user_limit) VALUES (?,?,?,?)",
                id.toString(), r.name(), r.price_paise(), limit);
        for (String seat : new TreeSet<>(r.seats())) {
            db.update("INSERT INTO show_seats(show_id,seat_no,status) VALUES (?,?,'available')",
                    id.toString(), seat);
        }
        metrics.available(id.toString(), r.seats().size());
        return getShow(id);
    }

    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest request) {
        if (request.idempotency_key() == null || request.idempotency_key().isBlank()) {
            throw new DomainException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency key is required", 400);
        }
        if (request.seats().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.seats()).size() != request.seats().size()) {
            throw new DomainException("INVALID_SEATS", "Duplicate or null seat numbers are not allowed", 400);
        }

        List<String> seats = request.seats().stream().sorted().toList();
        if (seats.isEmpty()) {
            throw new DomainException("INVALID_SEATS", "At least one seat is required", 400);
        }

        ShowRow show = getShowRow(showId);
        String showKey = showId.toString();
        String hash = hash(showId, userId, seats);

        // MySQL/InnoDB transaction-scoped per-user serialization mechanism.
        // The row is created once and then locked with SELECT ... FOR UPDATE.
        // Therefore concurrent reservations for the same (show,user) serialize only
        // the per-user count + idempotency decision; different users remain concurrent.
        db.update("INSERT IGNORE INTO booking_locks(show_id,user_id) VALUES (?,?)", showKey, userId);
        db.queryForObject("SELECT show_id FROM booking_locks WHERE show_id=? AND user_id=? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), showKey, userId);

        // The lock above closes the concurrent-retry race for idempotency.
        Optional<IdempotencyRow> existing = findIdempotency(showKey, userId, request.idempotency_key());
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().requestHash())) {
                metrics.declined("idempotency-key-conflict");
                throw new DomainException("IDEMPOTENCY_CONFLICT",
                        "Same idempotency key was used with a different request", 409);
            }
            metrics.declined("idempotent-replay");
            return getReservation(UUID.fromString(existing.get().reservationId()));
        }

        int current = db.queryForObject(
                "SELECT COUNT(*) FROM reservation_seats rs " +
                "JOIN reservations r ON r.id=rs.reservation_id " +
                "WHERE r.show_id=? AND r.user_id=? AND r.status='confirmed'",
                Integer.class, showKey, userId);

        if (current + seats.size() > show.limit) {
            metrics.declined("per-user-limit");
            throw new DomainException("PER_USER_LIMIT", "User booking limit exceeded", 409);
        }

        String placeholders = seats.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT seat_no,status FROM show_seats WHERE show_id=? AND seat_no IN (" + placeholders + ") " +
                "ORDER BY seat_no FOR UPDATE",
                prepend(showKey, seats));

        if (rows.size() != seats.size()) {
            metrics.declined("seat-not-found");
            throw new DomainException("SEAT_NOT_FOUND", "One or more requested seats do not exist", 409);
        }

        Set<String> unavailable = rows.stream()
                .filter(x -> !"available".equals(x.get("status")))
                .map(x -> (String) x.get("seat_no"))
                .collect(Collectors.toCollection(TreeSet::new));

        if (!unavailable.isEmpty()) {
            metrics.declined("seat-taken");
            throw new DomainException("SEAT_TAKEN",
                    "Seat(s) already taken: " + String.join(",", unavailable), 409);
        }

        UUID reservationId = UUID.randomUUID();
        long amount = show.price * seats.size();
        String reservationKey = reservationId.toString();

        db.update("INSERT INTO reservations(id,show_id,user_id,amount_paise,status) VALUES (?,?,?,?,'confirmed')",
                reservationKey, showKey, userId, amount);

        for (String seat : seats) {
            int updated = db.update(
                    "UPDATE show_seats SET status='confirmed',reservation_id=? " +
                    "WHERE show_id=? AND seat_no=? AND status='available'",
                    reservationKey, showKey, seat);
            if (updated != 1) {
                throw new IllegalStateException("Seat state changed unexpectedly during locked reservation");
            }
            db.update("INSERT INTO reservation_seats(reservation_id,show_id,seat_no) VALUES (?,?,?)",
                    reservationKey, showKey, seat);
        }

        db.update("INSERT INTO idempotency_keys(show_id,user_id,idempotency_key,request_hash,reservation_id) VALUES (?,?,?,?,?)",
                showKey, userId, request.idempotency_key(), hash, reservationKey);

        metrics.confirmed();
        refreshGauge(showId);
        return new ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed");
    }

    @Transactional
    public ReservationResponse cancel(UUID reservationId, String userId) {
        String reservationKey = reservationId.toString();
        List<Map<String, Object>> rr = db.queryForList(
                "SELECT id,show_id,user_id,status,amount_paise FROM reservations WHERE id=? FOR UPDATE",
                reservationKey);
        if (rr.isEmpty()) {
            throw new DomainException("NOT_FOUND", "Reservation not found", 404);
        }

        var r = rr.get(0);
        if (!userId.equals(r.get("user_id"))) {
            throw new DomainException("FORBIDDEN", "Only the owner can cancel this reservation", 403);
        }
        if ("cancelled".equals(r.get("status"))) {
            return getReservation(reservationId);
        }

        UUID showId = UUID.fromString((String) r.get("show_id"));
        List<Map<String, Object>> seats = db.queryForList(
                "SELECT seat_no FROM reservation_seats WHERE reservation_id=? ORDER BY seat_no FOR UPDATE",
                reservationKey);

        for (var s : seats) {
            db.update("UPDATE show_seats SET status='available',reservation_id=NULL " +
                      "WHERE show_id=? AND seat_no=? AND reservation_id=?",
                    showId.toString(), s.get("seat_no"), reservationKey);
        }
        db.update("DELETE FROM reservation_seats WHERE reservation_id=?", reservationKey);
        db.update("UPDATE reservations SET status='cancelled',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                reservationKey);

        refreshGauge(showId);
        return getReservation(reservationId);
    }

    public ShowResponse getShow(UUID id) {
        ShowRow s = getShowRow(id);
        List<SeatView> seats = db.query(
                "SELECT seat_no,status FROM show_seats WHERE show_id=? ORDER BY seat_no",
                (rs, n) -> new SeatView(rs.getString(1), rs.getString(2)), id.toString());

        Map<String, Integer> counts = seats.stream().collect(Collectors.groupingBy(
                SeatView::status, TreeMap::new,
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
        counts.putIfAbsent("available", 0);
        counts.putIfAbsent("held", 0);
        counts.putIfAbsent("confirmed", 0);
        metrics.available(id.toString(), counts.get("available"));
        return new ShowResponse(id, s.name, s.price, s.limit, seats, counts);
    }

    private ShowRow getShowRow(UUID id) {
        List<ShowRow> x = db.query(
                "SELECT id,name,price_paise,per_user_limit FROM shows WHERE id=?",
                (rs, n) -> new ShowRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                        rs.getLong(3), rs.getInt(4)), id.toString());
        if (x.isEmpty()) {
            throw new DomainException("SHOW_NOT_FOUND", "Show not found", 404);
        }
        return x.get(0);
    }

    private ReservationResponse getReservation(UUID id) {
        String key = id.toString();
        List<ReservationResponse> x = db.query(
                "SELECT r.id,r.show_id,r.user_id,r.amount_paise,r.status FROM reservations r WHERE r.id=?",
                (rs, n) -> new ReservationResponse(
                        UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getString(3), List.of(), rs.getLong(4), rs.getString(5)), key);
        if (x.isEmpty()) {
            throw new DomainException("NOT_FOUND", "Reservation not found", 404);
        }
        ReservationResponse r = x.get(0);
        List<String> seats = db.query(
                "SELECT seat_no FROM reservation_seats WHERE reservation_id=? ORDER BY seat_no",
                (rs, n) -> rs.getString(1), key);
        return new ReservationResponse(r.reservation_id(), r.show_id(), r.user_id(), seats,
                r.amount_paise(), r.status());
    }

    private Optional<IdempotencyRow> findIdempotency(String showId, String userId, String key) {
        List<IdempotencyRow> rows = db.query(
                "SELECT reservation_id,request_hash FROM idempotency_keys WHERE show_id=? AND user_id=? AND idempotency_key=?",
                (rs, n) -> new IdempotencyRow(rs.getString(1), rs.getString(2)), showId, userId, key);
        return rows.stream().findFirst();
    }

    private void refreshGauge(UUID showId) {
        Integer n = db.queryForObject(
                "SELECT COUNT(*) FROM show_seats WHERE show_id=? AND status='available'",
                Integer.class, showId.toString());
        metrics.available(showId.toString(), n);
    }

    private String hash(UUID showId, String user, List<String> seats) {
        try {
            String p = showId + "|" + user + "|" + String.join(",", seats);
            byte[] b = MessageDigest.getInstance("SHA-256")
                    .digest(p.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object[] prepend(String id, List<String> xs) {
        Object[] a = new Object[xs.size() + 1];
        a[0] = id;
        for (int i = 0; i < xs.size(); i++) a[i + 1] = xs.get(i);
        return a;
    }

    private record ShowRow(UUID id, String name, long price, int limit) {}
    private record IdempotencyRow(String reservationId, String requestHash) {}
}
