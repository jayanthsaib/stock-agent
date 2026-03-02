# Trading Strategy — AI Stock Trading Agent

## Philosophy

The agent is a **swing trading system** for large/mid-cap Indian equities (NSE).
It targets 2–8 week holding periods with a fixed 2:1 risk-reward ratio on every trade.

Core principles:
1. **Capital safety first.** No trade without your explicit Telegram approval (in manual mode).
2. **Rule-based signals.** No emotion, no gut feel — every decision follows a checklist.
3. **Asymmetric bets.** Target is always ≥ 2× the stop-loss distance. Losers are small; winners pay for several losers.
4. **Universe-wide scanning.** The agent looks at all ~2500 NSE equities daily, not just a watchlist.
5. **No intraday.** Signals are based on daily OHLCV bars. Positions held days to weeks.

---

## Signal Generation Flow

```
NSE Universe (~2500 symbols)
      │
      ▼  Phase 1 filter (LTP + volume)
~300–800 candidates
      │
      ▼  Phase 2 — 1yr OHLCV fetch (top 500 by LTP)
400–500 stocks with OHLCV data
      │
      ▼  4 analysis modules (parallel)
Composite Score (0–100%)
      │
      ├── < 60% → discard silently
      │
      └── ≥ 60% → Risk Validator (15+ rules)
               │
               ├── Failed → discard with reason logged
               │
               └── Passed → Pre-Trade Report → Telegram
```

---

## Confidence Score Breakdown

| Module | Weight | What It Measures |
|--------|--------|-----------------|
| Fundamental Analysis | **35%** | Business quality and financial health |
| Technical Analysis | **30%** | Entry timing and price momentum |
| Macro Context | **20%** | Market environment and risk appetite |
| Risk-Reward Ratio | **15%** | Quality of the specific trade setup |

### Signal Thresholds

| Score | Classification | Action |
|-------|---------------|--------|
| 85–100% | High Conviction | Auto-execute eligible (if `auto_mode: true`) |
| 70–84% | Strong Signal | Sent for Telegram approval — recommended |
| 60–69% | Moderate Signal | Sent for Telegram approval — reduce size |
| < 60% | Weak / No Signal | Discarded silently |

---

## Fundamental Analysis (35%)

Data source: Screener.in (scraped via HTTP) + Yahoo Finance news headlines

### Scoring Criteria

| Metric | Strong Signal | Weak Signal |
|--------|--------------|-------------|
| ROE | > 15% | < 10% |
| ROCE | > 12% | < 8% |
| Debt-to-Equity | < 0.5 | > 1.5 |
| Free Cash Flow | Positive | Negative |
| Revenue Growth (3yr) | > 15% CAGR | < 5% |
| Profit Growth (3yr) | > 15% CAGR | < 5% |
| Promoter Holding | > 50% | < 30% |
| PE Ratio | < sector median | > 2× sector median |
| PEG Ratio | < 1.5 | > 2.5 |

### News Sentiment Overlay (±5 points)
- Yahoo Finance headlines fetched for each symbol (8 recent articles)
- 27 positive keywords: profit, growth, beat, record, acquisition, order, win, upgrade, dividend, buyback, strong, surge, rally, launch, expansion, highest, robust, momentum, outperform, contract, deal, approved, milestone, gains, rises, jumps
- 27 negative keywords: loss, fraud, scam, recall, downgrade, miss, below, penalty, investigation, decline, weak, concern, falls, drops, crisis, default, lawsuit, resign, slump, sell, warning, debt, risk, cut, probe, complaint, underperform
- Net positive headlines → +5 to fundamental score
- Net negative headlines → −5 to fundamental score

---

## Technical Analysis (30%)

Library: ta4j 0.22.2

### Indicators Used

| Indicator | Bullish Signal |
|-----------|---------------|
| Price vs SMA200 | Close > SMA200 (uptrend confirmed) |
| SMA20 vs SMA50 | SMA20 > SMA50 (golden cross) |
| RSI(14) | 40–65 range (momentum without overbought) |
| MACD | MACD line > signal line and rising |
| Volume | 20-day avg volume ≥ min_avg_daily_volume_cr AND today's volume > 20-day avg |
| ATR | Used for stop-loss distance calculation |

### Entry, Stop-Loss, and Target Calculation

```
Entry Price   = Current LTP (last traded price)
Stop-Loss     = Entry − (ATR × 2)        [or entry × 0.92, whichever is tighter]
Target        = Entry + (SL_distance × min_risk_reward_ratio)
```

With default R:R = 2.0:
- If stop is 5% below entry → target is 10% above entry
- If stop is 8% below entry → target is 16% above entry

### Golden Cross vs Death Cross
- Golden cross (SMA20 crosses above SMA50): strong buy confirmation
- Death cross (SMA20 crosses below SMA50): signal suppressed

---

## Macro Context (20%)

### India VIX

| VIX Level | Market State | Action |
|-----------|-------------|--------|
| < 13 | Very calm | Full position sizes |
| 13–18 | Normal | Normal operation |
| 18–25 | Elevated | Reduce position sizes by 50% |
| > 25 | **Circuit breaker** | **No new BUY signals** |

