# Equity research feed

Place your provider or analyst snapshot at `data/equity/snapshot.json` (override with `TRADING_EQUITY_SNAPSHOT_PATH`) and replace it atomically after each update. Alternatively import a JSON file on the Equity tab. The snapshot dates the evidence. The server checks trigger expiry and live quote freshness at request time. The service calculates indicators and evaluates evidence supplied by the operator. It does not fetch or authenticate the cited publications.

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
| scoring evidence | Supply individual entries for revenueGrowth, recurringProfit, quarterProfitGrowth, cashFlowOrCapital, outperformsSmallcap, peerPE, peerPB, scheduledEvent, recentDevelopment, quantifiedDevelopment, zeroPledge, cleanAudit and marketAndSectorTrend. Each has a boolean passed, source URL, asOf and a rationale documenting values and dates for the matching test in stock.md. The service computes the seven remaining checks from candles and turnover. Missing tests earn zero. Legacy category totals are ignored. |
| adjustedDaily | At least 250 adjusted daily OHLCV bars in ascending session order. Each has openTime (local IST, e.g. 2026-01-05T09:15:00), open, high, low, close and volume. Supply completed sessions only. |
| plan levels | Supply setup (NOW, BREAKOUT or PULLBACK), confirmed, entryLow, entry (highest permitted entry), stop, target1, target2, executableUnits, sessions and invalidation. Use more history than the planned holding period where required. Count entry as session one. A stop, target or invalidation can end the trade early. |
| plan costs | Supply entryCost, stopExitCost, targetExitCost and target2ExitCost as per-share variable INR charges including slippage. Supply entryFixed, stopFixed, target1Fixed and target2Fixed in total rupees per order. Use zero for verified zero fixed fees. Use conservative estimates valid across the modeled quantity range if brokerage has pricing tiers. Supply positive referenceUnits for cost estimates without capital. |
| plan trigger | Provide triggerCondition with the price, timeframe and required volume or retest, and triggerExpiresAt with time zone. The feed must cancel invalidated setups. A confirmed live entry additionally requires triggerObservedAt, liveQuote and marketOpen. The latest price must be inside the entry zone and at most 60 seconds old. A confirmed breakout must pass the completed-session volume check. |

Each sourced passing test earns five points. Missing critical evidence prevents qualification. The first target must return at least twice the modeled loss after costs. The response includes both unrounded ratios, a score breakdown, pending conditions and expiry. The Equity tab groups confirmed and pending opportunities separately. Older snapshots can still be inspected but require updated plan and rubric evidence to qualify.

Capital and availableCash are optional runtime inputs. Without capital, position size is null. Without availableCash, a computed size is provisional. The default risk budget is 0.5% of capital including estimated fees at the stop. Allocation including entry fees is capped at 10%. Depth, cash and whole-share limits also apply.

## On-demand company Research tab

After using **Discover**, click any candidate row to open **Research** inside Equity. The verified ISIN and exchange are retained. The server requests official dated exchange archives through `GET /api/equity/research?isin=<ISIN>&exchange=NSE` (or BSE). A direct ticker lookup also offers **Research** once the ISIN is verified.

The response includes `retrievedAt`, `asOf`, `observedSessions`, `requiredSessions`, `dailyPrices` (each with the original source URL), `averageTradedValueCr` when all 20 matching sessions exist, and indicative `ema9Indicative`, `ema20Indicative`, `rsi14Indicative`. Explicit `missingChecks` and `limitations` are also provided.

A completed **20-session archive history is preliminary research only**: it does not verify 250 corporate-action-adjusted candles, full financial evidence, current exchange surveillance restrictions, or BUY eligibility. Incomplete coverage, stale prices and missing securities are reported rather than silently filled. No broker integration or order path is invoked. This is separate from the existing full-rubric `/api/equity/screen` and dated snapshot feed.

## Automatic discovery

Start the Spring Boot application, open the Equity tab and press **Scan equities**. The server loads the AMFI classification workbook and official NSE and BSE end-of-day files. It joins by ISIN, applies the configured price band, and calculates average traded value from 20 exchange sessions. A dual listing appears once. The scan selects the newer closing date and then the listing with higher verified turnover. The JSON report includes closing dates, source links, coverage counts and any gaps.

The application needs outbound HTTPS access to AMFI and the two exchanges. It uses no account or API key. Public archives cover basic discovery. The full entry, risk and score calculation uses the verified research evidence described above. See `POST /api/equity/discover` for the same discovery output as JSON. This endpoint and the Equity tab perform discovery without the optional Codex research script.


## Individual equity lookup and dashboard screen (20 November 2026)

The three-tab dashboard now places all equity discovery and historical cash-share
backtesting under **Equity**. NIFTY/SENSEX show PAPER/LIVE positions and strategy
diagnostics only.

- **Search specific ticker:** `GET /api/equity/lookup?q=SBIN` uses the latest
  available downloadable NSE/BSE end-of-day archives to find exchange ticker
  matches. `GET /api/equity/eod/SBIN` returns the verified closing price, ISIN,
  exchange, trade value, as-of exchange session, and source link. This is not
  an intraday or live quote. Most archive formats do not supply company-name
  aliases, so search is primarily ticker-based.
- **Scan all small caps:** `POST /api/equity/discover` uses the existing AMFI
  classification workbook and official exchange end-of-day reports, mapping by
  ISIN. It records the universe size, mapped securities, filtered prices, and
  20-session liquidity qualification where complete archives exist. Fetch
  time, source URLs, each market's data-as-of date, and partial-coverage warnings
  are part of the result. The dashboard does not falsely claim BUY eligibility.
- **Full screening rubric:** `POST /api/equity/screen` still requires 250
  adjusted daily bars, sourced financial/governance tests, valid price and
  surveillance checks, realistic costs and net R:R. Public EOD archives alone
  cannot meet those thresholds.
- **Historical backtest:** the Equity UI can upload a 5-minute CSV to
  `POST /api/equity/backtest` and display V1-style cash-equity simulation metrics.
  This endpoint has no broker-order path.

**Licensing:** The optional NSE website API adapter remains disabled by default
(`TRADING_EQUITY_NSE_WEBSITE_ACCESS_AUTHORIZED=false`) because website terms
restrict automated collection. The official downloadable report workflow is
separate and must still be used consistently with the relevant publisher's terms.
No CAPTCHA/session restriction bypass, AI service, or third-party AI screening
is required to use the dashboard.

**Check priority:** critical liquidity/depth/exits and
governance/surveillance; high earnings/cash/debt, trend/volume/support, valuation,
near-term catalysts, stop and net reward:risk; medium promoter and FII/DII trends.
Missing critical evidence is `UNVERIFIED` or `INSUFFICIENT DATA`.
