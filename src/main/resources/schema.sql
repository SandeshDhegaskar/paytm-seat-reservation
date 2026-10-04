CREATE TABLE IF NOT EXISTS shows (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    price_paise BIGINT NOT NULL,
    per_user_limit INT NOT NULL DEFAULT 4,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_show_price CHECK (price_paise >= 0),
    CONSTRAINT chk_show_limit CHECK (per_user_limit > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS show_seats (
    show_id CHAR(36) NOT NULL,
    seat_no VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reservation_id CHAR(36) NULL,
    PRIMARY KEY (show_id, seat_no),
    CONSTRAINT fk_show_seats_show FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE,
    CONSTRAINT chk_seat_status CHECK (status IN ('available','confirmed')),
    INDEX idx_show_seats_status (show_id, status),
    INDEX idx_show_seats_reservation (reservation_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reservations (
    id CHAR(36) PRIMARY KEY,
    show_id CHAR(36) NOT NULL,
    user_id VARCHAR(120) NOT NULL,
    amount_paise BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelled_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_reservations_show FOREIGN KEY (show_id) REFERENCES shows(id),
    CONSTRAINT chk_reservation_amount CHECK (amount_paise >= 0),
    CONSTRAINT chk_reservation_status CHECK (status IN ('confirmed','cancelled')),
    INDEX idx_reservations_user_show (show_id, user_id, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reservation_seats (
    reservation_id CHAR(36) NOT NULL,
    show_id CHAR(36) NOT NULL,
    seat_no VARCHAR(40) NOT NULL,
    PRIMARY KEY (reservation_id, seat_no),
    UNIQUE KEY uk_reservation_show_seat (show_id, seat_no),
    CONSTRAINT fk_reservation_seats_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_seats_show_seat FOREIGN KEY (show_id, seat_no) REFERENCES show_seats(show_id, seat_no) ON DELETE RESTRICT,
    INDEX idx_reservation_seats_show (show_id, seat_no)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS idempotency_keys (
    show_id CHAR(36) NOT NULL,
    user_id VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reservation_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (show_id, user_id, idempotency_key),
    CONSTRAINT fk_idempotency_show FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE,
    CONSTRAINT fk_idempotency_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id)
) ENGINE=InnoDB;

-- A persistent per-(show,user) lock row provides transaction-scoped serialization.
-- INSERT IGNORE + SELECT ... FOR UPDATE makes the serialization transaction-scoped.
CREATE TABLE IF NOT EXISTS booking_locks (
    show_id CHAR(36) NOT NULL,
    user_id VARCHAR(120) NOT NULL,
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT fk_booking_locks_show FOREIGN KEY (show_id) REFERENCES shows(id) ON DELETE CASCADE
) ENGINE=InnoDB;
