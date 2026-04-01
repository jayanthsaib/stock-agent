# REST API Reference — Cerebro

Base URL (local): `http://localhost:8080`
Base URL (production): `http://13.203.216.12:8080`

All endpoints return JSON. No authentication required (planned for ★★★★★ tier).

---

## Status & Health

### `GET /api/status`

Agent health and configuration summary.

**Response:**
```json
{
  "status": "RUNNING",
  "timestamp": "2026-03-02T09:15:00.123",
  "mode": "PAPER_TRADING",
  "autoMode": false,
  "brokerAuthenticated": true,
  "telegramConnected": true,
  "pendingSignals": 2,
  "openPositions": 3,
  "watchlistSize": 20
}
```

| Field | Description |
|-------|-------------|
| `mode` | `PAPER_TRADING` or `LIVE` |
| `autoMode` | Whether high-conviction signals auto-execute |
| `brokerAuthenticated` | Angel One JWT valid |
| `pendingSignals` | Signals waiting for your APPROVE/REJECT |

---

### `GET /api/market/status`

Current market open/closed state (IST-aware, holiday-aware).

**Response:**
```json
{
  "date": "2026-03-02",
  "status": "OPEN",
  "isOpen": true,
  "holidays": ["2026-01-26", "2026-03-25", "2026-04-14"]
}
```

`status` values: `PRE_MARKET` · `OPEN` · `AFTER_HOURS` · `CLOSED_HOLIDAY` · `CLOSED_WEEKEND`

---

## Positions & Signals

### `GET /api/positions`

All currently open (executed) positions.

**Response:** Array of `TradeRecord` objects.
```json
[
  {
    "tradeId": "TRD-A1B2C3D4",
    "symbol": "RELIANCE",
    "status": "EXECUTED",
    "entryPrice": 2850.00,
    "targetPrice": 3136.00,
    "stopLossPrice": 2622.00,
    "quantity": 17,
    "generatedAt": "2026-03-01T09:15:22",
    "executedAt": "2026-03-01T09:17:45"
  }
]
```

---

### `GET /api/signals/pending`

Signals waiting for your APPROVE/REJECT in Telegram.

**Response:** Array of `TradeRecord` objects with `status = PENDING_APPROVAL`.

---

### `GET /api/signals/history?days=30`

Recent trade history.

**Query params:**
| Param | Default | Description |
|-------|---------|-------------|
| `days` | `30` | How many days back to fetch |

**Response:** Array of all `TradeRecord` objects generated in the last N days, all statuses.

---

## Performance & Learning

### `GET /api/performance`

Win rate, P&L stats, and learning engine analysis.

**Response:**
```json
{
  "totalClosedTrades": 47,
  "wins": 28,
  "losses": 19,
  "winRatePct": 59.57,
  "totalRealizedPnlInr": 18450.75,
  "confidenceCalibration": "Score 60-70: 12 trades, 50.0% win | Score 70-80: 18 trades, 61.1% win | Score 80+: 17 trades, 70.6% win",
  "sectorAnalysis": "Best: IT (72% win, 8 trades) | Worst: Pharma (40% win, 5 trades)",
  "rejectionAnalysis": "Top reasons: valuation too high (8), missed entry (5), sector overweight (3)"
}
```

---

## Analysis

### `GET /api/analyse/{symbol}`

On-demand full analysis for any NSE symbol. No minimum threshold — always returns the full breakdown.

**Path params:**
| Param | Example | Description |
|-------|---------|-------------|
| `symbol` | `RELIANCE` | Valid NSE equity symbol (exact, uppercase) |

