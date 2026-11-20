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

1. **EMA Crossover + RSI + VWAP Side V2**: require confirmed EMA crossover and RSI first, then close above VWAP for LONG or below VWAP for SHORT. Touching VWAP is optional. Preserve V1; see §3A.
2. **Fibonacci Pivot Breakout**: confirmed R1 (or chosen resistance) breakout / S1 support breakdown, optional retest, candle-close buffer, volume, EMA, VWAP and RSI filters.
3. **EMA Pullback + VWAP Reclaim**: prevailing EMA trend, pullback toward configurable EMA/VWAP line, confirmed reclaim/rejection and RSI.
4. **High-Volume Momentum Scalping**: 5m momentum/continuation breakout with volume confirmation and optional 15m trend alignment.
5. **Custom Multi-Indicator**: typed AND/OR nested conditions. Comparators include >, <, >=, <=, crosses above/below, between, rising/falling, percent distance and level breakout. No arbitrary code evaluation.

Never repeat a signal on every bar just because a condition stays true: use false→true transitions, configurable cooldown, and re-entry rules. Evaluate at a consistent completed-candle snapshot.

Optional rule-based **confluence score** (not a calibrated probability): EMA **25**, VWAP **20**, RSI **15**, Volume **20**, Pivot **20** = 100 total. Suggested candidate threshold 65, strong candidate threshold 80; configurable. Display satisfied and failed conditions, entry reference, stop, target, R:R, signal timestamp and WAIT/NO TRADE when blocked. A score never overrides mandatory risk gates.


## 3A. V2: EMA/RSI first, VWAP side as final entry gate (clarified 2026-11-20)

**Implementation status:** The isolated `quant-engine-test` research engine now has a dedicated V2 implementation and `POST /api/backtests/vwap-ema-v2`, separate from V1. The V2 UI option imports `config/strategy-vwap-ema.example.yml` in the test repository. Valid V2 15:00 bar-open history must still be exported separately; do not report a real six-month V2 result until run against the actual upgraded dataset. The parent `quant-engine` must not start live orders from configuration alone. Preserve the legacy `/api/backtests/ema-vwap-rsi` route and historical results as V1.

**Final VWAP side:** After the configured current-session EMA crossover, RSI, clock and risk checks, a completed candle must CLOSE strictly above session VWAP for LONG, or strictly below it for SHORT. Touching or bouncing from VWAP is optional and is not separately scored. At the next candle OPEN, recheck against the last confirmed VWAP and cancel if it is on the wrong side or equal. Never use a future close. Shorts are research-only and require instrument/product validation before any future live use.

**OHLC note:** Candle OHLC cannot establish the chronological order of intrabar touches. The entry rule uses the completed close and next open; VWAP uses HLC3 and traded volume from the configured market open.

**EMA:** Fast 9/slow 20, confirmed crossover uses *previous versus current* completed EMA samples, held active on that session's side until an opposite crossover; its crossover must occur no later than the VWAP confirming bar. The crossover check precedes the VWAP check. Prevent duplicate entries on the same qualifying signal.

**RSI:** Wilder period 14, entry filter enabled. LONG >50; SHORT <50; 50 exactly ineligible. RSI exit enabled=false; proposed short exit above 55 is stored but INACTIVE; longExitBelow=null/unset because the user specified no numerical long-exit threshold. Do not exit simply because entry RSI is later lost.

**Exits:** Stop loss enabled, mode PERCENT, value **1.0% of execution price**. Take profit enabled, mode PERCENT, value **2.0% of execution price**. Exit on loss of composite entry predicates is FALSE, and optional confirmed VWAP invalidation or EMA reversal require their own toggles/reasons. Conservative stop-first on stop+target collision, realistic adverse slippage and gaps.

**Other V2 configuration:** Default quantity 1, maximum 10, max concurrent positions 1; max 5 new trades/day; daily realized loss limit ₹5,000 and daily realized profit limit ₹10,000 (block new entries); closePositionOnDailyLoss=false. Timezone Asia/Kolkata, market/VWAP open 09:15, strategy calculation start 09:20, first actual entry at 09:30, last entry 14:45 (configurable), square-off 15:00, normal NSE cash market close 15:30. Carry-over false and incompatible with INTRADAY product. Trading mode PAPER by default, order MARKET / product INTRADAY / validity DAY; level INFO logging. Parent LIVE requires separate explicit operator approval and the complete safety controls; config alone MUST NOT enable orders.

**Data coverage constraint:** The old NIFTYBEES CSV ends at 14:55 OPEN and V1 exits at 14:55. To backtest V2 square-off **15:00 OPEN**, obtain genuine 15:00 opening candle data and require that timestamp for every V2-traded session. Do not infer 15:00 OPEN from 14:55 CLOSE or make up prices. Keep V1 results reproducible.

