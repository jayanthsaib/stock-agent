# Project Roadmap — Cerebro

This document captures the full project plan from conception to production, organised
as a star-rating maturity model. Each tier builds on the previous one.

---

## Star-Rating Framework

| Rating | Name | State |
|--------|------|-------|
| ★☆☆☆☆ | Skeleton | First working pipeline |
| ★★☆☆☆ | Reliable | Stable scheduled flow, Telegram approval |
| ★★★☆☆ | Production-grade | Live broker, PostgreSQL, AWS deploy |
| ★★★★☆ | Smart | Market awareness, news sentiment, backtesting |
| ★★★★★ | Full system | Intraday signals, learning engine, HTTPS |

---

## ★☆☆☆☆ — Skeleton (Completed)

**Goal:** Prove the concept end-to-end with a hardcoded watchlist.

- [x] Spring Boot 3.2.3 project scaffold (Java 21, Maven)
- [x] Angel One SmartAPI authentication (TOTP-based MPIN login)
- [x] Historical OHLCV fetch via `getHistoricalData()`
- [x] Basic technical analysis: SMA200, SMA50, SMA20, RSI (ta4j)
- [x] Hardcoded 20-symbol watchlist in config.yaml
- [x] Paper-trading mode with virtual balance
- [x] Telegram bot integration (send/receive)
- [x] APPROVE / REJECT command handler
- [x] Basic REST API: `/api/status`, `/api/positions`, `/api/signals/pending`
- [x] H2 in-memory database for trade records

---

## ★★☆☆☆ — Reliable (Completed)

**Goal:** A fully automated scheduled system that runs without babysitting.

- [x] 7-layer architecture (data → analysis → signal → risk → report → execution → monitor)
- [x] Two-phase NSE universe scan
  - Phase 1: batch `getQuote()` across all ~2500 NSE equities (50-token batches, 200ms delay)
  - Phase 2: sequential 1-year OHLCV fetch (Semaphore(1) + 500ms sleep per symbol)
  - Cap: top 500 candidates from Phase 1
- [x] Instrument Master Service (downloads Angel One scrip master daily, ~2500 symbols)
  - Dynamic token resolution by symbol + exchange
  - ETF filter: excludes ETFs by name/symbol pattern
- [x] Four parallel analysis modules (CompletableFuture)
  - Fundamental: Screener.in P/E, ROE, ROCE, D/E, cash flow, promoter holding
  - Technical: ta4j SMA200/50/20, RSI, MACD, volume confirmation
  - Macro: India VIX, Nifty vs 200 DMA, FII flow (NSE website)
  - Risk-Reward: entry/target/stop-loss levels, minimum R:R enforcement
- [x] Confidence Score (0–100%) weighted: Fund 35% + Tech 30% + Macro 20% + RR 15%
- [x] 15+ hard risk rules in RiskValidator
  - Max position size (10% portfolio)
  - Max sector allocation (25%)
  - VIX circuit-breaker (no buys above VIX 25)
  - Minimum cash reserve (20%)
  - Duplicate symbol check (one open per symbol)
  - Minimum R:R ratio (configurable, default 1.5)
- [x] Pre-Trade Report sent to Telegram (blueprint format)
- [x] Signal expiry (configurable timeout, default 30 min)
- [x] TradingScheduler cron jobs
  - 08:45 AM IST — pre-market data refresh
  - 09:15 AM IST — signal generation pipeline
  - Every 15 min (09:30–15:30) — position monitoring
  - 15:30 IST — end-of-day summary
  - Every 2 seconds — Telegram APPROVE/REJECT polling
  - 1st of month 07:00 IST — learning engine review
- [x] PortfolioMonitor: trailing stop-loss, partial profit alerts, end-of-day P&L summary
- [x] LearningEngine: win-rate calibration, sector analysis, rejection analysis
- [x] Web dashboard (Thymeleaf): `/ui/dashboard`, `/ui/positions`, `/ui/signals`, `/ui/performance`
- [x] On-demand stock analysis UI: `/ui/analyse` + `GET /api/analyse/{symbol}`
- [x] BETA/NIFTY/SENSEX symbol exclusion from instrument universe
- [x] ETF hard-filter in InstrumentMasterService (by name pattern and symbol suffix)

