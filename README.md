# quant-engine-test

**EC2-to-Mac NIFTYBEES export:** For a read-only, registered-egress-checked workflow using the existing Quant Engine container and no copied broker credentials, see [EC2 historical export](doc/ec2-historical-export.md). It requires authorized SSH access; GitHub alone cannot connect to a private EC2 host or generate the actual licensed candles.

**Independent EMA(9/20) + Session VWAP(HLC3) + RSI(14) backtesting service.**

Tech stack is intentionally aligned with [`aaruchalet/quant-engine`](https://github.com/aaruchalet/quant-engine) `develop`: Java 21, Gradle Kotlin DSL 9.7.1 wrapper, Spring Boot 4.1.1, and Spring Dependency Management 1.1.7. This standalone service includes only the parent's Spring MVC, Actuator, and corresponding test starters. It does **not** require Postgres, Flyway, Angel One credentials, an exchange connection, or a running parent project.

## Clone, test and run

Prerequisites: JDK 21 and internet access for Gradle wrapper/dependency downloads on the **first** run.

```bash
git clone https://github.com/aaruchalet/quant-engine-test.git
cd quant-engine-test
./gradlew clean test bootJar
./gradlew bootRun
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Default server: `http://127.0.0.1:8081`.

**Without downloading any market dataset**, verify it starts and the demo engine works:

```bash
curl http://127.0.0.1:8081/actuator/health
curl http://127.0.0.1:8081/api/backtests/demo
```

The demo generates six months of **synthetic deterministic candles** to test the runtime, and responds with `SYNTHETIC DEMO ONLY`. **It is not historical NIFTY/ETF performance and must not be used as a trading result.**

## Run a real six-month backtest

A local licensed OHLCV dataset is required; it is not included in source control.

```bash
export HISTORY_CSV_PATH=/absolute/path/to/5-minute-candles.csv
export HISTORY_SYMBOL=YOUR_TRADABLE_INSTRUMENT
./gradlew bootRun

curl -X POST http://127.0.0.1:8081/api/backtests/ema-vwap-rsi \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"YOUR_TRADABLE_INSTRUMENT","from":"2026-05-20","through":"2026-11-19"}'
```

CSV format, with each timestamp denoting a **5-minute candle OPEN in IST**, no offset:

```csv
timestamp,open,high,low,close,volume
2026-05-20T09:15:00,100.0,100.5,99.8,100.3,12500
2026-05-20T09:20:00,100.3,100.6,100.1,100.4,15000
```

These rows illustrate format; they are **not market observations**. Supply complete candle sessions between 09:15–14:55 for every date you wish to simulate. Skipped/incomplete sessions are reported. Include pre-range candles for EMA/RSI warmup when possible. The default date range rolls over the last six completed calendar months through *yesterday in IST*; explicitly supply `from` and `through` for reproducibility and to handle exchange holidays.

NIFTY 50 **spot index is not a tradable instrument**, and index bar volume is not interchangeable with underlying constituents' or derivative contracts' traded volume. Use an instrument with valid OHLCV, such as a suitable ETF. Backtest assumptions currently model cash/ETF-like sizing, not options premium, futures margin, expiry, or realistic derivative contract rolling.

## Strategy and trade times (IST)

- Market data collected from **09:15** for session VWAP; EMA/RSI may warm up across prior days.
- Long setup: EMA9 > EMA20, close > session VWAP, RSI14 between **55 and 75**.
- Optional short setup: EMA9 < EMA20, close < session VWAP, RSI14 between **25 and 45**; off by default.
- Confirm a 5-minute candle before producing a signal; fill at next candle OPEN using adverse slippage.
- First new entry **09:35**; last new entry **14:45**; mandatory square-off at **14:55** candle OPEN (never at 15:00).
- Risk per trade **1%**, fixed stop **0.5%**, target **2R** by default.
- Max daily realized-loss budget **3%**, max **5** new trades/day, one position at a time.
- A setup only triggers on false→true transition; no signal at every subsequent candle.
- Gap-through-stop fills at bar OPEN; if the same bar touches stop and target, stop wins conservatively.
- Fee/slippage configurable; default slippage **2bps per fill**, but fees default to **0** until a broker/instrument fee model is supplied.
- Outputs closed-trade equity curve, trades, fees, win rate, P&L, closed-equity drawdown and skipped dates.

All parameter overrides can be sent in the JSON `POST` body. See `BacktestRequest` for names and `StrategyConfig` for validations/defaults. The engine uses local configured CSV paths only; callers cannot read arbitrary server files.

## Scope and limitations

This is research-only with **no broker integration, order execution, database, or frontend**. Run the demo to verify application setup, then provide licensed historical data for real results. Intrabar path is not observable from 5-minute OHLC bars. Closed-equity drawdown can understate intratrade risk. Fee and instrument margin models are simplified; a futures/options backtest requires instrument-specific contract metadata and historical tradable prices. It does not reconcile Indian exchange holidays (missing sessions are skipped and reported), market-data licensing, corporate actions, or source-data accuracy.

## Source layout

- `BacktestApplication` — Spring Boot entry point
- `BacktestController` — historical-data POST endpoint
- `DemoController`, `DemoHistoryFactory` — zero-config **synthetic** smoke endpoint
- `Candle`, `CsvHistoryRepository` — input validation/session completeness
- `IndicatorState` — incremental EMA, Wilder RSI and HLC3 session VWAP
- `BacktestEngine` — signals, stops/targets, square-off, P&L
- `BacktestRequest`, `StrategyConfig`, `BacktestReport` — API/config/report records

Run `./gradlew clean test build` locally or let GitHub Actions CI run it after every commit. `./gradlew bootRun` has no required credentials.

## Automatic Angel One historical download + backtest (new)

The separate `quant-engine-test` repo can now **fetch six months of real historical
five-minute OHLCV data, cache it locally and run the EMA(9/20)+VWAP+RSI(14)
backtest in one POST**. The original manual CSV workflow remains available.

### 1. Configure SmartAPI safely

Create or obtain an authorized Angel One SmartAPI session. Export environment variables
in the shell launching the service (or supply a secure external environment file); **never
commit actual credentials/JWTs**. The following are required for an actual vendor fetch:

```bash
export HISTORY_SYMBOL=NIFTYBEES
export ANGEL_ONE_SYMBOL_TOKEN='ACTUAL_INSTRUMENT_MASTER_TOKEN'
export ANGEL_ONE_EXCHANGE=NSE
export ANGEL_ONE_API_KEY='YOUR_SMARTAPI_KEY'
export ANGEL_ONE_JWT_TOKEN='YOUR_CURRENT_JWT_FROM_ANGEL_ONE_LOGIN'
export ANGEL_ONE_CLIENT_LOCAL_IP='YOUR_LOCAL_IPV4'
export ANGEL_ONE_CLIENT_PUBLIC_IP='YOUR_PUBLIC_IPV4'
export ANGEL_ONE_MAC_ADDRESS='YOUR_MACHINE_MAC'
```

`ANGEL_ONE_SYMBOL_TOKEN` must be the **current instrument-master token matching the
configured exchange and symbol**. Do not guess or hardcode it. In place of
`ANGEL_ONE_JWT_TOKEN`, you can set `ANGEL_ONE_JWT_FILE` to a private file with the
JWT in its first line (for example a user-authorized copy/path of the parent
`quant-engine` session cache). This reads but does not modify the file. Session
JWTs expire; reauthenticate through Angel One when needed.

The default instrument is `NIFTYBEES` (volume-bearing NSE ETF). NIFTY 50 spot index
is not directly tradable and is not appropriate for volume-weighted VWAP from index
bars. This prototype sizes positions like cash equities/ETFs, not futures/options.

Start the application:

```bash
./gradlew bootRun
```

### 2. Run backtest with ONE REST request

```bash
curl --fail-with-body -sS -X POST 'http://127.0.0.1:8081/api/backtests/ema-vwap-rsi/angel-one' \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"NIFTYBEES","from":"2026-05-20","through":"2026-11-19"}' \
  -o backtest-result.json
```

This downloads the specified range **plus 30 calendar days of indicator warmup**, in
14-day vendor batches, validates each OHLCV candle and complete 09:15–14:55 session,
caches the CSV, then executes the original backtester. Entries stay 09:35–14:45;
all positions square off by 14:55. Response includes a `backtest` report,
`cacheHit`, `validatedCandles` and `validSessions`. The second identical run
uses the cache and does not call Angel One again until the cache exceeds its age
(default 24 hours). Add `?refresh=true` to explicitly re-download.

### 3. Download the cached CSV with curl

```bash
curl --fail-with-body -sS -G 'http://127.0.0.1:8081/api/history/angel-one.csv' \
  --data-urlencode 'symbol=NIFTYBEES' \
  --data-urlencode 'from=2026-05-20' \
  --data-urlencode 'through=2026-11-19' \
  -o niftybees-5min.csv
```

The exported CSV contains all valid fetched rows including warmup. The cache file is
in `data/cache/` (ignored by Git). Files are atomically replaced after successful
provider requests and validation; incomplete sessions are excluded from performance
statistics and identified in the backtest output. Holidays without any rows cannot
be distinguished from a totally missing day until an exchange calendar is added.

### Operational controls

| Setting | Default | Description |
|---|---|---|
| `HISTORY_CACHE_DIR` | `data/cache` | Cached data files, ignored by Git |
| `HISTORY_CACHE_MAX_AGE_HOURS` | `24` | Cache reuse interval; `0` means refetch |
| `HISTORY_BATCH_DAYS` | `14` | Calendar days per broker request, 1–30 |
| `BACKTEST_ACCESS_KEY` | Empty | Optional shared token for remote access |
| `HOST` | `127.0.0.1` | Local-only by default |

For a remotely exposed service, configure a strong `BACKTEST_ACCESS_KEY`, TLS,
authentication at a trusted reverse proxy and appropriate access controls.
Pass `-H 'X-Backtest-Key: YOUR_KEY'` with both requests when configured. Do not
expose broker credentials, cache files or a public backtest endpoint on the internet.

The client conservatively throttles requests to **one per 1.1 seconds**. It fails
closed on 401/403, 429, malformed provider responses or missing volume; it does not
retry unsafe/ambiguous responses in a loop or return fabricated historical data.
Some SmartAPI accounts may not be entitled to this endpoint; Angel One can also
return HTTP 403 or rate-limit errors even at low request rates. The application
cannot bypass broker permissions or guarantee six months of vendor retention.

This remains **read-only and research-only**: no live trading or broker order calls.
