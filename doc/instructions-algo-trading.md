# Instructions — Configurable Algorithmic Trading Platform

> **Master coding-agent prompt** for GitHub Copilot, OpenAI Codex, Claude Code, or Cursor.
>
> File: `doc/instructions-algo-trading.md`  
> Repositories: `aaruchalet/quant-engine` (default `develop`) and `aaruchalet/quant-engine-test` (default `main`).  
> Last consolidated: 2026-11-20.

## Agent mission

Act as a principal Java/Spring Boot engineer, quantitative strategy engineer, market-data engineer, and experienced trading-UI developer. **Inspect the checked-out repository first, then implement production-quality code, tests, configuration, and documentation rather than merely drafting an architecture.** Preserve working features and existing conventions. Do not create a new project or copy unrelated code. Plan coherent increments and execute them; report precisely what is actually implemented, tested, incomplete, or externally blocked.

### Repository-specific boundaries

- **`quant-engine`** is the parent application. Current baseline (verify on each checkout): Java 21, Spring Boot **4.1.1**, Gradle Kotlin DSL, PostgreSQL/Flyway, Angel One integration, manual PAPER trading, broker/account monitoring and static operator dashboard. **Do not** turn on automatic live trading or broaden its existing broker-order permissions as part of strategy work. Existing authenticated delivery-holding SELL is not a generalized algo-execution gateway.
- **`quant-engine-test`** is the isolated backtesting research service. Current baseline (verify): Java 21, Spring Boot **4.1.1**, Gradle **9.7.1**, Spring MVC and Actuator; EMA/VWAP/RSI backtesting, CSV loader, synthetic demo, Angel One historical-data download/cache. **No live order placement, no account-order gateway, no mandatory database, no UI dependency.** Keep it clone-and-run with no credentials for its synthetic demo.
- Both repositories must remain independently buildable; share calculation contracts where justified **without** introducing an unnecessary monorepo, external parent-POM requirement, circular dependency, or copy of broker secrets.
- If this document describes a feature not present in the current branch, treat it as a **requirement or roadmap item**, not as an assertion that it already works. Read current source/tests/README before deciding the delta.

## 1. Reference UI and overall experience

Use the supplied NIFTY 50 / NSE 5-minute chart screenshot as the intended **visual reference** when it is available to the coding agent. If the image is not checked in, use these requirements; do not pretend you can inspect it.

Build a responsive, chart-first intraday UI (in the **parent project only**, unless specifically asked to add UI to the test service). Prefer the existing frontend stack; add TradingView Lightweight Charts or another appropriately licensed chart library only if needed. Default light theme; optional dark theme.

Tabs: **Chart / Overview / Option Chain / Stock Composition**. Chart toolbar: instrument and exchange selector; 1m/3m/5m/10m/15m/30m/1h/1D; chart type; indicator selector; drawing tools; crosshair; zoom/pan; reset; save layout; export screenshot; fullscreen; theme; Instant Orders; Scalper Mode; settings. Every visible control must work or be clearly disabled with a reason.

Main chart: OHLCV candles (green/teal up, red down), volume histogram, EMA overlays, session VWAP, historical-period Fibonacci pivot segments, crosshair and axes, current price, relevant support/resistance labels, entry/exit arrows, stops/targets, connection freshness. RSI belongs in a separately resizable lower panel, with 30/50/70 reference lines. Keep live updates incremental; preserve scroll/zoom unless auto-follow is enabled. Collapsible dashboard panels may show current indicators, strategy confluence, position/orders, and backtest results. Do not introduce UI clutter.

## 2. Indicator configuration and calculations

All calculation settings, styling, visibility, and **participation in strategy signals** must be distinct and persisted. Hiding a plot must not silently disable the filter.

| Indicator | Default | Implementation |
| --- | --- | --- |
| EMA cross | Fast **9**, slow **20**, Close; red and teal/green | EMA alpha = 2/(period+1); well-defined seed/warmup; cross above/below only on real crossings, slope and minimum separation optional |
| Session VWAP | **HLC3**, NSE session; black line | Cumulative sum(HLC3×volume)/sum(volume), reset at session start; allow source, session, deviation bands |
| Fibonacci pivots | **Auto**, **10** historical pivot periods | On 5m use completed prior day by default; P=(H+L+C)/3; R/S at 0.382, 0.618 and 1.0 × prior range; never read the current incomplete reference period |
| Volume | Histogram + **MA 9** | Relative volume and configurable breakout/spike thresholds; distinguish ordinary volume average from comparable-time-of-day volume |
| RSI | **14**, Close, Wilder smoothing; thresholds **30/50/70** | Deterministic initial seed; 70 is not an automatic short and 30 is not an automatic buy |

