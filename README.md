# quant-engine-test


Open **http://127.0.0.1:8081/** for exactly **three tabs: NIFTY, SENSEX and Equity**. Both index tabs show the real runner's **PAPER/LIVE** execution mode, EMA(9/20) crossover, RSI(14), final VWAP check, current-position/risk gates and per-index strategy logs. They have **no historical replay forms**. The old standalone Trading tab was removed. Trading mode is a trusted YAML setting, never an HTTP control. The Equity tab provides single-NSE-symbol lookup, official-source price discovery where authorized, a detailed validation checklist and an uploaded historical cash-equity CSV backtester. The existing ETF V1/V2 dashboard remains accessible from **Equity**, at `/etf.html`.

Standalone Java 21 / Spring Boot application for historical backtests, live-market paper trading and automated Angel One index-option orders. The default is **PAPER**, which cannot place real orders. LIVE must be explicitly selected in the server's trusted YAML and requires broker permissions and preflight. Use the [standalone trading runbook](doc/standalone-trading.md) for the exact YAML, authentication, preflight, start and recovery commands. The optional [EC2 historical export](doc/ec2-historical-export.md) uses this application's own broker session.



## Three-tab workspace and fresh-source limitations

| UI tab | Purpose | On refresh |
| --- | --- | --- |
| **NIFTY** | PAPER/LIVE option execution and NIFTY-only indicator/entry/risk log | `GET /api/trading/indices/NIFTY` returns the configured runner's latest observed data |
| **SENSEX** | Identical logic for SENSEX, independent display/log | `GET /api/trading/indices/SENSEX` |
| **Equity** | NSE ticker search, individual quote, preliminary price-band screen, research-source links, cash-equity historical backtest | `GET /api/equity/market/search?q=...`, `GET /api/equity/market/quote/{symbol}`, `GET /api/equity/market/screen?minPrice=10&maxPrice=100` request a new source response each time, when officially authorized |

The strategy-diagnostic log is persisted in the existing local H2 journal, separately for NIFTY and SENSEX. It records confirmed indicator values and named gates, rejection reasons, order events and market/session budget checks. It is derived from the SAME strategy and order runner; it is not a second trading algorithm. Logs can be empty before the first completed market candle or when the broker data feed is unavailable. Both indices share the configured daily budget, and the runtime permits one position at a time. PAPER has simulated premium fills; LIVE uses the protected broker execution path. Neither the browser nor an API request can switch the mode.

**Equity web access:** NSE's published website terms prohibit systematic automated data collection without authorization. Consequently `TRADING_EQUITY_NSE_WEBSITE_ACCESS_AUTHORIZED` defaults to **false**. The optional direct website adapter can be used *only* if your organization has express permission covering the intended access. It does not bypass login, CAPTCHA, IP restrictions, 403, 429, or rate limits. Without authorization, the API returns **503 Data unavailable**, never a synthetic price. [NSE terms](https://www.nse.in/static/nse-terms-of-use) · [NSE licensed data access](https://www.nse.in/static/market-data/real-time-data-subscription). Actual programmatic production usage should prefer an approved exchange-data contract or an existing authorized broker feed. The dashboard has **no AI dependency**; an internet-accessible, authorized market-data source is still inherently necessary for current quotes.

The automated price-band discovery route, if authorized, scans **NIFTY SMALLCAP 250 constituents only**. It is **not** a complete latest-AMFI small-cap universe screen. It cannot legitimately assert that a share has passed surveillance, governance, profit/cash-flow, liquidity, adjusted-history, valuation or catalysts checks. The result deliberately stays `PRELIMINARY_RESEARCH_ONLY` and per-stock `INSUFFICIENT_DATA`. The nine user-selected research sites are provided as outbound verification links, not silently scraped. The existing `POST /api/equity/screen` detailed evidence-gated scoring API remains available for properly licensed, validated research snapshots. A ticker's share price alone is never interpreted as an AMFI classification or a BUY signal.

