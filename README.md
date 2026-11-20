# quant-engine-test

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