EMA slope, VWAP reclaim/rejection, Fibonacci breakout/retest, relative-volume threshold, RSI min/max and colors are configurable. Provide alerts on meaningful crossings and threshold events, with debouncing/cooldowns.

**Data quality:** NIFTY 50 *spot index* is not a directly tradable instrument and does not provide equivalent market-traded index volume for VWAP. For volume/VWAP and meaningful executable backtests, use actual, licensed OHLCV for a tradable ETF (e.g., NIFTYBEES, when volume is valid) or actual futures contracts with contract-specific rollover/margin modeling. Never fabricate index volume, contract tokens, lot sizes, expiry, quotes, fills, charges, or signal confidence.

## 3. Strategy engine and configurable presets

Implement validated, immutable, versioned strategy configurations and the same deterministic indicator/decision contracts for historical simulation, paper signaling, and any *separately authorized* future execution. Support per-instrument/timeframe overrides, enable/disable each filter, thresholds, a no-code AND/OR rule builder, and saved/duplicate/import/export presets.

Required presets:

1. **EMA + VWAP Trend Following**: bullish EMA9 > EMA20, close > session VWAP, RSI in **55–75**; optional relative-volume >= **1.2**, pivot P and bullish confirmation. Bearish mirror: EMA9 < EMA20, close < VWAP, RSI **25–45**, with short eligibility governed by instrument/regulations; short trades **off by default**.
2. **Fibonacci Pivot Breakout**: confirmed R1 (or chosen resistance) breakout / S1 support breakdown, optional retest, candle-close buffer, volume, EMA, VWAP and RSI filters.
3. **EMA Pullback + VWAP Reclaim**: prevailing EMA trend, pullback toward configurable EMA/VWAP line, confirmed reclaim/rejection and RSI.
4. **High-Volume Momentum Scalping**: 5m momentum/continuation breakout with volume confirmation and optional 15m trend alignment.
5. **Custom Multi-Indicator**: typed AND/OR nested conditions. Comparators include >, <, >=, <=, crosses above/below, between, rising/falling, percent distance and level breakout. No arbitrary code evaluation.

Never repeat a signal on every bar just because a condition stays true: use false→true transitions, configurable cooldown, and re-entry rules. Evaluate at a consistent completed-candle snapshot.

Optional rule-based **confluence score** (not a calibrated probability): EMA **25**, VWAP **20**, RSI **15**, Volume **20**, Pivot **20** = 100 total. Suggested candidate threshold 65, strong candidate threshold 80; configurable. Display satisfied and failed conditions, entry reference, stop, target, R:R, signal timestamp and WAIT/NO TRADE when blocked. A score never overrides mandatory risk gates.

## 4. Mandatory intraday schedule (Asia/Kolkata)

All times must be server-side validated, configurable, persisted, and consistently used in backtests/paper/live permission checks:

| Rule | Default IST |
| --- | --- |
| Collect candle/volume data | From **09:15** exchange open |
| Earliest eligible *entry fill* | **09:35** (after 09:30) |
| Last eligible new entry | **14:45** |
| Mandatory forced intraday exit | At **14:55** candle **open**, where supported |
| No open strategy position / activity | Before **15:00** |
| Carry overnight | **Disabled** |

EMA and RSI may need prior-session warmup; VWAP accumulates from **09:15**, not 09:35. A 09:30–09:35 completed candle may produce the 09:35 entry decision; fill at the **next available candle open**, never at the already-observed close. Document precisely whether configuration bounds *signal time* or *fill time* and ensure the 14:45 cutoff is enforced on actual entry fills. Do not use the 14:55 candle's future high/low/close to decide an exit at 14:55 open.

Respect NSE holidays, special sessions, time zone and stale/missing data; never assume weekends are trading days.

## 5. Risk management and order-safety rules

