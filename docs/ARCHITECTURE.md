# Architecture — Cerebro

## Overview

The agent is built in **7 layers**, each with a single responsibility.
Data flows strictly top-to-bottom; no layer calls a higher layer.

```
┌─────────────────────────────────────────────────────────────────────┐
│  SCHEDULER  (TradingScheduler.java)                                 │
│  08:45 pre-market · 09:15 signals · every 30min intraday · 15:30   │
└────────────────────────┬────────────────────────────────────────────┘
                         │ drives
           ┌─────────────▼─────────────┐
           │   Layer 1 — DATA          │  AngelOneClient, DataIngestionEngine,
           │                           │  InstrumentMasterService, MarketCalendarService,
           │                           │  PortfolioValueService
           └─────────────┬─────────────┘
                         │ StockData (symbol + 1yr OHLCV + LTP)
           ┌─────────────▼─────────────┐
           │   Layer 2 — ANALYSIS      │  FundamentalAnalysisModule (Screener.in)
           │   (4 modules in parallel) │  TechnicalAnalysisModule (ta4j)
           │                           │  MacroContextModule (NSE/VIX)
           │                           │  MutualFundAnalysisModule (AMFI)
           │                           │  NewsService (Yahoo Finance headlines)
           └─────────────┬─────────────┘
                         │ StockAnalysisResult (4 module scores + indicators)
           ┌─────────────▼─────────────┐
           │   Layer 3 — SIGNAL        │  SignalGenerator (CompletableFuture)
           │                           │  StockAnalysisService (on-demand)
           │                           │  BacktestEngine (historical validation)
           └─────────────┬─────────────┘
                         │ TradeSignal (confidence score, entry/SL/target)
           ┌─────────────▼─────────────┐
           │   Layer 4 — RISK          │  RiskValidator (15+ hard rules)
           └─────────────┬─────────────┘
                         │ ValidationResult (passed/failed + reasons)
           ┌─────────────▼─────────────┐
           │   Layer 5 — REPORT        │  PreTradeReportGenerator
           └─────────────┬─────────────┘
                         │ Formatted Telegram message
           ┌─────────────▼─────────────┐
           │   Layer 6 — EXECUTION     │  ApprovalGateway (APPROVE/REJECT)
           │                           │  ExecutionEngine (Angel One order)
           └─────────────┬─────────────┘
                         │ TradeRecord saved to DB
           ┌─────────────▼─────────────┐
           │   Layer 7 — MONITOR       │  PortfolioMonitor (SL, trailing stop)
           │                           │  LearningEngine (calibration, monthly review)
           └───────────────────────────┘
```

---

## Layer 1 — Data

### AngelOneClient
- Angel One SmartAPI via OkHttp REST (no SDK)
- TOTP-based MPIN login; stores JWT + feed token in memory
- Auto-login on application startup (`@EventListener(ApplicationReadyEvent.class)`)
- Key methods: `login()`, `getQuote()`, `getHistoricalData()`, `placeOrder()`, `getHoldings()`
- All Angel One responses are parsed with Jackson `JsonNode`

### InstrumentMasterService
- Downloads Angel One scrip master JSON daily at midnight (and on startup)
- Parses ~2500 NSE + optional BSE equity instruments
- Filters ETFs by name pattern (`ETF`, `BEES`, `FUND`, `INDEX`, `GILT`) and symbol suffix (`-ETF`)
- Excludes derivative synthetic symbols: `BETA`, `NIFTY`, `SENSEX`
- `resolveToken(symbol, exchange)` → Angel One instrument token string

### DataIngestionEngine
Two-phase universe scan, results cached in `ConcurrentHashMap<String, StockData>`:

**Phase 1 — Price & Volume Filter**
```
All NSE symbols
→ Batch getQuote() (50 tokens/call, 200ms delay between batches)
→ Filter: LTP ≥ min_stock_price_inr AND 20-day avg volume ≥ min_avg_daily_volume_cr
→ Store LTP per symbol in Map<String, Double>
→ Sort non-watchlist candidates by LTP descending
→ Cap at top 500 (watchlist always included)
```

**Phase 2 — 1-Year OHLCV Fetch**
```
Up to 500 candidates (watchlist first, then LTP-ranked)
→ Sequential getHistoricalData() (Semaphore(1) + 500ms sleep)
→ Stores 252 daily bars in StockData.historicalBars
→ Filter: 20-day avg volume ≥ min_avg_daily_volume_cr
→ Cache populated: typically 400–500 symbols after filtering
```

**Intraday Price Refresh** (`refreshLivePrices()`)
```
All symbols already in OHLCV cache
→ Batch getQuote() (50 tokens/call, 200ms delay)
→ Updates StockData.ltp only — no OHLCV re-fetch
→ Takes ~1–2 minutes; safe to call every 30 minutes
```

