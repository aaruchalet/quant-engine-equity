# Equity research feed

Place your provider or analyst snapshot at `data/equity/snapshot.json` (override with `TRADING_EQUITY_SNAPSHOT_PATH`) and replace it atomically after each update. Alternatively import a JSON file on the Equity tab. All decisions are as of the supplied snapshot time. The service calculates indicators and evaluates evidence supplied by the operator. It does not fetch or authenticate the cited publications.

POST a snapshot and config to `/api/equity/screen`, or just the config to `/api/equity/screen/latest`. Example config:

```json
{"minPrice":10,"maxPrice":100,"minSessions":20,"maxSessions":45,"capital":100000}
```

A minimal **synthetic, incomplete** import that exercises the missing-data path:

```json
{"asOf":"2026-01-05T16:00:00+05:30","regime":"UNKNOWN","stocks":[{"exchange":"NSE","symbol":"EXAMPLE","isin":"TEST-SYNTHETIC","company":"Example only"}]}
```

Use one listing per ISIN. Regime values are BULLISH, NEUTRAL, BEARISH, HIGH_VOLATILITY or UNKNOWN. For each listing supply:

| Property | Contents |
| --- | --- |
| price, tradedValue20Cr | Verified INR price and average actual daily traded value in crore over 20 exchange sessions |
| gates | Booleans for amfiSmallCap, ordinaryMainboardEquity, deliveryTradable, surveillanceClear, reportingClear, manipulationClear, financialMinimums and improvingTrend. Use null for unknown. Apply the industry-specific financial and governance tests in stock.md. |
| evidence | Map keyed by price, amfi, surveillance, financials, adjustedHistory, fees and depth. Each entry carries an HTTPS source, offset timestamp asOf and nonempty rationale. Verify current surveillance and classification for the dated snapshot. |
| evidence (scoring) | Also supply fundamentals (0–20), technicals (0–25), liquidity (0–15), valuation (0–10), catalysts (0–15), governance (0–10), market (0–5). Each entry additionally carries integer points. Document the source figures, peer comparisons, weekly structure, regime, catalyst dates and reasoning behind each assessment. |
| adjustedDaily | At least 250 adjusted daily OHLCV bars in ascending session order. Each has openTime (local IST, e.g. 2026-01-05T09:15:00), open, high, low, close and volume. Supply completed sessions only. |
| plan | setup (NOW, BREAKOUT or PULLBACK), boolean confirmed, entry, stop, target1, target2, executableUnits, sessions and invalidation. Provide entryCost, stopExitCost and targetExitCost as all-in INR **per share** including charges and slippage for the modeled size. |

The response includes at most ten qualifying setups and all evaluated decisions with evidence and missing or failed checks. Position size is capped by 1% portfolio risk including costs, 10% allocation including entry costs and executable depth. The net reward/risk gate uses the first target. Breakout setups need at least 1.5× prior 20-session average volume. Historical snapshots describe the stated date. They do not qualify a trade for today.