**Config contract:** V2 reads `config/strategy-vwap-ema.example.yml` directly on each request; `V2_STRATEGY_CONFIG_PATH` can choose another file at startup. Missing, unknown and unsupported settings are rejected. The example values are configurable; unsupported timeframe, scope, mode and filter combinations are rejected. Logging is initialized by Spring at startup. The legacy V1 endpoint remains independent. In parent `quant-engine`, the example is reference-only and is NOT activated. Keep immutable per-run config snapshots and do not treat a file setting as authorization for actual orders.

**Acceptance tests:** Above/below VWAP entries work without touches after EMA crossover and RSI; touch remains allowed; equality and wrong-side prices are rejected; the next-open check, YAML values and validation, real square-off candle, risk caps and no-lookahead behavior remain covered.

## 4. Mandatory configurable intraday schedule (Asia/Kolkata)

The new V2 session defaults are **market 09:15**, **strategy start 09:20**, **entry fill start 09:30**, **last entry 14:45**, **square-off 15:00**, **normal cash-market close 15:30**, **overnight carry disabled**. These are configuration fields, never hard-coded execution constants. See §3A and the reference YAML for full details.

Market data and VWAP begin at the actual **09:15** equity session open; strategy evaluation begins **09:20**. Signals use only completed candles, fill at the next eligible bar open and are blocked before 09:30. Square-off at 15:00 is a separate safety cut-off, not the exchange close; instrument-specific calendars and broker restrictions must be validated. No overnight carry with INTRADAY products.

**Legacy V1:** The existing Java backtester still uses **09:35–14:45** entry and **14:55 open** square-off. The original NIFTYBEES CSV contains bars only through **14:55 open**. The test project now has a separate V2 engine with configurable 15:00 exits, but it must load a separately exported CSV with actual 15:00 opening candles. Use `V2_INCLUDE_1500=1 ./scripts/pull-history-from-ec2.sh` with the authorized EC2 SSH settings. Never imply V2 historical results exist just because V2 source code has been merged.


## 5. Risk management and order-safety rules

The clarified V2 defaults are **fixed quantity 1 (max 10)**, maximum one position, **1.0% percent-of-entry stop**, **2.0% percent-of-entry profit target**, maximum **5 trades/day**, realized daily loss cutoff **₹5,000**, realized daily profit cutoff **₹10,000**, and **closePositionOnDailyLoss = false**. Daily P&L caps stop additional entries; they do not, by themselves, force-close current positions when that toggle is false. Actual stop/target, allowed VWAP invalidation and mandatory square-off still apply. Make quantities, daily limits and position/exposure/risk constraints validated in external configuration. Preserve separate capital-risk sizing semantics; a 1.0% price stop is not the same as 1.0% of account equity at risk.

Model slippage, gaps, full charges, exchange lot sizes, cash/margins, contract type and real risk exposure. The parent must gate any LIVE trading with explicit user authorization, credential isolation, idempotent orders, stale-quote safeguards, restart reconciliation and broker entitlement checks. A setting alone never authorizes sending real orders. The standalone backtester always remains order-free. Overnight carry must be rejected if product type is INTRADAY.

## 6. Backtesting requirements (initial focus: EMA + VWAP + RSI)

Default historical period: **six months** (explicit reproducible example **2026-05-20 through 2026-11-19**), 5-minute bars, with preceding warmup. Support 1m/3m/6m/1y/custom ranges when the data provider supports them, and configurable 1m/3m/5m/15m/30m bars when implemented.

- **No lookahead:** indicators update only after a bar fully closes; generated signal fills next bar open with adverse slippage; historical pivot levels use the previous completed pivot period only.
- **Intrabar ambiguity:** if target and stop are both touched with unknown ordering, use a documented conservative stop-first assumption. Gap-through-stop fills at the bar open, not the stop price.
- **Legacy V1 only:** forced square-off uses the **14:55 candle open**. V2 requires configurable **15:00 square-off with actual 15:00 opening-price data**. Never silently use the old CSV for the new rule.
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
4. Existing V1 backtest stays labeled and reproducible (09:35–14:45 entries, 14:55 open square-off). V2 requires passing §3A acceptance tests: 09:20 strategy start, 09:30 first fill, 15:00 square-off using real 15:00 candle-open data, a correct-side VWAP close without a required touch, EMA crossover before RSI before final VWAP confirmation, daily INR caps and config validation.
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

**Primary priority:** preserve V1 evidence and implement the configurable VWAP-side + EMA-crossover **V2** from §3A with risk controls, validated market data and no live-order activation.