**Equity history upload:** `POST /api/equity/backtest` accepts a multipart `file` CSV plus `symbol`, `from`, `through` and `initialCapital` fields. The historical 5-minute CSV must contain full valid sessions through 14:55 IST, and is limited to 5MB. The service uses a temporary file which is deleted after evaluation, produces trades/performance statistics using the cash-style V1 engine, and never places trades. Historical fees and corporate actions need independent verification. Do not confuse this with premium-based NIFTY/SENSEX options execution. The legacy ETF backtester remains at `/etf.html`.

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

## Visual backtest dashboard (no curl required)

A responsive, dependency-free web UI is served directly by this Spring Boot application at **http://127.0.0.1:8081/etf.html**. It uses your **local CSV**, never contacts the broker, and does not place orders.

For the previously exported NSE NIFTYBEES dataset on a Mac, run from the project root:

```bash
git pull origin main
export HISTORY_SYMBOL=NIFTYBEES
export HISTORY_CSV_PATH="$PWD/data/niftybees-5min-2026-05-20-to-2026-11-19.csv"
./gradlew bootRun
```

Then open **http://127.0.0.1:8081/etf.html** in your browser. The page checks the configured local CSV automatically, preselects **2026-05-20 through 2026-11-19**, and provides a **Run historical backtest** button.

The UI displays:

- Net P&L, return, ending capital, win rate, total trades, closed-equity drawdown, profit factor.
- Closed-trade equity chart (**not intratrade mark-to-market**).
- Searchable, paginated trade history with entry/exit, quantity, fees, P&L and exit reason.
- Downloadable JSON report and trades CSV, plus coverage and skipped-session information.
- Editable capital/risk/EMA/RSI, slippage, fee and optional short-side parameters.

The status endpoint `GET /api/backtests/history-status` returns only a file-presence flag, file basename and size; the dashboard does not accept arbitrary server file paths. If the CSV is missing, configure `HISTORY_CSV_PATH` and restart Spring Boot. The UI does not save reports after page reload; use **Export JSON** to retain them.

**Important:** V1 brokerage/taxes default to zero. V2 uses the configurable Angel One NSE equity intraday estimate described below. Slippage defaults to 2 bps per fill. Both remain research-only estimates, not a live-trading interface.

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

## Current strategy V1: implemented behavior and trading times (IST)

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

### Implemented separate V2 — EMA crossover, RSI, then VWAP side

The **new isolated research-only V2** uses `POST /api/backtests/vwap-ema-v2` and is selectable in the local browser dashboard. It does **not** replace the original `/api/backtests/ema-vwap-rsi` V1 simulation or connect to order placement. V2 reads and validates `src/main/resources/strategy-vwap-ema.example.yml` for every run. Set `V2_STRATEGY_CONFIG_PATH` before startup to use a different YAML file. Missing, unknown, or unsupported settings are rejected; there are no silent Java fallback values for V2 strategy settings. The parent repository does not auto-load this file. Spring Boot initializes application logging at startup.

| Entry gate (strict order) | V2 behavior |
| --- | --- |
| EMA first | Confirmed same-session EMA9 crosses above EMA20 for LONG or below for SHORT; maintain side until opposite crossing |
| RSI second | RSI(14) strictly **>50** for LONG or **<50** for SHORT; RSI exit **disabled** |
| Time, capital, daily limits | Before VWAP: valid entry hours, 1–10 quantity, at most five trades/day, ₹5,000 realized loss and ₹10,000 profit caps |
| **VWAP FINAL** | Completed candle CLOSE > VWAP for LONG or CLOSE < VWAP for SHORT; a touch or rebound is optional and is not separately scored; recheck the price side at next-bar OPEN |
| Exit | 1% entry-price stop loss; 2% entry-price profit target; forced square-off at configured **15:00 OPEN** |
| Trading safety | Historical ETF simulation uses MARKET / INTRADAY / DAY and disables carry-over |

#### The V2 dataset is different from the old CSV

Your successful November 20 historical CSV was exported only through **14:55** (69 bars per full session). The V2 default requires the **genuine 15:00 candle opening price**, which that CSV does not contain. The V2 loader rejects a dataset without that price; **never infer 15:00 from 14:55 close**.

When Angel One permits historical requests, use the new opt-in V2 exporter **from your Mac**. Your original CSV and 14:55 caches are preserved; V2 uses a *separate* filename and separate 15:00 checkpoint set:

