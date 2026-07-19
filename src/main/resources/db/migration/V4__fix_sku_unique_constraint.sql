-- Drop the global unique constraint on SKU
ALTER TABLE items DROP CONSTRAINT IF EXISTS uk_items_sku;

-- Add a partial unique index for active items only
CREATE UNIQUE INDEX uk_items_sku_active ON items (sku) WHERE archived = false;
