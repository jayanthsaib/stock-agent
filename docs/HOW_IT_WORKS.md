# How the Agent Works — Complete Flow

---

## Every Day (Automated, No Action Needed)

### 08:45 AM IST — Pre-Market
```
TradingScheduler fires
  ↓
MarketCalendarService.isMarketOpen()
  ├── Weekend or NSE holiday? → skip everything, go back to sleep
  └── Market day? → continue
  ↓
SignalGenerator.resetDailySignals()    ← clears "already alerted today" memory
  ↓
DataIngestionEngine.refreshAll()       ← THE BIG SCAN (5–15 min)
```

**Phase 1 — Price & Volume Filter:**
```
Angel One scrip master: ~2500 NSE symbols
  ↓
Batch getQuote() calls (50 symbols per call, 200ms gap)
  ↓
Filter: LTP ≥ ₹10  AND  20-day avg volume ≥ ₹1 Cr/day
  ↓
Store symbol → LTP in a map
Sort non-watchlist stocks by LTP descending (bigger stocks first)
Cap at top 500 candidates (watchlist always included)
```

**Phase 2 — 1-Year OHLCV Fetch:**
```
Up to 500 candidates (sequential, one at a time, 500ms gap)
  ↓
getHistoricalData() → 252 daily OHLCV bars per symbol
  ↓
Cache populated: ~400–500 stocks with full price history
```

---

### 09:15 AM IST — Signal Generation
```
TradingScheduler fires
  ↓
Wait if Phase 2 is still running (up to 10 min)
  ↓
SignalGenerator.generateSignals()
```

For **every stock in the cache** (~400–500), 4 modules run in parallel:

```
┌──────────────────────────────────────────────┐
│  Stock: RELIANCE (400+ stocks run in parallel) │
├────────────────┬─────────────────────────────┤
│ FUNDAMENTAL    │ Scrapes Screener.in          │
│ (35% weight)   │ ROE, ROCE, D/E, FCF,         │
│                │ revenue growth, PE, PEG,     │
│                │ promoter holding             │
│                │ + Yahoo Finance news:        │
│                │   8 headlines → sentiment    │
│                │   score → ±5 adjustment      │
├────────────────┼─────────────────────────────┤
│ TECHNICAL      │ Uses ta4j on cached OHLCV    │
│ (30% weight)   │ SMA200, SMA50, SMA20         │
│                │ RSI(14), MACD(12/26/9)       │
│                │ Volume vs 20-day avg         │
│                │ Golden cross detection       │
│                │ Entry/Target/Stop levels     │
├────────────────┼─────────────────────────────┤
│ MACRO          │ NSE website scrape           │
│ (20% weight)   │ India VIX level              │
│                │ Nifty 50 vs SMA200           │
│                │ FII net buy/sell flow        │
├────────────────┼─────────────────────────────┤
│ RISK-REWARD    │ Entry to stop distance       │
│ (15% weight)   │ Entry to target distance     │
│                │ R:R ratio scored             │
└────────────────┴─────────────────────────────┘
          ↓
Composite Score = Fund×35% + Tech×30% + Macro×20% + RR×15%
```

**Filtering:**
```
Score < 60%  → discard silently
Score ≥ 60%  → RiskValidator (15+ hard rules)

Risk rules check:
  - VIX > 25?                       → reject all BUY signals
  - Already have this stock open?   → reject
  - Sector over 25% of portfolio?   → reject
  - Position would exceed 10%?      → reject
  - Cash reserve would drop < 20%?  → reject
  - R:R ratio < 1.5?                → reject
  - Daily loss > 3%?                → reject

All rules pass? → PreTradeReportGenerator formats Telegram message
Already alerted this symbol today? → skip (dedup)
```

**Telegram message sent:**
```
📊 PRE-TRADE ANALYSIS REPORT — 02-Mar-2026 09:15
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TRADE ID     : TRD-A1B2C3D4
ASSET        : RELIANCE (NSE)
SIGNAL TYPE  : BUY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
BUY PRICE    : ₹2,850.00
TARGET       : ₹3,136.00  (+10%)
STOP-LOSS    : ₹2,622.00  (-8%)
R:R RATIO    : 1 : 2.0
CONFIDENCE   : 74%  [F:80% T:70% M:65% RR:72%]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CAPITAL      : ₹50,000 (10% of portfolio)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Reply: APPROVE TRD-A1B2C3D4  or  REJECT TRD-A1B2C3D4
Expires: 09:45 AM
```

---