### Nifty 50 Trend

| Nifty vs SMA200 | Regime | Score Adjustment |
|-----------------|--------|-----------------|
| Nifty > SMA200 | Bullish | +10 to macro score |
| Nifty at SMA200 | Neutral | No adjustment |
| Nifty < SMA200 | Bearish | −10 to macro score |

### FII Flow
- Net FII buying: +5 to macro score
- Net FII selling: −5 to macro score

---

## Risk Management Rules

All 15+ rules must pass for a signal to reach Telegram.

### Position Sizing

```
Available capital  = Total portfolio − (open positions value)
Position size      = min(max_position_size_pct × portfolio, available_capital × 0.9)
Quantity           = floor(position_size / entry_price)
```

Default: max 10% of portfolio per trade.

### Hard Rules

| Rule | Default |
|------|---------|
| Max open positions | 10 |
| Max position size | 10% of portfolio |
| Max sector allocation | 25% of portfolio |
| Min cash reserve | 20% of portfolio always kept as cash |
| Min R:R ratio | 1.5 (hard reject below this) |
| VIX circuit-breaker | No buys if VIX > 25 |
| Daily loss limit | No new signals if daily P&L < −3% |
| Duplicate position | Only one open position per symbol |
| Min signal score | 60% composite |
| Min position value | ₹1,000 (filters penny lots) |

---

## Position Monitoring

### Stop-Loss Policy
- **Stop-loss is NEVER moved down.** Once set, it is a hard floor.
- Stop-loss can only be moved **up** as a trailing stop.

### Trailing Stop Activation
- When position profit ≥ 50% of target distance → stop moved to breakeven (entry price)
- When position profit ≥ 75% of target distance → stop moved to +25% of target distance
- Telegram alert sent on each trailing stop adjustment

### Partial Profit
- At 50% of target reached → alert sent suggesting partial profit booking (manual action)
- Agent does not auto-sell partial; you decide via Telegram

### Auto-Close Events (no approval needed)
1. Stop-loss hit → market sell order placed immediately
2. Target hit → market sell order placed immediately

---

## Backtesting Results

The backtesting engine validates the signal strategy against 1-year cached OHLCV data.

### Strategy Parameters (as backtested)
- **BUY rule:** Close > SMA200, SMA20 > SMA50 (golden cross), RSI 35–70
- **Stop-loss:** −8% from entry (fixed)
- **Target:** +16% from entry (fixed, 2:1 R:R)
- **Universe:** ~467 symbols from NSE cache (as of March 2026)

### Sample Results (5-symbol watchlist, March 2026)

| Symbol | Trades | Win Rate | Avg Return | Total Return |
|--------|--------|----------|------------|-------------|
| RELIANCE | 7 | 71% | +8.9% | +62.4% |
| TCS | 6 | 67% | +6.2% | +37.1% |
| INFY | 8 | 63% | +5.8% | +46.2% |
| HDFCBANK | 5 | 60% | +4.1% | +20.7% |
| LT | 7 | 57% | +3.9% | +27.5% |

> Backtesting uses the full 1-year OHLCV cache. Results will vary with universe size.
> Run `POST /api/backtest` after each `POST /api/refresh` to get updated numbers.

### Interpreting Backtest Results

| Metric | Healthy Range | Concern |
|--------|--------------|---------|
| Overall win rate | 50–70% | < 45% or > 80% (may be overfitting) |
| Avg return per trade | > 0% | Negative |
| Max drawdown | < 15% | > 25% |
| Trades per symbol | 3–15 per year | < 3 (low signals) or > 20 (overtrading) |

---

## Signal Deduplication

The `signaledSymbolsToday` set in `SignalGenerator` prevents the same stock from being
sent to Telegram more than once per trading day — even across multiple signal cycles:
- 09:15 AM run
- 10:00, 10:30, 11:00, ... intraday runs

`resetDailySignals()` clears this set at 08:45 AM each day.

---

## Going Live Checklist

Before switching `paper_trading.enabled: false`:

- [ ] Win rate ≥ 50% over at least 20 paper signals
- [ ] No execution errors (all Telegram APPROVE commands processed correctly)
- [ ] Backtest win rate aligns with paper trading win rate (within 10%)
- [ ] Angel One live account connected and tested with small order
- [ ] Stop-loss orders verified executing in paper simulation
- [ ] `auto_mode: false` confirmed (all trades require manual approval)
- [ ] Starting capital ≤ ₹50,000 for first 4 live weeks
- [ ] Short-term capital gains tax tracked (20% in India)

---

## Important Disclaimers

- This system is for **personal use only**. Offering it as a service requires SEBI registration.
- All trading involves risk. The agent can and will generate incorrect signals.
- Past backtesting performance does not guarantee future results.
- Short-term capital gains are taxed at **20%** in India — keep records for ITR filing.
- The agent cannot predict black swan events, corporate frauds, or sudden regulatory changes.
- **You are solely responsible** for all trading decisions made using this system.
- Always start with small capital and validate over months before scaling.
