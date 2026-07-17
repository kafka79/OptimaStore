CREATE TABLE items (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    unit_price DECIMAL(12, 2) NOT NULL DEFAULT 0,
    category VARCHAR(128) NOT NULL DEFAULT 'General',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    low_stock_threshold INT NOT NULL DEFAULT 5,
    CONSTRAINT uk_items_sku UNIQUE (sku)
);

CREATE TABLE stock_transactions (
    id BIGSERIAL PRIMARY KEY,
    item_id BIGINT NOT NULL,
    sku VARCHAR(64) NOT NULL,
    delta INT NOT NULL,
    previous_quantity INT NOT NULL,
    new_quantity INT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator VARCHAR(128) NOT NULL DEFAULT 'anonymous',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    response_payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_items_lower_sku ON items (sku);
CREATE INDEX idx_items_lower_category ON items (category);
CREATE INDEX idx_items_lower_name ON items (name);
CREATE INDEX idx_items_quantity ON items (quantity, archived);
CREATE INDEX idx_items_archived_id ON items (archived, id);
CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

CREATE TABLE users (
    username VARCHAR(50) NOT NULL PRIMARY KEY,
    password VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL
);

CREATE TABLE authorities (
    username VARCHAR(50) NOT NULL,
    authority VARCHAR(50) NOT NULL,
    CONSTRAINT fk_authorities_users FOREIGN KEY(username) REFERENCES users(username)
);
CREATE UNIQUE INDEX ix_auth_username ON authorities (username, authority);
