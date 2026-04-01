# Deployment Guide — Cerebro

## Local Development

### Prerequisites
- Java 21 (`java -version`)
- Maven 3.8+ (`mvn -version`)
- Angel One SmartAPI account with TOTP enabled
- Telegram bot created via @BotFather

### Quick Start

```bash
# 1. Clone and enter project
cd C:\Users\jayan\projects\stock-agent

# 2. Create credentials file
copy .env.example .env
# Edit .env with your Angel One + Telegram credentials

# 3. Build
mvn clean compile

# 4. Run (paper-trading mode by default)
mvn spring-boot:run

# 5. Verify
curl http://localhost:8080/api/status
```

### Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `ANGEL_API_KEY` | SmartAPI key from smartapi.angelbroking.com | `abc123xyz` |
| `ANGEL_CLIENT_ID` | Your Angel One login ID | `B102789` |
| `ANGEL_MPIN` | 4-digit MPIN | `1234` |
| `ANGEL_TOTP_SECRET` | Base32 TOTP secret (NOT a 6-digit code) | `JBSWY3DPEHPK3PXP` |
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather | `7123456789:AAF...xyz` |
| `TELEGRAM_CHAT_ID` | Your Telegram user chat ID | `987654321` |
| `DB_URL` | JDBC URL (optional; defaults to H2) | `jdbc:postgresql://host:5432/stock_agent` |
| `DB_USER` | DB username (optional) | `stock_user` |
| `DB_PASSWORD` | DB password (optional) | `secret` |
| `DB_DRIVER` | JDBC driver class (optional) | `org.postgresql.Driver` |
| `DB_DIALECT` | Hibernate dialect (optional) | `org.hibernate.dialect.PostgreSQLDialect` |

---

## Production — AWS EC2

### Instance Details

| Property | Value |
|----------|-------|
| Instance ID | `i-000642cb46fbf7ad9` |
| Name | `stock-agent-jay` |
| Type | `t2.micro` (1 vCPU, 1 GB RAM) |
| Region | `ap-south-1` (Mumbai) |
| OS | Ubuntu 22.04 LTS |
| Public IP | `13.203.216.12` |
| JVM heap | `-Xmx512m` |

### SSH Access

```bash
ssh -i "C:\Users\jayan\.ssh\stock-agent-pem.pem" ubuntu@13.203.216.12
```

### Directory Layout (on server)

```
/home/ubuntu/
├── stock-agent/
│   ├── stock-agent.jar          # Deployed JAR
│   ├── config.yaml              # Agent trading config
│   ├── .env                     # Secrets (not in git)
│   └── logs/
│       ├── agent.log            # Application log (rolling)
│       └── cron.log             # Angel One login cron log
└── .ssh/
```

---

## PostgreSQL Database

### Server Setup (once)

```bash
# Install PostgreSQL
sudo apt update && sudo apt install -y postgresql

# Create database and user
sudo -u postgres psql <<'SQL'
CREATE DATABASE stock_agent;
CREATE USER stock_user WITH PASSWORD 'your_password_here';
GRANT ALL PRIVILEGES ON DATABASE stock_agent TO stock_user;
GRANT ALL ON SCHEMA public TO stock_user;
SQL
```

### `.env` Configuration (server)

```env
ANGEL_API_KEY=...
ANGEL_CLIENT_ID=...
ANGEL_MPIN=...
ANGEL_TOTP_SECRET=...
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...

DB_URL=jdbc:postgresql://localhost:5432/stock_agent
DB_USER=stock_user
DB_PASSWORD=your_password_here
DB_DRIVER=org.postgresql.Driver
DB_DIALECT=org.hibernate.dialect.PostgreSQLDialect
```

### How Tables Are Created

`ddl-auto: update` in `application.yml` — Hibernate creates or migrates tables automatically
on first startup. No manual SQL scripts needed.

### Connecting with DBeaver

| Field | Value |
|-------|-------|
| Host | `13.203.216.12` |
| Port | `5432` |
| Database | `stock_agent` |
| Username | `stock_user` |
| Password | (from `.env`) |

> Ensure PostgreSQL is configured to allow remote connections:
> `pg_hba.conf` — add `host all all 0.0.0.0/0 md5`
> `postgresql.conf` — set `listen_addresses = '*'`
> Then restart: `sudo systemctl restart postgresql`

---

## Systemd Service

### Service File: `deploy/stock-agent.service`