```bash
cd /Users/manjeetkumar/Desktop/FileSystem/quant-engine-test
git pull origin main
V2_INCLUDE_1500=1 \
EC2_SSH=ubuntu@13.202.233.29 \
EC2_SSH_KEY="$HOME/.ssh/quant-test-ec2" \
./scripts/pull-history-from-ec2.sh
```

On success, V2 data will be in:

```text
data/niftybees-5min-2026-05-20-to-2026-11-19-v2-1500.csv
```

Restart the Spring Boot process using that *new* CSV:

```bash
export HISTORY_SYMBOL=NIFTYBEES
export HISTORY_CSV_PATH="$PWD/data/niftybees-5min-2026-05-20-to-2026-11-19-v2-1500.csv"
./gradlew bootRun
```

Open **http://127.0.0.1:8081/etf.html**, choose **V2 · EMA → RSI → VWAP side**, and run. Alternatively POST the original JSON body to `/api/backtests/vwap-ema-v2`. V2 strategy rules and session times are controlled by the external YAML; V1-only form inputs are disabled when V2 is selected.

**Important:** The new exporter still performs authorized, read-only Angel One requests from the registered EC2 egress and can encounter HTTP 403/AB1021 broker rate limits. It will stop without aggressive retries and preserve V2 checkpoints. Credentials are never copied into Git. No six-month V2 performance figure has yet been measured/verified. Exchanging the old CSV for the new one is required for the actual V2 historical run.


## Scope and limitations

The historical backtest endpoints simulate fills. The market runner and its local journal are described in the standalone runbook. Run the demo to verify application setup, then provide licensed historical data for real results. Intrabar path is not observable from 5-minute OHLC bars. Closed-equity drawdown can understate intratrade risk. Fee and instrument margin models are simplified; a futures/options backtest requires instrument-specific contract metadata and historical tradable prices. It does not reconcile Indian exchange holidays (missing sessions are skipped and reported), market-data licensing, corporate actions, or source-data accuracy.

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

The historical download endpoint only reads broker data.


### VWAP side clarification (20 Nov 2026)

After the configured EMA crossover, RSI and risk checks pass, a completed candle must close strictly above session VWAP for LONG or strictly below for SHORT. No touch or bounce is required. A touch may occur but is not scored separately. The next bar OPEN must also be on the correct side of the last confirmed VWAP. The historical `VWAP_BOUNCE_EMA_V2` strategy ID remains for compatibility; its name does not imply a required bounce.

EMA periods, RSI thresholds, order quantity, risk limits, times and fee rates are read from the YAML. The supported contract is validated for five-minute bars, current-session crossover, HLC3 VWAP and the listed mandatory filters. Unsupported options are rejected instead of silently ignored. Capital and slippage remain per-run dashboard/API inputs; V2 rejects the V1 `feeBps`/`feePerOrder` inputs so they cannot silently override the YAML. The configured market open determines which CSV bars feed the indicators; only complete sessions within the requested date range may be simulated. Strategy settings reload on each V2 request; Spring logging changes take effect after restart. The example/default settings above are not fixed values.

### V2 Angel One fee estimate

The default YAML uses Angel One's published **NSE equity intraday** rates: brokerage is 0.1% of order value, with ₹5 minimum and ₹20 maximum per executed order; NSE transaction charges are 0.0030699% and IPFT 0.0000001% on each side; SEBI turnover fee is 0.0001% on each side; stamp duty is 0.003% on the buy side; STT is 0.025% on the sell side; GST is 18% on brokerage, exchange charges, IPFT and SEBI fees. For shorts the entry is the sell and the exit is the buy. All rates are editable and validated in `trading.fees`.

