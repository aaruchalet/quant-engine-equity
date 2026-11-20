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

1. **VWAP Third Bounce + EMA Crossover V2**: two prior qualifying VWAP bounces, signal on third; confirmed EMA9/20 same-session crossover; LONG above VWAP with RSI14 **>50**, SHORT below VWAP with RSI14 **<50**; independent SL/TP and disabled RSI exits. Keep original legacy V1 for reproducibility; see §3A.
2. **Fibonacci Pivot Breakout**: confirmed R1 (or chosen resistance) breakout / S1 support breakdown, optional retest, candle-close buffer, volume, EMA, VWAP and RSI filters.
3. **EMA Pullback + VWAP Reclaim**: prevailing EMA trend, pullback toward configurable EMA/VWAP line, confirmed reclaim/rejection and RSI.
4. **High-Volume Momentum Scalping**: 5m momentum/continuation breakout with volume confirmation and optional 15m trend alignment.
5. **Custom Multi-Indicator**: typed AND/OR nested conditions. Comparators include >, <, >=, <=, crosses above/below, between, rising/falling, percent distance and level breakout. No arbitrary code evaluation.

Never repeat a signal on every bar just because a condition stays true: use false→true transitions, configurable cooldown, and re-entry rules. Evaluate at a consistent completed-candle snapshot.

Optional rule-based **confluence score** (not a calibrated probability): EMA **25**, VWAP **20**, RSI **15**, Volume **20**, Pivot **20** = 100 total. Suggested candidate threshold 65, strong candidate threshold 80; configurable. Display satisfied and failed conditions, entry reference, stop, target, R:R, signal timestamp and WAIT/NO TRADE when blocked. A score never overrides mandatory risk gates.


## 3A. New configurable VWAP third-bounce / EMA-crossover strategy (requested 2026-11-20)

**Specification status:** This is the NEXT strategy contract, not an implemented or activated feature. Preserve previous simulation as **LEGACY_EMA_VWAP_RSI_V1** with its original parameters and results. Call the new rule set **VWAP_BOUNCE_EMA_V2**. Never relabel the previous six-month backtest as V2.

**Mandatory V2 entry conditions, evaluated together (AND) on completed candles:**

- **VWAP side:** LONG only when the confirmed signal close is **strictly above** session VWAP; SHORT only when **strictly below**. Before an actual next-bar entry fill, recheck the open/fill price against the latest completed VWAP value, cancelling the entry if the correct side is lost. Never create a long entry below/equal VWAP or a short entry above/equal VWAP. Intrabar movement of an already-open position cannot be guaranteed to remain on one side; model gaps and exits honestly.
- **Third bounce:** Before a LONG, detect **two** distinct prior same-session VWAP support bounces and allow a signal only on the **third** confirmed support bounce. A long bounce candle has low at/below VWAP (with configured tolerance) and confirmed close above VWAP. A SHORT requires two prior same-session resistance rejections and its third rejection (high at/above VWAP, confirmed close below VWAP). Default tolerance **0 bps** means actual touch; tolerance is configurable. Reset counts on a new session, wrong-side confirmed close, or signal consumption. Do not use future candles, a guessed next bounce, or repeated counts on the same bar.
- **EMA crossover:** Default fast **9** and slow **20**. LONG requires a **confirmed bullish crossover** in the current session at or before the third bounce, with fast EMA still above slow; SHORT requires a confirmed bearish crossover with fast still below slow. The historical existence of EMA9 > EMA20 alone does not count as a fresh crossover. Track crossover timestamps, opposite-cross resets and configurable scope/lookback. Use previous/current completed EMA samples to detect crossings.
- **RSI entry:** Filter **enabled**, Wilder period **14**, LONG RSI **>50**, SHORT RSI **<50**, exactly 50 is ineligible. RSI exit **disabled** by default. Store short-exit-above **55** for future opt-in. **Long-exit-below was not specified numerically**; leave that setting null/unset, and reject enabling a long RSI exit until configured. Do not exit merely because the original RSI entry filter becomes false while RSI exit is disabled.
- **Exits:** Independent stop-loss enabled at **1.0% of actual entry price**, take-profit enabled at **2.0% of entry price**; these are **percent-of-price** distances, not an unspecified R multiple. An optional confirmed VWAP-side invalidation exit may be enabled and must have its own distinct reason and next-open execution; do not confuse it with an EMA reversal. Generic exit on loss of ANY composite entry predicate is **off**. EMA-reversal exit is a separate opt-in switch. Stop-first when stop and target both hit in the same OHLC candle; handle gaps conservatively.
- **Short eligibility:** In research, short signals may be evaluated, but a LIVE short must be rejected unless the instrument, account, product and broker explicitly permit it.

**Externally configured parameters (typed, validated, no hard-coded trade rules):**