---

## ★★★☆☆ — Production-Grade (Completed)

**Goal:** Deploy to a real server; fix all reliability gaps found in live testing.

### Production Fixes Applied

- [x] **Race condition fix** — `volatile boolean refreshInProgress` flag in DataIngestionEngine;
  TradingScheduler waits up to 10 minutes at 09:15 if pre-market refresh is still running
- [x] **Telegram offset persistence** — `lastUpdateId` saved to `~/.stock-agent-telegram-offset`
  on disk; restored on restart to prevent replaying old APPROVE/REJECT commands
- [x] **Live price in PortfolioMonitor** — replaced stale `getHoldings()` with a direct
  `getQuote()` call via `InstrumentMasterService.resolveToken()`
- [x] **Systemd timezone fix** — `Environment=TZ=Asia/Kolkata` added to service file so IST
  cron expressions fire at the correct wall-clock time

### PostgreSQL Migration

- [x] Database driver, URL, credentials injected via environment variables
  (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_DRIVER`, `DB_DIALECT`)
- [x] `application.yml` uses env vars with H2 defaults (local dev stays on H2, server uses PG)
- [x] Hibernate `ddl-auto: update` creates / migrates schema automatically
- [x] Production server uses PostgreSQL 14 on AWS RDS (same host as EC2)

### Auto-Login on Startup

- [x] `@EventListener(ApplicationReadyEvent.class)` in `AngelOneClient` — triggers Angel One
  login automatically when the app starts; no manual API call needed after a server restart
- [x] Daily cron on EC2 (`30 3 * * 1-5` UTC = 09:00 AM IST) re-runs login to refresh the
  Angel One JWT token before market open

### AWS EC2 Deployment

- [x] Instance: `stock-agent-jay` (t2.micro, Ubuntu 22.04, Mumbai `ap-south-1`)
- [x] Systemd service (`stock-agent.service`) — auto-restart on crash, `-Xmx512m` heap cap
- [x] Nginx reverse proxy (HTTP → localhost:8080)
- [x] Public dashboard: `http://13.203.216.12:8080/ui/dashboard`

---

## ★★★★☆ — Smart (Completed)

**Goal:** The agent understands the market calendar, picks better stocks, and can validate its own signals.

### Market Holiday Calendar

- [x] `MarketCalendarService` checks IST date against a configured NSE holiday list
- [x] Holiday list maintained in `config.yaml` (`market_holidays` key, ISO date strings)
- [x] All scheduler jobs skip on holidays; `GET /api/market/status` exposes current market state
- [x] Market status values: `PRE_MARKET`, `OPEN`, `AFTER_HOURS`, `CLOSED_HOLIDAY`, `CLOSED_WEEKEND`

### Smarter Phase 2 Stock Selection

- [x] Phase 1 now stores LTP per candidate (`Map<String, Double>` symbol → LTP)
- [x] Non-watchlist candidates sorted by LTP descending before entering the 500-slot Phase 2 cap
- [x] Watchlist symbols always included in Phase 2 regardless of rank
- [x] Result: OHLCV cache covers highest-priced (typically larger-cap, more liquid) stocks first

### News Sentiment

- [x] `NewsService` fetches headlines from Yahoo Finance search API (`query1.finance.yahoo.com`)
- [x] Scores 8 recent headlines using 27 positive + 27 negative keyword lists
- [x] Score formula: `50 + (positive_hits − negative_hits) × 5`, clamped to 0–100
- [x] Score > 65 → +5 to fundamental score; score < 35 → −5
- [x] Rate-limited to 3 concurrent Yahoo Finance calls via `Semaphore(3)`

### Backtesting Engine

- [x] `BacktestEngine` replays the agent's own buy strategy on 1-year cached OHLCV data
- [x] Strategy mirrors live signal rules:
  - BUY: Close > SMA200, SMA20 > SMA50 (golden cross), RSI 35–70
  - EXIT: stop-loss at −8%, target at +16%, or end of data (R:R = 2:1)
- [x] Per-symbol output: trade list, win rate, avg return, total return, max drawdown
- [x] Aggregate output: overall win rate, avg return per trade, avg max drawdown
- [x] `POST /api/backtest` with optional `{"symbols": [...]}` body (empty = full universe)

### Intraday Signal Generation

- [x] `refreshLivePrices()` in DataIngestionEngine — fast Phase 1-only quote refresh (~1–2 min)
  updates cached LTPs for all symbols already in the OHLCV cache
- [x] `intradaySignals()` scheduler: `0 0/30 10-14 * * MON-FRI` IST
  - Refreshes live prices
  - Generates signals using the same pipeline as 09:15
  - Risk-validates and submits to Telegram
- [x] Daily deduplication: `signaledSymbolsToday` (ConcurrentHashMap set) in SignalGenerator
  prevents the same symbol from being alerted twice in one day
- [x] `resetDailySignals()` called at 08:45 pre-market each day

### Trigger-on-Demand API Endpoints (all ★★★★☆)

- [x] `POST /api/refresh` — kicks off Phase 1+2 universe refresh in background
- [x] `POST /api/signals` — runs signal generation on cached universe, returns JSON
- [x] `POST /api/signals/submit` — full pipeline: generate → risk-validate → Telegram
- [x] `POST /api/scan` — per-symbol analysis on watchlist (sequential, 400ms gap)
- [x] `GET /api/market/status` — current market open/closed/holiday state
- [x] `POST /api/backtest` — run backtest on cached universe or specified symbols

---

## ★★★★★ — Full System (Planned)

**Goal:** Professional-grade system with security, resilience, and continuous learning.

### Security & Access Control
- [ ] HTTPS via Let's Encrypt (Certbot + Nginx)
- [ ] API key authentication for all REST endpoints
- [ ] IP allowlist for dashboard access

### Database & Resilience
- [ ] Flyway database migrations (replace `ddl-auto: update`)
- [ ] Automated PostgreSQL backups (pg_dump + S3)
- [ ] DB connection pooling tuning (HikariCP)

### Advanced Signal Quality
- [ ] Sector rotation scoring (compare stock vs sector performance)
- [ ] Institutional ownership change tracking (quarterly AMFI data)
- [ ] Multi-timeframe confirmation (weekly + daily signals must align)
- [ ] Options chain analysis (PCR, max pain) as a macro filter

### Advanced Learning Engine
- [ ] Automatic weight adjustment based on 3-month rolling win rate
- [ ] Rejection pattern analysis (reject reasons → improve filter rules)
- [ ] Confidence calibration: if 70% score signals only win 40% of the time, recalibrate

### Operational Excellence
- [ ] Prometheus metrics endpoint + Grafana dashboard
- [ ] Structured JSON logging (ELK / CloudWatch)
- [ ] Slack alerts as fallback if Telegram is down
- [ ] Weekly automated backtest run + email report

---

## Trading Phases (Go-Live Checklist)

Follow these phases in order — **never skip straight to live trading**.

### Phase 1 — Paper Trading (Weeks 1–4)
- Keep `paper_trading.enabled: true`
- Approve/Reject signals as if real
- Track would-be P&L in a spreadsheet
- **Pass criteria:** Win rate > 50% over at least 20 signals

### Phase 2 — Live, Manual Approval (Weeks 5–8)
- Set `paper_trading.enabled: false`, `auto_mode: false`
- Deploy with small capital (₹25,000–₹50,000)
- **Pass criteria:** 4 weeks profitable, execution errors = 0

### Phase 3 — Semi-Auto Mode (Months 3–4)
- Set `auto_execute_threshold: 90`, `auto_mode: true`
- Only 90%+ confidence signals auto-execute (small positions)
- All others require manual approval
- **Pass criteria:** Auto-executed signals win rate ≥ manual approval win rate

### Phase 4 — Full Operation (Month 5+)
- Tune thresholds using 3-month performance data
- Scale capital gradually (₹1L → ₹5L → ₹10L)
- Monthly learning engine review drives weight adjustments
