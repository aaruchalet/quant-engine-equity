# Operating the standalone Angel One application

Run one Java 21 application on one execution host. It connects directly to Angel One. H2 is embedded in the executable JAR. The parent repository, its container, a database server and SSH forwarding are unnecessary for trading. The server binds to loopback by default.

## Choose the mode

Edit `trading.mode` in `src/main/resources/application.yml` before building. For a packaged JAR, put an external `application.yml` in the directory from which you run `java`, or pass its absolute file location with `--spring.config.additional-location=file:/absolute/path/application.yml`. Restart after editing. This complete override selects automated paper trading:

```yaml
trading:
  mode: PAPER
```

| Mode | Market data | Execution |
| --- | --- | --- |
| BACKTEST (default) | Local historical candles and timestamped chains, or the synthetic demo | Historical simulation. No scheduled broker runner or credentials required |
| PAPER | Authenticated Angel One cash-index, ETF and option data | Local simulated fills at observed bid/ask with fees, lot sizing and premium stops |
| LIVE | Same market-data path as PAPER, with account and cash checks | Automated intraday option limit orders and broker stop-limit orders |

Edit `src/main/resources/index-options.yml` for budget, lot cap, index/ETF names, strike offsets and options fees. `maximumLots: APPLICATION` (or `AUTO`) lets the application buy the largest affordable whole-lot quantity within quoted two-sided depth. An integer such as `2` adds a ceiling. Both indices share the ₹15,000 daily entry budget. Premium and estimated buy charges consume that budget even after the position closes. LIVE also caps the purchase by available broker cash. At a ₹50 NIFTY premium, four lots of 65 cost ₹13,000 before fees. Five cost ₹16,250 and exceed the default budget.

Edit `src/main/resources/strategy-vwap-ema.example.yml` for EMA/RSI, mandatory VWAP side, the 1% premium stop, 2% target, session times, maximum entries and P&L limits. ETF simulation order fields describe historical ETF fills. The market runner submits LIMIT and STOPLOSS_LIMIT orders. To use separate files with a built JAR, set `OPTIONS_CONFIG_PATH` and `V2_STRATEGY_CONFIG_PATH` to absolute paths. Restart the market runner for configuration changes. After upgrading an old external options YAML, copy the new `signalSymbols`, `vwapSymbols` and `maximumLots` settings.

## Build and authenticate on the execution host

1. Enable the appropriate NFO and BFO segments, market-data access and Client Direct API order permissions on the intended Angel One account. Register the host's static outbound IP with the SmartAPI app. Angel One validates the actual order source IP. The configured public-IP header alone cannot establish eligibility. [SmartAPI static IP requirements](https://smartapi.angelone.in/exchange-regulations)
2. On the Linux execution host with Java 21 and Python 3, use the standalone clone. Port 8081 must be available for the dashboard.

```bash
git clone https://github.com/aaruchalet/quant-engine-test.git
cd quant-engine-test
./gradlew clean test bootJar
```

   Alternatively download the `standalone-jar` artifact from a successful GitHub Actions run, unzip it, and use its JAR path in the commands below. The artifact contains the packaged YAML defaults.

3. Before the trading session, authenticate with that app's key, client code, PIN and current TOTP. The helper prompts privately, verifies outbound IPv4, and writes an owner-only credentials file. Its default registered IP is the existing Trading test host. For another registered host, set `ANGEL_ONE_REGISTERED_PUBLIC_IP` to the address registered for that app before login. Repeat after session expiry. Keep the host clock synchronized.

```bash
python3 scripts/ec2_login_trading_test.py
CREDENTIALS="file:$HOME/.config/quant-engine-test/trading-test.env[.properties]"
```

   Alternatively supply `ANGEL_ONE_API_KEY`, `ANGEL_ONE_CLIENT_CODE`, `ANGEL_ONE_CLIENT_LOCAL_IP`, `ANGEL_ONE_CLIENT_PUBLIC_IP`, `ANGEL_ONE_MAC_ADDRESS`, and either `ANGEL_ONE_JWT_TOKEN` or `ANGEL_ONE_JWT_FILE` through the process environment. When using only environment variables, omit the Spring config-import argument below.

4. Run the one-shot, **read-only preflight** from the registered host. This verifies the profile, funds response, order book, positions and index/ETF mappings. During regular weekday market hours it also requires fresh spot, ETF and option quotes, and checks the latest completed signal candles when available. Outside market hours, repeat the quote check during the session. This check does not submit a test order or certify exchange acceptance of an order.