Sources: [Angel One brokerage and regulatory schedule](https://www.angelone.in/exchange-transaction-charges), [Angel One brokerage support](https://www.angelone.in/support/charges-and-cashbacks/brokerage-charges), and [NSE transaction-charge circular effective March 2026](https://nsearchives.nseindia.com/content/circulars/FA73061.pdf).

This is an estimate per simulated order. It does not apply historical rate changes, client-specific brokerage plans, promotional discounts, broker/exchange caps for tiny orders, contract-note rounding, auto-square-off charges or income tax. Check your actual Angel One contract note. A single current rate configuration is applied across the whole backtest, so historical results may differ from actual costs. V1 retains its separate manual fee inputs.

### Exit reason labels

Legacy V1 labels the loss of any part of its combined entry condition `TREND_REVERSAL`, even without an EMA reversal. V2 optional VWAP and EMA exits are disabled by default. If enabled, they are reported as `VWAP_INVALIDATION` and `EMA_REVERSAL`; when both trigger on the same close, VWAP invalidation takes precedence. Stop, target and square-off retain their own labels.

## NIFTY and SENSEX option replay

The historical options endpoint `POST /api/backtests/vwap-ema-v2/options` buys calls for bullish V2 signals and puts for bearish signals. Set the shared daily entry budget, lot cap, signed strike offsets, index and ETF symbols and Angel One Plus options fees in [src/main/resources/index-options.yml](src/main/resources/index-options.yml). Defaults are ₹15,000, automatic whole-lot sizing, ATM calls and puts one listed strike below ATM. A NIFTY lot is 65 units and a SENSEX lot is 20. The existing strategy YAML controls signals, stops, targets and hours. The trade report returns contract identity, lots, quantity, entry/exit premiums, stop, target, fees and exit reason, plus spend and remaining budget per day.

1. Prepare one signal ETF OHLCV CSV per index in the existing history CSV layout. Supply every five-minute bar from configured market open through square-off, inclusive. Provide index spot and full option-chain snapshots for a single chosen expiry at the actual IST bar opens. Include bid and ask quantities in **underlying units**. Keep licensed inputs in `data/options/`.
2. For NIFTY, choose the expiry on the [NSE option-chain page](https://www.nseindia.com/option-chain) and download the CSV. Convert each snapshot using the metadata actually displayed for that capture. Supply the depth unit used by your export and lot size from the dated contract master. For SENSEX use the [BSE derivatives chain](https://www.bseindia.com/markets/Derivatives/DeriReports/DeriOptionchain.html) or your entitled feed and normalize it to the same JSON format.
3. Put one chain per line in each index's JSONL file. A chain has `index`, `asOf` (IST local time), `expiry`, `lotSize`, `spot` and `strikes`. Each strike contains `strike`, `ce` and `pe`; each book contains `bid`, `bidQuantity`, `ask`, `askQuantity`. Keep unquoted strikes in the ladder with a null book or a zero-depth side. The server rejects stale snapshots. A single end-of-day download cannot stand in for intraday history.
4. Build the request and POST it to the local service. Include both markets in one request to share the daily budget. Omit a market argument to replay one index.

```bash
python3 scripts/nse_option_chain.py data/options/nse.csv \
  --as-of "$SNAPSHOT_IST" --expiry "$CONTRACT_EXPIRY" --spot "$INDEX_SPOT" \
  --lot-size "$MASTER_LOT_SIZE" --depth-unit "$SOURCE_DEPTH_UNIT" >> data/options/nifty.jsonl

python3 scripts/build_options_replay.py \
  --market NIFTY NIFTYBEES data/options/niftybees.csv data/options/nifty.jsonl \
  --market SENSEX SENSEXETF data/options/sensexetf.csv data/options/sensex.jsonl \
  > data/options/request.json
curl --fail-with-body -H 'Content-Type: application/json' \
  --data-binary @data/options/request.json http://127.0.0.1:8081/api/backtests/vwap-ema-v2/options
```

The replay reads the book at entry and each subsequent five-minute observation. Stop fills can gap through the configured level. For reproduction on another date or brokerage plan, verify contract sizes and adjust the options fee YAML against the applicable [Angel One tariff](https://assist.angelone.in/assist/plans-and-pricing). The ETF dashboard continues to report ETF-unit trades. The options route requires separately supplied option quotes.

For one-click index replays, put the generated request at `data/options/request.json` or configure `TRADING_HISTORY_OPTIONS_REPLAY_PATH`. Click **Run backtest** on either index tab. Importing a file overrides the local feed for that run.