**Response:**
```json
{
  "symbol": "RELIANCE",
  "ltp": 2856.40,
  "compositeScore": 72.4,
  "verdict": "BUY",
  "fundamentalScore": 78.0,
  "technicalScore": 65.0,
  "macroScore": 70.0,
  "rrScore": 70.0,
  "roe": 18.2,
  "roce": 14.5,
  "debtToEquity": 0.41,
  "peRatio": 24.1,
  "pegRatio": 1.12,
  "promoterHolding": 50.3,
  "sma200": 2650.00,
  "sma50": 2790.00,
  "sma20": 2840.00,
  "rsi": 52.3,
  "goldenCross": true,
  "deathCross": false,
  "volumeConfirmed": true,
  "suggestedEntry": 2856.40,
  "suggestedTarget": 3141.04,
  "suggestedStopLoss": 2713.58,
  "riskReward": 2.0,
  "indiaVix": 14.2,
  "niftyPrice": 22850.0,
  "marketRegime": "BULLISH",
  "newsSentimentScore": 65.0,
  "analysisSummary": "Strong fundamentals. Above all DMAs with golden cross. Macro tailwinds..."
}
```

| `verdict` | Score Range |
|-----------|-------------|
| `STRONG BUY` | 80–100% |
| `BUY` | 65–79% |
| `HOLD` | 50–64% |
| `AVOID` | < 50% |

---

### `POST /api/scan`

Runs full analysis on every watchlist stock sequentially (400ms gap between calls to
avoid Angel One rate-limits) and returns results sorted by composite score descending.

**Response:**
```json
{
  "scannedAt": "2026-03-02T11:30:00",
  "elapsedSeconds": 48,
  "totalScanned": 20,
  "aboveThreshold": 3,
  "notifyThreshold": 60.0,
  "alerts": ["RELIANCE (74.2)", "TCS (68.1)", "INFY (61.5)"],
  "results": [ ... StockAnalysisResult array ... ]
}
```

Typical runtime: ~8–15 seconds for a 20-symbol watchlist.

---

## Signal Generation Pipeline

### `POST /api/refresh`

Kicks off the full NSE universe refresh (Phase 1 + Phase 2) in a **background thread**
and returns immediately. Watch logs for progress.

Typical runtime: **5–15 minutes** (depends on universe size and Angel One rate limits).

**Response:**
```json
{
  "started": true,
  "startedAt": "2026-03-02T08:45:00",
  "message": "Full NSE universe refresh started in background. Phase 1 filters all NSE stocks; Phase 2 fetches OHLCV for up to 500 candidates. Check logs for progress, then call POST /api/signals when done."
}
```

---

### `POST /api/signals`

Runs `generateSignals()` on the cached universe and returns every signal above the
minimum confidence threshold. Does **not** send anything to Telegram.

**Response:**
```json
{
  "generatedAt": "2026-03-02T09:15:00",
  "elapsedSeconds": 12,
  "signalCount": 4,
  "signals": [
    {
      "tradeId": "TRD-A1B2C3D4",
      "symbol": "RELIANCE",
      "entryPrice": 2856.40,
      "targetPrice": 3141.04,
      "stopLossPrice": 2713.58,
      "confidenceScore": { "composite": 72.4, "fundamental": 78.0, "technical": 65.0, "macro": 70.0, "rr": 70.0 }
    }
  ]
}
```

---

### `POST /api/signals/submit`

Runs the **full pipeline** identical to the 09:15 AM scheduler:
`generateSignals()` → `riskValidator.validate()` → `approvalGateway.submitForApproval()`

Each signal that passes risk validation is sent to your Telegram for APPROVE/REJECT.

**Response:**
```json
{
  "generatedAt": "2026-03-02T09:15:00",
  "totalSignals": 5,
  "submittedToTelegram": 3,
  "riskRejected": 2,
  "submitted": ["RELIANCE (TRD-A1B2C3D4)", "TCS (TRD-B2C3D4E5)", "INFY (TRD-C3D4E5F6)"],
  "rejected": ["SBIN: sector overweight (28% > 25% cap)", "HDFCBANK: duplicate open position"]
}
```

---

## Backtesting

### `POST /api/backtest`