| Setting | Requested target |
| --- | --- |
| Trading mode | **PAPER** default; **LIVE** is an allowed configuration value only in the separately authorized parent application. Test/backtest must always remain read-only. |
| Order type / product / validity | **MARKET / INTRADAY / DAY** |
| Position quantity | Default **1** unit; max **10** units; max simultaneous positions **1**; validate eligible lot size, capital and exposure. |
| Stop-loss | Enabled = **true**, mode = **PERCENT**, value = **1.0** |
| Take-profit | Enabled = **true**, mode = **PERCENT**, value = **2.0** |
| Max trades | **5 per day** |
| Max daily realized loss | **₹5,000** (block new entries at or beyond threshold) |
| Max daily realized profit | **₹10,000** (block new entries at or beyond threshold) |
| Close position on daily loss | **false**. The daily loss gate blocks new entries, not an automatic immediate close; independent stop/target/VWAP rules and timed square-off remain active. |
| Market/session | Timezone **Asia/Kolkata**; NSE cash session begins **09:15**; strategy evaluation starts **09:20**; earliest new-entry FILL **09:30**; last entry **14:45** (retained from earlier instructions); forced square-off **15:00**; normal equity market close **15:30**. All configurable by instrument/session. |
| Carry-over | Configurable, **false** by default; reject overnight carry with INTRADAY product. Enabling carry requires compatible product, verified broker eligibility and explicit authorization. |
| Logging | Level **INFO** (redact tokens, credentials and private account identifiers). |

**Scheduling and compatibility details:** The exchange's normal equity session is approximately 09:15–15:30 IST, subject to instrument/segment, exchange changes and special sessions. **09:20 strategy start is NOT VWAP reset**: VWAP must still include genuine 09:15 market data. Entries must use confirmed completed 5m candles then fill at a later 5m open; earliest fill 09:30. A **15:00 square-off is intentionally earlier than the 15:30 cash-market close** and does not imply trading until close. The currently implemented V1 backtester and downloaded six-month CSV require complete 09:15–14:55 bar-open timestamps and force exits at the **14:55 open**. Implement new 15:00-bar-open historical coverage, configurable clocks, exit execution and safety tests BEFORE claiming the V2 15:00 setting works. Do not fabricate 15:00 prices from the 14:55 candle's close or future data.

**Reference configuration:** See **config/strategy-vwap-ema.example.yml** in each repository; it is **documentation/reference only** until an actual loader is implemented. Config must be immutable per run, validated on startup and embedded/versioned in the backtest report. Define typed schema, overrides, environment binding, UI display and tests. Never silently activate LIVE by changing a file. Parent LIVE requires explicit separate approval, authenticated operator permission, risk gate, broker checks, kill switch and order reconciliation. No test/demo/backtest call may place broker orders. If LIVE is selected for quant-engine-test, reject it explicitly.

**Acceptance tests:** Long/short third-bounce counts; no early first/second bounce trade; crossover timing; strict VWAP side at both close and next-open fill; RSI strict >50/<50; RSI exit disabled; percentage stop/target; 1–10 quantity and lot validation; five-trade cap; ₹5,000/₹10,000 realized daily gates; false close-on-loss; chronological configuration and 15:00 price coverage; carry/INTRADAY conflict; PAPER/LIVE isolation; safe INFO logs; repeatable no-lookahead reports. Keep V1 regression tests intact.


## 4. Mandatory configurable intraday schedule (Asia/Kolkata)

The new V2 session defaults are **market 09:15**, **strategy start 09:20**, **entry fill start 09:30**, **last entry 14:45**, **square-off 15:00**, **normal cash-market close 15:30**, **overnight carry disabled**. These are configuration fields, never hard-coded execution constants. See §3A and the reference YAML for full details.

Market data and VWAP begin at the actual **09:15** equity session open; strategy evaluation begins **09:20**. Signals use only completed candles, fill at the next eligible bar open and are blocked before 09:30. Square-off at 15:00 is a separate safety cut-off, not the exchange close; instrument-specific calendars and broker restrictions must be validated. No overnight carry with INTRADAY products.

**Legacy V1:** The existing Java backtester still uses **09:35–14:45** entry and **14:55 open** square-off. The exported NIFTYBEES CSV contains bars only through **14:55 open**. V2 15:00 execution requires new real data and a revised engine; until implemented, do not claim V2 settings are active or claim its results.


## 5. Risk management and order-safety rules

The requested V2 defaults are **fixed quantity 1 (max 10)**, maximum one position, **1.0% percent-of-entry stop**, **2.0% percent-of-entry profit target**, maximum **5 trades/day**, realized daily loss cutoff **₹5,000**, realized daily profit cutoff **₹10,000**, and **closePositionOnDailyLoss = false**. Daily P&L caps stop additional entries; they do not, by themselves, force-close current positions when that toggle is false. Actual stop/target, allowed VWAP invalidation and mandatory square-off still apply. Make quantities, daily limits and position/exposure/risk constraints validated in external configuration. Preserve separate capital-risk sizing semantics; a 1.0% price stop is not the same as 1.0% of account equity at risk.

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
4. Existing V1 backtest stays labeled and reproducible (09:35–14:45 entries, 14:55 open square-off). V2 is implemented only after passing §3A acceptance tests: 09:20 strategy start, 09:30 first fill, 15:00 square-off with real bar-open data, two prior VWAP bounces before third-trigger, EMA crossover, RSI >50/<50, daily INR caps and config validation.
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

**Primary priority:** preserve V1 evidence and implement the configurable VWAP-third-bounce + EMA-crossover **V2** from §3A with risk controls, validated market data and no live-order activation.