Centralize risk enforcement. Default **risk 1% of current eligible capital per trade**, **target 2R** (configurable 1:2/1:3/1:4), stop-loss required, **max five entries/day**, **3% daily realized-loss budget**, **one simultaneous position**, configurable cooldown (e.g., three 5m candles). Support percentage/points/EMA/VWAP/swing/pivot/ATR stops and fixed/multiple/trailing targets. Include cash constraints and round quantity to actual tradable lot size. Model slippage, spreads, brokerage, taxes, product type, contract selection and fees; expose assumptions in reports.

Live order placement must be **separate, disabled by default, explicitly authorized, authenticated and idempotent**; broker acceptance != fill. Square-off must confirm actual fills and alert/escalate pending/rejected orders. Include max exposure, freshness checks, daily kill switch, partial fills and restart reconciliation. No martingale/revenge sizing. **Backtest and `quant-engine-test` must never place real broker orders.**

## 6. Backtesting requirements (initial focus: EMA + VWAP + RSI)

Default historical period: **six months** (explicit reproducible example **2026-05-20 through 2026-11-19**), 5-minute bars, with preceding warmup. Support 1m/3m/6m/1y/custom ranges when the data provider supports them, and configurable 1m/3m/5m/15m/30m bars when implemented.

- **No lookahead:** indicators update only after a bar fully closes; generated signal fills next bar open with adverse slippage; historical pivot levels use the previous completed pivot period only.
- **Intrabar ambiguity:** if target and stop are both touched with unknown ordering, use a documented conservative stop-first assumption. Gap-through-stop fills at the bar open, not the stop price.
- Forced square-off uses the 14:55 bar **open**, and requires that bar to be present.
- Use *exactly the same* indicator formulas, rule predicates, and trading hours for research and paper signals.
- Validate timestamps as IST **candle opening times** and 5-minute alignment; sort/dedupe, reject invalid OHLCV, flag missing candles/sessions, prevent malformed or mixed-symbol datasets.
- Reject missing/nonrepresentative volume for VWAP strategies rather than silently substituting spot volume.
- Report trades, entry/exit reason, quantity, gross/net P&L, charges, win rate, profit factor, expectancy, max drawdown, realized equity curve, session counts, skipped dates and explicit fill assumptions. Distinguish closed-equity drawdown from intrabar mark-to-market drawdown.
- Add deterministic formula tests, trend/short/risk tests, no-trade and candle-close tests, missing-session and duplicated-bar tests, historical cost models, forced exits and benchmark/data integrity tests. Include out-of-sample comparisons to reduce overfitting.

**Backtest profitability must never be presented as a prediction or guarantee.** NIFTY option/futures strategy results require contract-level historical prices, expiry mapping, rollover, lot sizes, margins and realistic execution, not just NIFTY spot signals.

## 7. Historical market-data retrieval: Angel One SmartAPI

For `quant-engine-test`, implement/maintain `HistoricalMarketDataService` and an Angel One history adapter, with all credentials passed securely by environment/secret storage, **never committed, logged, echoed in errors, or returned through the API**.

Desired flow:

`authenticated broker history API → bounded date batches → retry/backoff for transient failures → strict OHLCV/timestamp validation → atomic local cache/CSV → backtest engine → JSON report`.

- Angel One endpoint: `POST /rest/secure/angelbroking/historical/v1/getCandleData` via the official broker host and authenticated headers. Requested timeframe `FIVE_MINUTE`; actual exchange and **instrument token** must come from trusted instrument master/metadata, **not guessed**.
- Retrieve six months in bounded batches (e.g., **14 days per request**), limit rate, respect provider rate limits/entitlements, handle expired tokens and API errors without fabricating data.
- CSV format: `timestamp,open,high,low,close,volume`, ISO local IST opening timestamp such as `2026-05-20T09:15:00`; each candle is exactly 5 minutes.
- Cache by exchange/symbol token/timeframe/date range and configuration, with a default **24h TTL**; keep cache ignored by Git. Validate complete candles and invalid sessions before reuse, with explicit refresh/re-download option; do not silently backtest a partial cache.
- Provide **one-request** fetch-and-run endpoint and CSV export endpoint, while preserving existing offline loader and credential-free demo.
- Never allow arbitrary client-supplied server file paths, unsafe URLs/SSRF or third-party credential exposure. Avoid distributing licensed datasets without verifying redistribution rights.
- Current intended routes (verify code): `POST /api/backtests/ema-vwap-rsi/angel-one`, `GET /api/history/angel-one.csv`, existing `POST /api/backtests/ema-vwap-rsi`, and `GET /api/backtests/demo`.
- Keep tests fully offline using stub/mock HTTP responses; no paid API or real trading is needed for CI.
- Document required environment settings: `ANGEL_ONE_API_KEY`, `ANGEL_ONE_JWT_TOKEN` or secured JWT file, `ANGEL_ONE_SYMBOL_TOKEN`, `ANGEL_ONE_EXCHANGE`, instrument symbol, required client IP/MAC broker headers, and optional `HISTORY_CSV_PATH`, `HISTORY_SYMBOL`; provide safe examples with placeholders only.