**Race Condition Guard**
- `volatile boolean refreshInProgress` set/cleared in `refreshAll()`
- `TradingScheduler.marketOpenAnalysis()` polls this flag, waiting up to 10 minutes if
  the pre-market refresh is still running at 09:15

### MarketCalendarService
- Checks `config.yaml` `market_holidays` list (ISO date strings, maintained annually)
- `isMarketOpen()` returns false on weekends and holidays
- `getMarketStatus()` returns `PRE_MARKET | OPEN | AFTER_HOURS | CLOSED_HOLIDAY | CLOSED_WEEKEND`
- All scheduler jobs check `isMarketOpen()` before executing

### PortfolioValueService
- Fetches live wallet balance (available cash + holdings value) from Angel One
- Used by RiskValidator for accurate position sizing in live mode
- Paper-trading mode uses `config.yaml` `paper_trading.virtual_balance_inr`

---

## Layer 2 — Analysis

All four modules run in parallel via `CompletableFuture.supplyAsync()` in `SignalGenerator`.
Each returns a score 0–100 and a text summary.

### FundamentalAnalysisModule
- Scrapes Screener.in for financial ratios
- Metrics: P/E, ROE, ROCE, D/E, FCF, promoter holding, revenue growth, profit growth
- News sentiment overlay: `NewsService` fetches 8 Yahoo Finance headlines, adjusts score ±5
- Yahoo Finance calls rate-limited to `Semaphore(5)` concurrent calls

### TechnicalAnalysisModule
- Uses ta4j 0.22.2 (`org.ta4j.core.indicators.averages.SMAIndicator / EMAIndicator`)
- Indicators: SMA200, SMA50, SMA20, RSI(14), MACD(12/26/9), volume ratio
- Golden cross (SMA20 > SMA50), death cross detection
- Entry/Stop/Target levels computed from ATR or fixed percentage

### MacroContextModule
- India VIX from NSE website (parsed from HTML table)
- Nifty 50 last price vs 200 DMA (bullish / neutral / bearish regime)
- FII net buy/sell flow from NSE (positive = bullish)
- Suppresses all BUY signals if VIX > 25 (hard cutoff in RiskValidator)

### MutualFundAnalysisModule
- AMFI NAV data: identifies mutual funds holding the stock
- Sector trends inferred from top fund holdings

### NewsService
- Yahoo Finance search API: `https://query1.finance.yahoo.com/v1/finance/search?q={symbol}.NS&newsCount=8`
- 27 positive keywords: `profit`, `growth`, `beat`, `record`, `acquisition`, `order`, `win`, ...
- 27 negative keywords: `loss`, `fraud`, `scam`, `recall`, `downgrade`, `miss`, ...
- Score: `50 + (positive_hits − negative_hits) × 5`, clamped 0–100
- Semaphore(3) limits concurrent Yahoo Finance calls

---

## Layer 3 — Signal

### SignalGenerator
- Pulls all `StockData` from the DataIngestionEngine cache
- Runs all 4 analysis modules in parallel (CompletableFuture) for each stock
- Weighted composite score: Fund 35% + Tech 30% + Macro 20% + RR 15%
- Filters: composite score ≥ `min_confidence_to_notify` (default 60)
- Daily deduplication: `signaledSymbolsToday` (ConcurrentHashMap set) — same symbol not sent twice per day
- `resetDailySignals()` called at 08:45 by TradingScheduler

### StockAnalysisService
- Identical pipeline to SignalGenerator but for a single symbol
- Used by `GET /api/analyse/{symbol}` and `/ui/analyse`
- No minimum threshold — always returns full breakdown

### BacktestEngine
- Replays buy strategy on cached 1-year OHLCV data
- BUY rule: `Close > SMA200 AND SMA20 > SMA50 AND 35 ≤ RSI ≤ 70`
- Exit rules: stop-loss −8%, target +16%, or end of data
- One position at a time per symbol
- Outputs per-symbol: trades list, wins, win rate, avg/total return, max drawdown
- Outputs aggregate: overall win rate, avg return per trade, avg max drawdown
- Results sorted by total return descending

---

## Layer 4 — Risk

### RiskValidator
15+ hard rules, all must pass for a signal to proceed:

| Rule | Default | Config Key |
|------|---------|-----------|
| Min confidence score | 60% | `signal.min_confidence_to_notify` |
| Max position size | 10% | `risk.max_position_size_pct` |
| Max sector allocation | 25% | `risk.max_sector_allocation_pct` |
| Max open positions | 10 | `risk.max_open_positions` |
| Cash reserve | 20% | `risk.cash_reserve_pct` |
| Min R:R ratio | 1.5 | `risk.min_risk_reward_ratio` |
| VIX circuit-breaker | 25 | `risk.max_vix` |
| Daily loss limit | 3% | `risk.max_daily_loss_pct` |
| Duplicate symbol | 1 per symbol | (hard) |
| Min signal score | varies | (hard) |
| Min position size | ₹1,000 | (hard) |

---

## Layer 5 — Report