```ini
[Unit]
Description=Cerebro
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/stock-agent
EnvironmentFile=/home/ubuntu/stock-agent/.env
Environment=TZ=Asia/Kolkata
ExecStart=/usr/bin/java -Xmx512m -jar /home/ubuntu/stock-agent/stock-agent.jar \
  --spring.config.additional-location=file:/home/ubuntu/stock-agent/config.yaml
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

> `Environment=TZ=Asia/Kolkata` is critical — Spring's `@Scheduled` cron expressions
> with `zone = "Asia/Kolkata"` need the JVM timezone to be set correctly on the host.

### Service Commands

```bash
# Deploy and start
sudo systemctl daemon-reload
sudo systemctl enable stock-agent
sudo systemctl start stock-agent

# Status
sudo systemctl status stock-agent

# Logs (live tail)
tail -f ~/stock-agent/logs/agent.log

# Restart after deploy
sudo systemctl restart stock-agent

# Stop
sudo systemctl stop stock-agent
```

---

## Angel One Auto-Login Cron

The Angel One JWT token expires daily. A cron job refreshes it each morning before market open.

### Cron Entry (server)

```bash
crontab -e
# Add:
30 3 * * 1-5 curl -s -X POST http://localhost:8080/api/broker/login >> /home/ubuntu/stock-agent/logs/cron.log 2>&1
```

`30 3 * * 1-5` = 03:30 UTC = **09:00 AM IST**, Monday–Friday.

The application also auto-logs in on every startup via `ApplicationReadyEvent`.

---

## Build & Deploy Workflow

### On Local Machine

```bash
# Build JAR
mvn clean package -DskipTests

# Copy to server
scp -i "C:\Users\jayan\.ssh\stock-agent-pem.pem" \
    target/stock-agent-0.0.1-SNAPSHOT.jar \
    ubuntu@13.203.216.12:/home/ubuntu/stock-agent/stock-agent.jar
```

### On Server

```bash
# Restart the service to pick up new JAR
sudo systemctl restart stock-agent

# Watch startup
tail -f ~/stock-agent/logs/agent.log
```

### Full Deploy Script (one-liner)

```bash
# From local machine (PowerShell / bash)
mvn clean package -DskipTests && \
scp -i "C:\Users\jayan\.ssh\stock-agent-pem.pem" \
    target/stock-agent-0.0.1-SNAPSHOT.jar \
    ubuntu@13.203.216.12:/home/ubuntu/stock-agent/stock-agent.jar && \
ssh -i "C:\Users\jayan\.ssh\stock-agent-pem.pem" ubuntu@13.203.216.12 \
    "sudo systemctl restart stock-agent && tail -n 50 ~/stock-agent/logs/agent.log"
```

---

## Verify Production Deployment

```bash
# Check service status
sudo systemctl status stock-agent

# Agent health
curl http://13.203.216.12:8080/api/status

# Market status
curl http://13.203.216.12:8080/api/market/status

# Broker login
curl -X POST http://13.203.216.12:8080/api/broker/login

# Telegram test
curl -X POST http://13.203.216.12:8080/api/telegram/test

# Web dashboard
# Open in browser: http://13.203.216.12:8080/ui/dashboard
```

---

## config.yaml on Server

The server's `config.yaml` lives at `/home/ubuntu/stock-agent/config.yaml` and is loaded
alongside the bundled one via `--spring.config.additional-location`.

To update agent parameters without redeploying the JAR:

```bash
# SSH into server
ssh -i "C:\Users\jayan\.ssh\stock-agent-pem.pem" ubuntu@13.203.216.12

# Edit config
nano ~/stock-agent/config.yaml

# Restart to pick up changes
sudo systemctl restart stock-agent
```

---

## Log Management

```bash
# Live log tail
tail -f ~/stock-agent/logs/agent.log

# Last 200 lines
tail -n 200 ~/stock-agent/logs/agent.log

# Search for errors
grep -i "error\|exception\|failed" ~/stock-agent/logs/agent.log | tail -50

# Angel One login cron log
cat ~/stock-agent/logs/cron.log
```

The application uses Spring Boot's rolling file appender — configure in `application.yml`:
```yaml
logging:
  file:
    name: /home/ubuntu/stock-agent/logs/agent.log
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 7
```

---

## Security Notes

> These are planned for the ★★★★★ tier.

- [ ] HTTPS via Let's Encrypt + Certbot + Nginx
- [ ] API key authentication on all REST endpoints
- [ ] IP allowlist for `/api/*` routes
- [ ] Rotate Angel One TOTP secret periodically
- [ ] Never commit `.env` to git (it is in `.gitignore`)

Current security posture: HTTP only, no authentication on endpoints.
**Do not expose port 8080 publicly in live-trading mode without adding authentication first.**