**Security warning:** A local unauthenticated convenience API must never be exposed to a public interface; bind to `127.0.0.1` by default. Enforce proper access control before deployment or accepting remote users.

## 8. Architecture and contracts

Follow existing packages and patterns rather than forcibly creating microservices. Useful logical modules: `MarketDataAdapter`, `HistoricalMarketDataService`, `CandleValidator`, `CandleAggregator`, `EMA`, `SessionVWAP`, `FibonacciPivot`, `RSI`, `VolumeStatistics`, `StrategyConfiguration`, `StrategyEvaluator`, `BacktestEngine`, `RiskEngine`, `ReportService`, `BrokerAdapter` (parent only), `AlertService`, and `TradingAuditService` (where needed).

Use typed DTOs and validation, explicit clock and exchange calendar boundaries, immutable strategy snapshots, deterministic time series, separation of historical market data from signal and execution. Retain the current Spring Boot **4.1.1**, Java 21 and Gradle Kotlin DSL unless the checked-out repo states otherwise. Never require PostgreSQL or broker credentials for `quant-engine-test`'s synthetic smoke endpoint. Reuse the parent's dependencies only insofar as the test project needs them, not the entire parent dependency graph.

Chart/API design in parent (when requested): instrument/candles/indicators/strategies/signals/backtests/orders/positions/alerts, versioned WebSocket/SSE live updates, security at every execution boundary. Implement optional Option Chain and Stock Composition only with licensed real source data (expiry, strikes, OI, IV and Greeks when available). Never claim real-time data without freshness validation.

## 9. Acceptance tests / definition of done

1. Existing repo still builds; no unrelated regressions or destroyed configurations.
2. Standalone test service runs immediately after clone with Java 21 and its Gradle wrapper; synthetic demo needs no broker credentials or database.
3. Five indicator formulas and warmup behavior are documented, configurable and tested before enabling their respective strategy.
4. EMA/VWAP/RSI backtest consumes genuine, validated 5m OHLCV or explicitly labeled synthetic data, applies **09:35–14:45 entry** and **14:55 exit** with no overnight positions.
5. Six-month broker download uses real instrument metadata, token, valid auth, bounded requests, error handling, cache and complete-session validation.
6. CSV export and fetch-and-backtest endpoints work as documented; failed/partial downloads are visible and never silently treated as success.
7. Outputs are reproducible and include costs, fill model, trade reasons and skipped sessions.
8. No backtest, test, alert or demo path can send a live order.
9. For parent UI work, enabled chart overlays, legend, settings, saved layout and controls are functional, and market-data state is clearly marked live/delayed/simulated.
10. Run local unit/integration tests and GitHub CI; **report actual evidence**, not assertions. State limitations and missing broker entitlements or real data separately.

## 10. Agent execution instructions

1. Read `README.md`, Gradle config, current source, tests, sample settings and CI; summarize relevant architecture.
2. Identify what is already implemented versus missing, and use smallest safe change set.
3. Implement source, tests, settings and docs in coherent steps. Maintain a functioning build after each.
4. Never place secrets, session JWT, instrument token assumptions or licensed candle dumps into Git.
5. Verify with `./gradlew clean test build` (or the repository's canonical CI command), then inspect the exact commit's GitHub Actions result when available.
6. Provide branch, commit SHA, changed-file summary, test outcomes, run commands, example `curl`, and explicit external prerequisites.
7. Never activate real money trading, alter production execution rules, or claim verified six-month historical results without valid source data and a completed backtest.

**Primary priority:** robust historical data acquisition, mathematically correct indicators, honest reproducible backtesting, strict 09:35–14:55 IST intraday guards, and disciplined configuration—before adding more automated order features.