```bash
java -jar build/libs/quant-engine-test-0.0.1-SNAPSHOT.jar \
  --spring.config.import="$CREDENTIALS" --trading.preflight-only=true \
  --spring.main.web-application-type=none
```

5. Start with the YAML mode of your choice. Keep one instance and one persistent journal directory for this account. Run from the same directory on restarts, or set an absolute `TRADING_STATE_DIRECTORY` on durable disk. Set a restrictive umask for order records. Allow the service to run continuously through square-off.

```bash
umask 077
export TRADING_STATE_DIRECTORY="$HOME/.local/state/quant-engine-test"
java -jar build/libs/quant-engine-test-0.0.1-SNAPSHOT.jar --spring.config.import="$CREDENTIALS"
```

Open the dashboard at `http://127.0.0.1:8081/` or read `GET /api/trading/status` for the mode, last runner outcome, journaled quantity, spend, pending order, stop/target and recent events. On EC2, open an SSH tunnel from your Mac (`ssh -L 8081:127.0.0.1:8081 <your-authorized-host>`). Do not expose the local dashboard publicly. To select live execution, set `trading.mode: LIVE` in the external YAML and start the JAR on the registered host. Keep the same state directory across process restarts. Paper and live have distinct ledger files.

## Signals, stops and recovery

- EMA and RSI use completed five-minute **NIFTY 50** or **BSE SENSEX** index candles. These indices lack a native traded-volume VWAP. The separate VWAP gate compares NIFTYBEES or SENSEXETF with its own session VWAP. The ETF must close above it for calls or below it for puts and pass the side check on a fresh ETF quote at entry. A touch is optional. A qualifying signal requires a current-session crossover. [BSE index methodology](https://www.bseindia.com/downloads1/BSE_Indices_Methodology.pdf)
- The runner resolves the current instrument master and earliest listed unexpired expiry. It selects the nearest listed strike for a call and one listed strike below ATM for a put. It checks the master lot against NIFTY 65 and SENSEX 20. It evaluates closed candles around five-minute boundaries in IST. Stale quotes, insufficient history or inadequate depth can prevent a trade. The default last entry slot is 14:45 and square-off starts at 15:00.
- LIVE submits a tagged buy limit at the ask. The order acknowledgement is distinct from a fill. The journal records the intent before submission. After an ambiguous response, the runner matches the broker order by tag or ID. A partially filled buy is cancelled and reconciled before the filled quantity is managed. Confirmed entry fills determine stop and target. The broker stop is a sell stop-limit with a configurable limit buffer below trigger. A stop-limit can remain unfilled through a gap. Targets, replacement exits and square-off depend on the running process, connectivity and an executable bid. PAPER models fills locally and cannot measure queue position or real broker latency.
- To pause new entries while continuing to manage exposure, create `pause-entries` in the configured state directory. Remove it to resume. Stopping Java or changing to BACKTEST does not close a broker position. Verify broker orders and net positions before stopping or changing modes.
- On an unresolved acknowledgement, rejected exit, position mismatch or previous-session exposure, use the Angel One order book and portfolio to reconcile. Check working sell orders before manually closing any remaining quantity. Keep the journal intact. Do not start a second host, delete the database or create a new state directory to clear an error. A rejected buy suspends new entries for the session. At the next session, before resetting a journal with unresolved exposure, LIVE verifies zero net INTRADAY quantity and no working orders on that contract. A prior day with unresolved fills is marked as incomplete. Verify all orders and fills against the broker contract note; local charges are estimates. [Angel One order and quote contracts](https://smartapi.angelone.in/docs)

## Historical options backtest

Select BACKTEST and start the app. For the market runner's signal inputs, include historical spot-index candles alongside the ETF candles and the options chain. The time and expiry on every chain must be genuine. Example for one market:

```bash
python3 scripts/build_options_replay.py \
  --market NIFTY NIFTYBEES data/options/niftybees.csv data/options/nifty.jsonl \
  --index-candles NIFTY data/options/nifty-spot.csv > data/options/request.json
```

Repeat the two arguments for SENSEX in the same request to share one daily budget. Each spot CSV uses the usual timestamp and OHLC columns. The ETF CSV also requires traded volume. Supply warm-up bars and genuine bars through configured square-off. Upload the request in **Index options backtest**, or POST it to `/api/backtests/vwap-ema-v2/options`. Historical bid/ask depth comes from the chain snapshots. ETF-only legacy payloads still use ETF EMA/RSI, and the original NIFTYBEES CSV can still run the ETF backtest.
