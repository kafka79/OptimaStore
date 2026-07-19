ALTER TABLE stock_transactions
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

ALTER TABLE items
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_stock_transactions_item_id
    ON stock_transactions (item_id);

ALTER TABLE stock_transactions
    DROP CONSTRAINT IF EXISTS fk_stock_transactions_item;

ALTER TABLE stock_transactions
    ADD CONSTRAINT fk_stock_transactions_item
    FOREIGN KEY (item_id) REFERENCES items(id);
