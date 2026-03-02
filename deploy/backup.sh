#!/bin/bash
# ============================================================
# PostgreSQL daily backup — stock_agent database
# Keeps 7 days of backups in ~/stock-agent/backups/
#
# Cron (2 AM IST = 20:30 UTC):
#   30 20 * * * /home/ubuntu/stock-agent/backup.sh >> /home/ubuntu/stock-agent/logs/backup.log 2>&1
# ============================================================

set -euo pipefail

BACKUP_DIR="/home/ubuntu/stock-agent/backups"
DB_NAME="stock_agent"
DB_USER="stock_user"
KEEP_DAYS=7
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="$BACKUP_DIR/${DB_NAME}_${TIMESTAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting backup: $BACKUP_FILE"

# Source env vars for password
if [ -f "/home/ubuntu/stock-agent/.env" ]; then
    export $(grep -v '^#' /home/ubuntu/stock-agent/.env | grep DB_PASSWORD | xargs)
fi

PGPASSWORD="${DB_PASSWORD:-}" pg_dump \
    -U "$DB_USER" \
    -h localhost \
    "$DB_NAME" | gzip > "$BACKUP_FILE"

SIZE=$(du -sh "$BACKUP_FILE" | cut -f1)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Backup complete: $BACKUP_FILE ($SIZE)"

# Delete backups older than KEEP_DAYS days
find "$BACKUP_DIR" -name "${DB_NAME}_*.sql.gz" -mtime +$KEEP_DAYS -delete
REMAINING=$(ls "$BACKUP_DIR" | wc -l)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Retention: kept $REMAINING backup(s), deleted files older than ${KEEP_DAYS} days"