### PreTradeReportGenerator
Formats a multi-line Telegram message with:
- Trade ID, symbol, signal type
- Buy price, target, stop-loss, R:R ratio
- Confidence score breakdown (F/T/M/RR)
- Capital allocation
- Expiry time
- `APPROVE TRD-xxx` / `REJECT TRD-xxx` instructions

---

## Layer 6 — Execution

### ApprovalGateway
- Submits signals to Telegram; saves `TradeRecord` with status `PENDING_APPROVAL`
- `pollForMessages()` called every 2 seconds by TradingScheduler
- Parses `APPROVE TRD-xxx` → calls `ExecutionEngine.execute()`
- Parses `REJECT TRD-xxx [reason]` → archives signal with reason
- `expireTimedOutSignals()` called every 15 min — rejects stale pending signals
- `lastUpdateId` persisted to `~/.stock-agent-telegram-offset` (survives restarts)

### ExecutionEngine
- Paper mode: simulates order, sets `TradeRecord.status = EXECUTED`
- Live mode: calls `AngelOneClient.placeOrder()` (LIMIT order at entry price)
- Saves entry price, quantity, trade ID to `TradeRecord`

---

## Layer 7 — Monitor

### PortfolioMonitor
- Runs every 15 minutes on all `EXECUTED` `TradeRecord`s
- `fetchCurrentPrice()` uses `AngelOneClient.getQuote()` via `InstrumentMasterService.resolveToken()`
  (live prices, not stale `getHoldings()`)
- Checks: stop-loss hit, trailing stop activation, 50% target reached, full target reached
- Sends Telegram alert on each event
- `sendDailySummary()` at 15:30: all open positions + closed P&L for the day

### LearningEngine
- `runConfidenceCalibration()`: compares predicted confidence vs actual win rate per decile
- `runSectorAnalysis()`: which sectors have best/worst win rates
- `runRejectionAnalysis()`: most common rejection reasons
- `runMonthlyReview()`: sends full Telegram report on 1st of each month

---

## Database Schema

### `trade_record`

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGSERIAL PK | Auto-increment |
| `trade_id` | VARCHAR | Unique trade ID (e.g. `TRD-A1B2C3D4`) |
| `symbol` | VARCHAR | NSE symbol |
| `status` | VARCHAR | `PENDING_APPROVAL`, `EXECUTED`, `REJECTED`, `EXPIRED`, `CLOSED` |
| `entry_price` | DOUBLE | Buy price |
| `target_price` | DOUBLE | Target price |
| `stop_loss_price` | DOUBLE | Stop-loss price |
| `quantity` | INTEGER | Shares |
| `confidence_score` | JSONB | Per-module scores |
| `generated_at` | TIMESTAMP | When signal was created |
| `executed_at` | TIMESTAMP | When order was placed |
| `closed_at` | TIMESTAMP | When position was closed |
| `realised_pnl_inr` | DOUBLE | Realised profit/loss (₹) |
| `target_hit` | BOOLEAN | True if closed at target |
| `rejection_reason` | VARCHAR | User's REJECT reason |

---

## Scheduling Timeline

```
00:00      Instrument master refresh (midnight, daily)
03:30 UTC  Angel One auto-login cron (09:00 AM IST)
08:45 IST  Pre-market: resetDailySignals + refreshAll (Phase 1+2, 5–15 min)
09:15 IST  Signal generation + risk validate + Telegram submit
09:30 IST  Intraday monitor starts (every 15 min until 15:30)
10:00 IST  Intraday signals start (every 30 min until 14:30)
15:30 IST  End-of-day: daily summary to Telegram
           (All day, every 2s) Telegram APPROVE/REJECT polling
1st month  Monthly learning review (07:00 IST)
```

---

## Configuration Files

| File | Purpose |
|------|---------|
| `src/main/resources/config.yaml` | All agent trading parameters |
| `src/main/resources/application.yml` | Spring Boot + database config |
| `.env` | Secrets (Angel One + Telegram credentials) |
| `deploy/stock-agent.service` | Systemd service definition |

### Key `config.yaml` Sections

```yaml
paper_trading:
  enabled: true
  virtual_balance_inr: 500000

execution:
  auto_mode: false
  auto_execute_threshold: 90

signal:
  min_confidence_to_notify: 60
  approval_timeout_minutes: 30

risk:
  max_position_size_pct: 10
  max_sector_allocation_pct: 25
  max_open_positions: 10
  cash_reserve_pct: 20
  min_risk_reward_ratio: 1.5
  max_vix: 25

filters:
  min_stock_price_inr: 10
  min_avg_daily_volume_cr: 1.0
  max_analysis_universe: 500

watchlist:
  - RELIANCE
  - TCS
  - INFY
  - HDFCBANK
  - LT
  # (always included in Phase 2 regardless of score)

market_holidays:
  - "2026-01-26"   # Republic Day
  - "2026-03-25"   # Holi
  # ... (full NSE calendar maintained annually)
```
