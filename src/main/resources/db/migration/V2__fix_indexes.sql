-- Replace standard B-tree indexes with functional indexes for case-insensitive search
DROP INDEX IF EXISTS idx_items_lower_sku;
DROP INDEX IF EXISTS idx_items_lower_category;
DROP INDEX IF EXISTS idx_items_lower_name;

CREATE INDEX idx_items_lower_sku ON items (LOWER(sku));
CREATE INDEX idx_items_lower_category ON items (LOWER(category));
CREATE INDEX idx_items_lower_name ON items (LOWER(name));