### You (Every Morning)
```
You see the Telegram message
  ↓
APPROVE TRD-A1B2C3D4
  ↓
Agent receives it (polling every 2 seconds)
  ↓
Paper mode:  simulates execution, saves TradeRecord (status=EXECUTED)
Live mode:   places LIMIT order on Angel One at entry price
  ↓
Confirmation sent to Telegram

OR

REJECT TRD-A1B2C3D4 valuation too high
  ↓
Signal archived with your reason (used by LearningEngine later)
No order placed
```

---

### 10:00 AM → 2:30 PM (Every 30 Min) — Intraday Signals
```
DataIngestionEngine.refreshLivePrices()
  ← fast batch quote refresh (~1–2 min)
  ← updates cached LTPs for all 400+ symbols
  ↓
SignalGenerator.generateSignals()
  ← same full pipeline as 9:15
  ← signaledSymbolsToday prevents re-alerting stocks already sent today
  ↓
New signals → Telegram
```

---

### 9:30 AM → 3:30 PM (Every 15 Min) — Position Monitoring
```
PortfolioMonitor.monitorPositions()
  ↓
For each EXECUTED TradeRecord:
  → fetchCurrentPrice() via AngelOneClient.getQuote() (live price)
  → Check stop-loss hit?    → auto-sell + Telegram alert 🔴
  → Check target hit?       → auto-sell + Telegram alert 🎯
  → 50% of target reached?  → Telegram alert 💰 (manual action)
  → Trailing stop update?   → move stop up + Telegram alert 📈

ApprovalGateway.expireTimedOutSignals()
  → any PENDING_APPROVAL signals older than 30 min → mark EXPIRED
```

---

### 3:30 PM — End of Day
```
PortfolioMonitor.sendDailySummary()
  ↓
Telegram message:
  - All open positions: entry, current price, unrealised P&L
  - Closed today: realised P&L
  - Total day P&L
```

---

### All Day (Every 2 Seconds) — Telegram Polling
```
TelegramService.pollForMessages()
  ↓
Fetches new messages from Telegram Bot API
  ↓
APPROVE TRD-xxx → ExecutionEngine.execute()
REJECT TRD-xxx  → archive with reason
STATUS          → reply with open positions count
POSITIONS       → reply with all open positions

lastUpdateId saved to disk after every poll
(survives restarts — no duplicate processing)
```

---

### 1st of Every Month — Learning Review
```
LearningEngine.runMonthlyReview()
  ↓
Telegram report:
  - Win rate by confidence decile (is 70% score actually winning 70%?)
  - Best/worst performing sectors
  - Most common rejection reasons
```

---

## REST API (Manual Triggers, Secured)

All `/api/*` endpoints require `X-API-Key` header.

| You call | What happens |
|----------|-------------|
| `POST /api/refresh` | Triggers Phase 1+2 scan in background |
| `POST /api/signals/submit` | Full pipeline → Telegram (same as 9:15 run) |
| `POST /api/scan` | Analyses your watchlist only, returns scores |
| `POST /api/backtest` | Replays strategy on 1yr OHLCV, returns win rate |
| `GET /api/analyse/RELIANCE` | Full analysis for one stock, no threshold |
| `GET /api/status` | Health check |
| `GET /api/market/status` | Is today a trading day? |

---

## What the Database Stores

Every signal ever generated:
```
trade_records table:
  - Trade ID, symbol, status
  - Entry/target/stop prices
  - All 4 confidence sub-scores
  - Timestamps (generated, approved, executed, closed)
  - Exit price, realised P&L, exit reason
  - Your rejection reason (if rejected)
```

Flyway ensures this schema is always in sync with the code, versioned.
PostgreSQL on the server. H2 in memory for local dev.

---

## Infrastructure

```
You (Telegram) ←──────────────────────────────────────────────┐
                                                               │
                         AWS EC2 (Mumbai)                      │
                    ┌────────────────────────┐                 │
HTTPS request  →    │  Nginx (port 443)      │                 │
                    │  self-signed TLS cert  │                 │
                    │  HTTP → HTTPS redirect │                 │
                    └──────────┬─────────────┘                 │
                               │ proxy_pass                    │
                    ┌──────────▼─────────────┐                 │
                    │  Spring Boot (port 8080)│                 │
                    │  ApiKeyFilter (auth)    │                 │
                    │  TradingScheduler       │─────────────────┘
                    │  7-layer pipeline       │  (Telegram Bot API)
                    └──────────┬─────────────┘
                               │
                    ┌──────────▼─────────────┐
                    │  PostgreSQL             │
                    │  Flyway migrations      │
                    │  Daily pg_dump backup   │
                    └────────────────────────┘
```

---

The agent runs entirely on its own during market hours.
You only need to respond to Telegram messages.
