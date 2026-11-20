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

## Web discovery

From the checkout, run the following with an authenticated Codex CLI and web search access:

```sh
./scripts/research-small-caps.sh > small-cap-report.md
```

This runs the full research prompt with its three configured settings and writes a dated research report with its structured result. It requires no symbols or snapshot. The Equity tab separately evaluates structured provider snapshots. Its input contract includes raw adjusted candles, so a final research report alone cannot be imported as a provider snapshot.
