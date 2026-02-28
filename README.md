# 🤖 AI Personal Trading & Investment Agent

An AI-driven trading agent for Indian markets (NSE/BSE) built with **Java 21 + Spring Boot**.
It analyses stocks using fundamental, technical, and macro data — then sends trade proposals to your **Telegram** for approval before placing any order on **Angel One**.

> **Capital safety first.** No order is ever placed without your explicit approval.
> Start in paper-trading mode (default) and only go live after validating performance.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Getting Your Credentials](#getting-your-credentials)
5. [Setup & Installation](#setup--installation)
6. [Configuration Reference](#configuration-reference)
7. [Running the Agent](#running-the-agent)
8. [Using the Agent (Telegram Commands)](#using-the-agent-telegram-commands)
9. [REST API](#rest-api)
10. [On-Demand Stock Analysis](#on-demand-stock-analysis)
11. [Web Dashboard](#web-dashboard)
12. [Implementation Phases](#implementation-phases)
13. [Project Structure](#project-structure)
14. [Troubleshooting](#troubleshooting)

---

## How It Works

Every trading day, the agent follows this flow automatically:

```
08:45 AM  →  Pre-market data refresh:
              ├─ Fetch Angel One wallet: available cash + holdings value → portfolio total
              ├─ Phase 1: Batch live-quote filter across full NSE equity universe
              │           (~2500 symbols → ~300–800 candidates, ~5 seconds)
              └─ Phase 2: Fetch 1-year daily OHLCV for each candidate in parallel
                          (10 concurrent API calls via semaphore, ~3–5 min)

09:15 AM  →  Analysis pipeline runs on all filtered stocks
              ├─ Fundamental Analysis  (ROE, ROCE, D/E, revenue growth)
              ├─ Technical Analysis    (200/50/20 DMA, RSI, MACD, Volume)
              ├─ Macro Context         (India VIX, Nifty vs 200DMA, FII flow)
              └─ Confidence Score computed (0–100%)

              If score ≥ 60% AND all 15 risk rules pass:
              └─ Pre-Trade Report sent to your Telegram

You reply:  APPROVE TRD-XXXXXXXX   →  Limit order placed on Angel One
            REJECT TRD-XXXXXXXX    →  Signal archived, no trade

Every 15 min  →  Open positions monitored (stop-loss, trailing stop, target)
03:30 PM      →  Daily P&L summary sent to Telegram
```

---

## Architecture

The system is built in **7 layers**, each with a single responsibility:

| Layer | Component | What It Does |
|-------|-----------|--------------|
| 1 | **Data Ingestion Engine** | Two-phase scan: batch quote filter → parallel OHLCV fetch for full NSE universe |
| 1 | **Instrument Master Service** | Downloads Angel One scrip master (~2500 NSE symbols), provides dynamic token resolution |
| 1 | **Portfolio Value Service** | Fetches live wallet value (cash + holdings) from Angel One for accurate position sizing |
| 2 | **Analysis Engine** | Runs 4 analysis modules in parallel |
| 3 | **Signal Generator** | Aggregates scores into a Confidence Score + trade signal across the full universe |
| 3 | **Stock Analysis Service** | On-demand single-stock analysis — full pipeline, no threshold filtering |
| 4 | **Risk Validator** | Enforces 15+ hard rules (position size, sector cap, etc.) |
| 5 | **Pre-Trade Report** | Formats the full trade proposal report |
| 6 | **Approval & Execution** | Sends to Telegram, waits for reply, places order |
| 7 | **Portfolio Monitor** | Monitors open positions, triggers stop-losses, tracks P&L |

### Confidence Score Breakdown

| Module | Weight | What It Measures |
|--------|--------|-----------------|
| Fundamental Analysis | 35% | Business quality — ROE, ROCE, D/E, cash flow, promoter holding |
| Technical Analysis | 30% | Entry timing — DMA, RSI, MACD, volume, breakout |
| Macro Context | 20% | Market environment — VIX, Nifty trend, FII flow |
| Risk-Reward Ratio | 15% | Stop-loss distance vs target distance (minimum 1:2) |

| Score | Classification | Action |
|-------|---------------|--------|
| 85–100% | High Conviction | Auto-execute eligible (if auto-mode on) |
| 70–84% | Strong Signal | Sent for approval — recommended |
| 60–69% | Moderate Signal | Sent for approval — reduce size by 50% |
| < 60% | Weak / Reject | Discarded silently |

---

## Prerequisites

Before you start, make sure you have:

- **Java 21** — [Download](https://adoptium.net/)
- **Maven 3.8+** — [Download](https://maven.apache.org/download.cgi)
- **Angel One demat account** with SmartAPI enabled
- **Telegram account** with a bot created via @BotFather

Verify your Java installation:
```bash
java -version   # should show: openjdk 21...
mvn -version    # should show: Apache Maven 3.x...
```

---

## Getting Your Credentials

You need **6 values** total. Here is exactly where to get each one:

### 1. Angel One API Key (`ANGEL_API_KEY`)
1. Log in to [smartapi.angelbroking.com](https://smartapi.angelbroking.com)
2. Go to **My Apps** → **Create App**
3. Fill in app name and redirect URL (use `http://localhost`)
4. Copy the **API Key** shown after creation

### 2. Angel One Client ID (`ANGEL_CLIENT_ID`)
- This is your **Angel One login ID** (e.g. `A123456`)
- Found on your Angel One account profile or welcome email

### 3. Angel One MPIN (`ANGEL_MPIN`)
- Your **4-digit MPIN** used to log in to the Angel One app
- If you forgot it, reset it via the Angel One mobile app

### 4. TOTP Secret (`ANGEL_TOTP_SECRET`)
Angel One requires TOTP (Time-based One-Time Password) for API login:
1. Open the **Angel One mobile app**
2. Go to **Profile** → **Enable TOTP**
3. When the QR code appears, tap **"Can't scan? Use key instead"**
4. Copy the **base32 secret key** shown (looks like: `JBSWY3DPEHPK3PXP`)
5. Store this — you'll only see it once

> ⚠️ Do NOT scan this QR code with Google Authenticator yet if you want the raw key.
> The raw base32 key is what the agent needs to generate TOTP codes automatically.

### 5. Telegram Bot Token (`TELEGRAM_BOT_TOKEN`)
1. Open Telegram and search for **@BotFather**
2. Send: `/newbot`
3. Follow the prompts to name your bot
4. BotFather will give you a token like: `7123456789:AAF...xyz`
5. Copy that token

### 6. Telegram Chat ID (`TELEGRAM_CHAT_ID`)
1. Send any message to your newly created bot
2. Open this URL in your browser (replace `<TOKEN>` with your bot token):
   ```
   https://api.telegram.org/bot<TOKEN>/getUpdates
   ```
3. Look for `"chat"` → `"id"` in the response JSON
4. That number is your Chat ID (e.g. `987654321`)

---

## Setup & Installation

### Step 1 — Clone / Open the project
```bash
cd C:\Users\jayan\projects\stock-agent
```

### Step 2 — Create your `.env` file
```bash
# Copy the template
copy .env.example .env
```

Open `.env` in any text editor and fill in your 6 values:
```env
ANGEL_API_KEY=your_api_key_here
ANGEL_CLIENT_ID=A123456
ANGEL_MPIN=1234
ANGEL_TOTP_SECRET=JBSWY3DPEHPK3PXP

TELEGRAM_BOT_TOKEN=7123456789:AAF...xyz
TELEGRAM_CHAT_ID=987654321
```

### Step 3 — Set environment variables
The application reads credentials from environment variables. Set them in your terminal before running:

**Windows (Command Prompt):**
```cmd
set ANGEL_API_KEY=your_api_key_here
set ANGEL_CLIENT_ID=A123456
set ANGEL_MPIN=1234
set ANGEL_TOTP_SECRET=JBSWY3DPEHPK3PXP
set TELEGRAM_BOT_TOKEN=7123456789:AAF...xyz
set TELEGRAM_CHAT_ID=987654321
```

**Windows (PowerShell):**
```powershell
$env:ANGEL_API_KEY="your_api_key_here"
$env:ANGEL_CLIENT_ID="A123456"
$env:ANGEL_MPIN="1234"
$env:ANGEL_TOTP_SECRET="JBSWY3DPEHPK3PXP"
$env:TELEGRAM_BOT_TOKEN="7123456789:AAF...xyz"
$env:TELEGRAM_CHAT_ID="987654321"
```

### Step 4 — Review `config.yaml`
Open `src/main/resources/config.yaml` and verify these key settings before running:

```yaml
paper_trading:
  enabled: true          # ← KEEP TRUE until you've validated the system
  virtual_balance_inr: 500000  # Virtual balance used for position sizing in paper mode

portfolio:
  total_value_inr: 500000  # Fallback only — used if Angel One wallet fetch fails in live mode

execution:
  auto_mode: false         # ← KEEP FALSE — always require manual approval

filters:
  min_stock_price_inr: 10       # Penny stock cutoff
  min_avg_daily_volume_cr: 1.0  # Minimum liquidity (₹1 Cr traded/day)
  include_bse: false            # Set true to also scan BSE equities
  max_analysis_universe: 500    # Cap on stocks analysed per cycle
```

> **Portfolio value in live mode** is fetched dynamically from your Angel One wallet
> (available cash + current holdings value) at the start of every cycle — no manual updates needed.

### Step 5 — Build the project
```bash
mvn clean compile
```
You should see: `BUILD SUCCESS`

---

## Running the Agent

```bash
mvn spring-boot:run
```

Successful startup looks like:
```
AgentConfig loaded from 'config.yaml'. Paper-trading mode: true
TelegramService initialized. Bot configured: true
InstrumentMasterService: downloading scrip master from Angel One...
InstrumentMasterService: loaded 2499 NSE + 0 BSE equity instruments
ApprovalGateway initialized — listening for APPROVE/REJECT commands
Started StockAgentApplication in ~19 seconds
```

> The instrument master download takes ~10 seconds on first boot. If it fails (no internet),
> the agent falls back to a 20-symbol hardcoded map and logs a warning.

The agent is now running at `http://localhost:8080`

**To stop the agent:** Press `Ctrl + C` in the terminal

---

## Using the Agent (Telegram Commands)

Once the agent is running, all interaction happens through your Telegram bot.

### Receiving Trade Signals

At 9:15 AM on weekdays, if any signals are generated, you'll receive a message like:

```
📊 PRE-TRADE ANALYSIS REPORT  —  27-Feb-2026 09:15
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TRADE ID          :  TRD-A1B2C3D4
ASSET NAME        :  RELIANCE (NSE: RELIANCE)
SIGNAL TYPE       :  BUY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
BUY PRICE         :  ₹2,850.00  (Limit order)
TARGET PRICE      :  ₹3,100.00
STOP-LOSS PRICE   :  ₹2,720.00  (NEVER moved down)
RISK-REWARD RATIO :  1 : 1.9
CONFIDENCE SCORE  :  74%  [F:80% T:70% M:65% RR:72%]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CAPITAL ALLOC     :  ₹50,000  (10% of portfolio)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📲 Reply: APPROVE TRD-A1B2C3D4  or  REJECT TRD-A1B2C3D4 [reason]
⏰ Signal expires at: 27-Feb-2026 09:45
```

### Approving a Trade
```
APPROVE TRD-A1B2C3D4
```
→ In paper mode: you get a confirmation with simulated execution
→ In live mode: a LIMIT order is placed on Angel One immediately

### Rejecting a Trade
```
REJECT TRD-A1B2C3D4 valuation too high
```
→ Signal is archived with your reason (used by the learning engine)
→ No order is placed

### Other Commands

| Command | What It Does |
|---------|-------------|
| `STATUS` | Shows agent mode, pending signals, open positions count |
| `POSITIONS` | Lists all open positions with entry price, SL, and target |

### Alerts You Will Receive Automatically

| Alert | When |
|-------|------|
| 📈 **Trailing Stop Updated** | When a position gains enough to activate trailing stop |
| 💰 **Partial Profit Opportunity** | When price reaches 50% of target |
| 🎯 **Target Hit** | When price reaches full target |
| 🔴 **Stop-Loss Triggered** | When price hits stop-loss (auto-sell, no approval needed) |
| 📊 **End-of-Day Summary** | Every day at 3:30 PM with P&L |
| 📈 **Monthly Review** | 1st of every month with win rate and performance stats |

---

## REST API

The agent exposes a dashboard API at `http://localhost:8080`:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/status` | Agent health, mode, open positions count |
| `GET` | `/api/positions` | All open (executed) positions |
| `GET` | `/api/signals/pending` | Signals waiting for your approval |
| `GET` | `/api/signals/history?days=30` | Trade history for last N days |
| `GET` | `/api/performance` | Win rate, P&L, confidence calibration |
| `GET` | `/api/analyse/{symbol}` | On-demand full analysis for any NSE symbol |
| `POST` | `/api/telegram/test` | Test Telegram bot connectivity |
| `POST` | `/api/broker/login` | Refresh Angel One session |

**Examples:**
```bash
# Check if everything is running
curl http://localhost:8080/api/status

# On-demand stock analysis (returns full JSON breakdown)
curl http://localhost:8080/api/analyse/RELIANCE
curl http://localhost:8080/api/analyse/TCS

# See all open positions
curl http://localhost:8080/api/positions

# View performance stats
curl http://localhost:8080/api/performance

# Test Telegram (sends a test message to your bot)
curl -X POST http://localhost:8080/api/telegram/test

# Login to Angel One (do this on first run)
curl -X POST http://localhost:8080/api/broker/login
```

**H2 Database Console** (paper trading mode only):
Open `http://localhost:8080/h2-console` → JDBC URL: `jdbc:h2:mem:stockagent`

---

## On-Demand Stock Analysis

You can analyse any NSE stock at any time — outside the scheduled 9:15 AM cycle.

### Via the UI
Open `http://localhost:8080/ui/analyse`, type a symbol, and click **Analyse**:

```
┌─────────────────────────────────────────────────────┐
│  [ RELIANCE               ]  [ ⚡ Analyse ]          │
└─────────────────────────────────────────────────────┘

Verdict: BUY                              ₹2,856.40 LTP
─────────────────────────────────────────────────────
Confidence Score          72.4%  ████████████░░░░
  Fundamental  (35%)      78.0%  ███████████████░
  Technical    (30%)      65.0%  █████████████░░░
  Macro        (20%)      70.0%  ██████████████░░
  Risk-Reward  (15%)      70.0%  ██████████████░░

Technical          SMA 20 ▲  SMA 50 ▲  SMA 200 ▲
                   RSI 52 · Volume confirmed

Fundamental        ROE 18%  ROCE 14%  D/E 0.4x
                   PE 24x   PEG 1.1

Suggested Levels   Entry ₹2,856  Target ₹3,142 (+10%)
                   Stop ₹2,713 (-5%)  R:R 1:2.0
```

### Via the API
```bash
curl http://localhost:8080/api/analyse/RELIANCE
```

Returns a JSON object with:
- `compositeScore`, `verdict` — overall assessment
- `fundamentalScore`, `technicalScore`, `macroScore`, `rrScore` — per-module breakdown
- `roe`, `roce`, `debtToEquity`, `peRatio`, `pegRatio`, `promoterHolding`
- `sma200`, `sma50`, `sma20`, `rsi`, `goldenCross`, `deathCross`, `volumeConfirmed`
- `suggestedEntry`, `suggestedTarget`, `suggestedStopLoss`, `riskReward`
- `indiaVix`, `niftyPrice`, `marketRegime`

### Verdict thresholds

| Score | Verdict |
|-------|---------|
| 80–100% | STRONG BUY |
| 65–79% | BUY |
| 50–64% | HOLD |
| < 50% | AVOID |

> Unlike the scheduled pipeline, on-demand analysis has **no minimum threshold** — it always returns
> the full breakdown regardless of score, so you can see exactly why a stock scores low.

---

## Web Dashboard

The full dashboard is available at `http://localhost:8080/ui/`:

| Page | URL | What It Shows |
|------|-----|---------------|
| Dashboard | `/ui/dashboard` | Agent status, open positions count, pending signals, recent activity |
| Positions | `/ui/positions` | All open positions with entry price, SL, target |
| Signals | `/ui/signals` | Pending approvals + full signal history |
| Performance | `/ui/performance` | Win rate, cumulative P&L chart, confidence calibration |
| **Analyse Stock** | `/ui/analyse` | **On-demand single-stock analysis with full breakdown** |

---

## Implementation Phases

Follow these phases — **never skip straight to live trading**:

### Phase 1 — Paper Trading (Weeks 1–4) ← You are here
- `paper_trading.enabled: true` in config.yaml
- Agent generates signals and sends reports to Telegram
- You reply APPROVE/REJECT as if it were real
- No real orders are placed
- **Goal:** Validate that signals make sense. Track would-be P&L manually.

### Phase 2 — Manual Approval, Real Money (Weeks 5–8)
- Set `paper_trading.enabled: false`
- Set `auto_mode: false` (keep this — every trade needs your approval)
- Connect with real Angel One credentials
- Start with **small capital** (e.g. ₹50,000)
- **Goal:** Build confidence that the system executes correctly.

### Phase 3 — Semi-Auto Mode (Months 3–4)
- Set `auto_execute_threshold: 90` in config.yaml
- Set `auto_mode: true`
- Only signals with 90%+ confidence auto-execute (small positions)
- All others still require manual approval
- **Goal:** Validate auto-execution on high-conviction signals only.

### Phase 4 — Full Operation (Month 5+)
- Adjust thresholds based on 3+ months of live performance data
- Scale capital gradually
- Review monthly learning reports to tune weights

---

## Project Structure

```
stock-agent/
├── pom.xml                          # Maven dependencies
├── .env.example                     # Credential template — copy to .env
├── src/main/
│   ├── java/com/jay/stagent/
│   │   ├── StockAgentApplication.java       # App entry point
│   │   ├── config/
│   │   │   └── AgentConfig.java             # Loads config.yaml
│   │   ├── model/                           # Data models (TradeSignal, StockAnalysisResult, etc.)
│   │   ├── entity/                          # JPA database entities
│   │   ├── repository/                      # Spring Data repositories
│   │   ├── layer1_data/
│   │   │   ├── AngelOneClient.java          # Angel One REST API client
│   │   │   ├── DataIngestionEngine.java     # Two-phase universe scan + caching
│   │   │   ├── InstrumentMasterService.java # Scrip master download + token resolution
│   │   │   └── PortfolioValueService.java   # Live wallet fetch (cash + holdings)
│   │   ├── layer2_analysis/
│   │   │   ├── TechnicalAnalysisModule.java # DMA, RSI, MACD, Volume (ta4j)
│   │   │   ├── FundamentalAnalysisModule.java # Screener.in data
│   │   │   ├── MacroContextModule.java       # VIX, Nifty, FII
│   │   │   └── MutualFundAnalysisModule.java # AMFI NAV analysis
│   │   ├── layer3_signal/
│   │   │   ├── SignalGenerator.java          # Parallel analysis → signal (scheduled)
│   │   │   └── StockAnalysisService.java     # On-demand single-stock analysis
│   │   ├── layer4_risk/
│   │   │   └── RiskValidator.java            # 15+ hard rules
│   │   ├── layer5_report/
│   │   │   └── PreTradeReportGenerator.java  # Telegram report formatter
│   │   ├── layer6_execution/
│   │   │   ├── ApprovalGateway.java          # APPROVE/REJECT handler
│   │   │   └── ExecutionEngine.java          # Order placement
│   │   ├── layer7_monitor/
│   │   │   ├── PortfolioMonitor.java         # SL checks, trailing stops
│   │   │   └── LearningEngine.java           # Win rate, calibration
│   │   ├── notification/
│   │   │   └── TelegramService.java          # Telegram Bot API
│   │   ├── scheduler/
│   │   │   └── TradingScheduler.java         # Cron jobs (8:45, 9:15, 15min, 3:30)
│   │   └── controller/
│   │       ├── AgentStatusController.java    # REST endpoints (incl. /api/analyse/{symbol})
│   │       └── DashboardController.java      # UI routes (incl. /ui/analyse)
│   └── resources/
│       ├── application.yml                   # Spring Boot configuration
│       ├── config.yaml                       # Agent trading configuration
│       └── templates/
│           ├── dashboard.html                # Main dashboard
│           ├── positions.html                # Open positions
│           ├── signals.html                  # Signal history
│           ├── performance.html              # P&L + win rate charts
│           ├── analyse.html                  # On-demand stock analysis UI
│           └── fragments/layout.html         # Shared sidebar + styles
```

---

## Troubleshooting

### Port 8080 already in use
```bash
# Find and kill the process
netstat -ano | findstr :8080
# Note the PID from the last column, then:
taskkill /PID <PID> /F
```

### Angel One login fails
- Verify your MPIN is correct (not your app password)
- Check your TOTP secret — it must be the raw **base32 key**, not a 6-digit code
- Ensure your API key is from the SmartAPI portal, not the trading platform

### No signals generated
- Check if VIX > 25 (all buys suppressed — check `/api/status`)
- Check if it's a market holiday
- Check logs for "below threshold" — confidence scores might all be < 60%
- Verify `config.yaml` watchlist has valid NSE symbols
- Check Phase 1 logs: if `0 candidates pass price/volume filter`, lower `min_avg_daily_volume_cr`

### Telegram messages not arriving
- Call `POST /api/telegram/test` — if it returns `"connected": false`, your token is wrong
- Make sure you sent at least one message to your bot first (Telegram requires this)
- Verify the Chat ID is correct (it's a number, not a username)

### Config not loading
- Ensure `config.yaml` is in `src/main/resources/`
- Check logs for "AgentConfig loaded" — if you see "Failed to load", check YAML formatting
- YAML is indentation-sensitive — use spaces, not tabs

### Position sizes look wrong / using config value instead of real balance
- Check logs for `Failed to fetch portfolio value from Angel One` — this means the wallet API failed
- Ensure you've called `POST /api/broker/login` to authenticate before the first cycle runs
- In paper mode this is expected — virtual balance from `paper_trading.virtual_balance_inr` is always used
- In live mode, `portfolio.total_value_inr` in config.yaml acts as a fallback — set it to your approximate balance

### Analyse page returns "Could not fetch market data"
- The symbol must be a valid NSE equity symbol exactly as it appears on the exchange (e.g. `RELIANCE`, `M&M`, `BAJAJ-AUTO`)
- Ensure you've called `POST /api/broker/login` first — historical data requires authentication
- Check logs for `No token found for symbol` — the symbol may not be in the instrument master (rare for large-caps)

### Hibernate dialect warning
The H2 dialect warning on startup is harmless — it's just a deprecation notice from Hibernate:
```
HHH90000025: H2Dialect does not need to be specified explicitly
```
You can ignore this in paper-trading mode.

---

## Important Disclaimers

- This system is for **personal use only**. Offering it as a service requires SEBI registration.
- All trading involves risk. The agent can generate incorrect signals.
- Short-term capital gains are taxed at **20%** in India. Keep records for ITR filing.
- Always start with small capital and validate over time before scaling.
- The agent cannot predict black swan events or corporate frauds.
- **You are solely responsible** for all trading decisions made using this system.