Replays the agent's buy strategy on 1-year cached OHLCV data.
Strategy: BUY when Close > SMA200, SMA20 > SMA50 (golden cross), RSI 35–70.
Exit: stop-loss −8% or target +16% (R:R = 2:1).

**Request body (optional):**
```json
{ "symbols": ["RELIANCE", "TCS", "INFY"] }
```
Omit or send empty `symbols` array to run on the full cached universe.

**Response:**
```json
{
  "symbolsAnalysed": 467,
  "symbolsWithTrades": 312,
  "totalTrades": 1847,
  "totalWins": 1024,
  "overallWinRate": 55.44,
  "avgReturnPerTrade": 4.21,
  "avgMaxDrawdown": 6.83,
  "results": [
    {
      "symbol": "RELIANCE",
      "totalTrades": 7,
      "wins": 5,
      "winRate": 71.43,
      "avgReturnPct": 8.91,
      "totalReturnPct": 62.34,
      "maxDrawdownPct": 8.0,
      "trades": [
        {
          "symbol": "RELIANCE",
          "entryDate": "2025-04-12",
          "entryPrice": 2540.00,
          "exitDate": "2025-05-03",
          "exitPrice": 2946.40,
          "exitReason": "TARGET",
          "returnPct": 16.0
        }
      ]
    }
  ]
}
```

`exitReason` values: `TARGET` · `STOP_LOSS` · `END_OF_DATA`

---

## Broker & Telegram

### `POST /api/broker/login`

Triggers an Angel One session refresh. Call this if `brokerAuthenticated` is `false`
in `/api/status`. The agent also auto-logs in on startup.

**Response:**
```json
{
  "success": true,
  "authenticated": true
}
```

---

### `POST /api/telegram/test`

Tests Telegram bot connectivity. Sends a test message to your configured chat.

**Response:**
```json
{ "connected": true }
```

---

## Quick Reference

| Method | Endpoint | Use Case |
|--------|----------|----------|
| `GET` | `/api/status` | Health check |
| `GET` | `/api/market/status` | Is market open today? |
| `GET` | `/api/positions` | Open positions |
| `GET` | `/api/signals/pending` | Awaiting your APPROVE/REJECT |
| `GET` | `/api/signals/history?days=30` | Trade history |
| `GET` | `/api/performance` | Win rate + P&L stats |
| `GET` | `/api/analyse/{symbol}` | On-demand stock deep-dive |
| `POST` | `/api/scan` | Scan entire watchlist |
| `POST` | `/api/refresh` | Refresh NSE universe (background) |
| `POST` | `/api/signals` | Generate signals (no Telegram) |
| `POST` | `/api/signals/submit` | Full pipeline → Telegram |
| `POST` | `/api/backtest` | Historical strategy validation |
| `POST` | `/api/broker/login` | Re-authenticate with Angel One |
| `POST` | `/api/telegram/test` | Test Telegram connectivity |

---

## Web UI Pages

| URL | Page |
|-----|------|
| `/ui/dashboard` | Agent status, positions count, recent signals |
| `/ui/positions` | Open positions with entry/SL/target |
| `/ui/signals` | Pending approvals + signal history |
| `/ui/performance` | Win rate, P&L chart, calibration |
| `/ui/analyse` | On-demand single-stock analysis |

---

## Error Responses

The API returns standard Spring Boot error responses for failures:

```json
{
  "timestamp": "2026-03-02T09:15:00.123+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Angel One token not found for symbol INVALID",
  "path": "/api/analyse/INVALID"
}
```

Common causes:
- `404` — symbol not in instrument master
- `500` — Angel One rate-limit hit; wait and retry
- `503` — broker not authenticated; call `POST /api/broker/login` first

---

## H2 Console (Paper Trading Only)

URL: `http://localhost:8080/h2-console`
JDBC URL: `jdbc:h2:mem:stockagent`
Username: `sa` · Password: (empty)
