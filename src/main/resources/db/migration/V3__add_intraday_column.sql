-- V3: Add intraday flag to trade_records
ALTER TABLE trade_records ADD COLUMN intraday BOOLEAN NOT NULL DEFAULT FALSE;
