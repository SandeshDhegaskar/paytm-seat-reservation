CREATE TABLE IF NOT EXISTS shows (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS show_seats (
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_no VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('available','confirmed')),
    reservation_id UUID NULL,
    PRIMARY KEY (show_id, seat_no)
);
CREATE INDEX IF NOT EXISTS idx_show_seats_status ON show_seats(show_id, status);

CREATE TABLE IF NOT EXISTS reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(120) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('confirmed','cancelled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_reservations_user_show ON reservations(show_id, user_id, status);

CREATE TABLE IF NOT EXISTS reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    show_id UUID NOT NULL,
    seat_no VARCHAR(40) NOT NULL,
    PRIMARY KEY (reservation_id, seat_no),
    UNIQUE (show_id, seat_no)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    user_id VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reservation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_reservation_seats_show ON reservation_seats(show_id, seat_no);
