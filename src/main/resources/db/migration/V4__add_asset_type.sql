-- V4: Add asset_type column for Mutual Fund signal tracking
ALTER TABLE trade_records ADD COLUMN IF NOT EXISTS asset_type VARCHAR(20) DEFAULT 'Stock';
UPDATE trade_records SET asset_type = 'Stock' WHERE asset_type IS NULL;
