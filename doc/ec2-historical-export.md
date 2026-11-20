# Export Angel One history using the existing EC2 server

This is an **optional, read-only research data workflow**. It does not install software into the production Quant Engine application or place trading orders.

## Critical prerequisite — registered outbound IP

The SmartAPI **Trading test** application now lists **13.202.233.29** as its Primary Static IP. The EC2 instance `quant-engine-prod` has independently reported outbound IP **13.202.233.29**. The script checks that exact address and aborts before reading credentials if egress changes. The two older SmartAPI registrations remain untouched. Never spoof a public-IP header to bypass a mismatch.

Before running, ensure the EC2 host is actually permitted to send historical requests and that Angel One has granted your SmartAPI application the relevant access. If the IP check fails, resolve the legitimate routing/static-IP setup through your network and broker administrators; do not disable the check.

## Prerequisites

- Your Mac has an **authorized SSH connection** to the existing EC2 instance. The documented production deployment temporarily opens SSH only for its CI runner. MacBook SSH may be blocked by EC2's security group. Prefer a suitably secured Session Manager or temporary single-IP SSH arrangement administered within AWS; never open SSH globally just for this script.
- EC2 has Python 3, Docker CLI, and permission for the SSH user to inspect the running `quant-engine` container.
- The **Trading test app-specific** API key and a currently valid JWT must be configured in `/home/ubuntu/.config/quant-engine-test/trading-test.env` on EC2. Production `quant-engine` broker credentials are deliberately **not** reused by the Mac launcher. Do not copy the production app key or stale session into the new file unless you have verified the session was issued for the correct application. Never commit or send the file to chat.
- The current Angel One NSE instrument master contains exactly one `NIFTYBEES-EQ` entry; this script verifies the token against master metadata.
- Check broker entitlement, market data limits, historical data license, and calendar completeness. No guarantee is made that six months will be available from the API.

## Configure separate Trading test credentials on EC2

**Recommended: interactive authentication with no manual JWT copying.** From your **Mac Terminal** (in the local `quant-engine-test` clone), upload only the helper's source code to EC2, then run it in an interactive EC2 shell:

```bash
git pull origin main
scp -i ~/.ssh/quant-test-ec2 scripts/ec2_login_trading_test.py ubuntu@13.202.233.29:/home/ubuntu/ec2_login_trading_test.py
ssh -t -i ~/.ssh/quant-test-ec2 ubuntu@13.202.233.29 'python3 /home/ubuntu/ec2_login_trading_test.py'
```

The helper verifies EC2 egress is `13.202.233.29`, detects the EC2 local network identity, then prompts **without echoing input** for:
1. **Trading test** app-specific SmartAPI API key (not either production app's key).
2. Angel One **client code**.
3. PIN/password.
4. Fresh six-digit **TOTP** from your authenticator app.

It authenticates through the broker's official `loginByPassword` endpoint, stores the API key and JWT privately on **EC2 only** in `/home/ubuntu/.config/quant-engine-test/trading-test.env` (mode 0600), and does not print or transmit the secrets to the Mac or GitHub. Never share the key, PIN, TOTP, JWT, or entire `.env` file in tickets or chat. Angel One sessions expire; rerun interactive login on the day of download when necessary. Login can affect session state for the same broker account, so avoid interrupting an active trading session.

**Alternative: manually populate the file** if you've already generated a Trading test JWT using an authorized broker client:


The Mac launcher reads only `/home/ubuntu/.config/quant-engine-test/trading-test.env` from EC2. It never reuses the production parent session by default. Before downloading, create this file **on EC2**, supplying values locally and privately:

```bash
umask 077
mkdir -p ~/.config/quant-engine-test
chmod 700 ~/.config/quant-engine-test
nano ~/.config/quant-engine-test/trading-test.env
```

File format (replace every placeholder on EC2 and do not paste real values into chat):

```dotenv
ANGEL_ONE_API_KEY=THE_TRADING_TEST_APP_API_KEY
ANGEL_ONE_JWT_TOKEN=CURRENT_JWT_AUTHENTICATED_USING_TRADING_TEST_APP
ANGEL_ONE_LOCAL_IP=172.31.2.40
ANGEL_ONE_MAC_ADDRESS=ACTUAL_EC2_NETWORK_MAC
```

After saving: `chmod 600 ~/.config/quant-engine-test/trading-test.env`. Obtain the JWT with the **Trading test** API key and your authorized SmartAPI client-code/PIN/TOTP login; per Angel One documentation, sessions may expire at midnight. Do not use the other two apps' API key/session interchangeably. Verify the actual EC2 MAC with `cat /sys/class/net/$(ip route get 1.1.1.1 | awk '{for (i=1;i<=NF;i++) if ($i=="dev") {print $(i+1);exit}}')/address` (do not publish sensitive configuration). If the credentials file is absent, the exporter safely refuses to continue.

## Run on your Mac

From your local clone of **`quant-engine-test`**:

```bash
git pull origin main
EC2_SSH=ubuntu@13.202.233.29 EC2_SSH_KEY="$HOME/.ssh/quant-test-ec2" ./scripts/pull-history-from-ec2.sh
```

**Find the host before running:** AWS Console → EC2 → Region `ap-south-1` → Instances → instance tagged `quant-engine-prod` → **Public IPv4 address**. The application domain `quant-engine.aaruchalet.com` is not a verified SSH endpoint. If your SSH setup uses an alias, e.g. `quant-prod`, set `EC2_SSH=quant-prod`. Configure your private SSH key or AWS-managed access **outside this repository**. Your production CI currently opens SSH only temporarily from its own runner, so direct SSH from the Mac may be blocked.

**Before opening SSH:** Use AWS Console **Connect → Session Manager** (if your instance is configured for SSM) or **EC2 Instance Connect** to run `curl -4 -fsS https://api.ipify.org` on the EC2 host. If the result is not the registered Angel One source IP, the downloader will refuse to run; arrange legitimate broker-approved egress instead of changing the request header. Session Manager does not require inbound SSH when correctly configured. If you must permit direct SSH, temporarily allow **only your current Mac public IPv4 /32** on TCP 22, use an authorized SSH key or Instance Connect, and remove the rule afterward.

Default export: `data/niftybees-5min-2026-05-20-to-2026-11-19.csv` on the Mac. The exporter requests candles only through **14:55 IST** and also tolerates correctly aligned extra regular-session candles at **15:00–15:25** if Angel One returns them; unexpected timestamps and invalid in-window OHLCV still fail validation. The remote export includes **30 extra calendar days for indicator warmup**, and only **complete 09:15–14:55 IST 5-minute sessions** are included. No market data is committed to GitHub.

The script performs the following steps:

1. Connect to the EC2 host using an authorized Mac SSH key or alias (the AWS Console browser connection alone does not authorize your Mac SSH client).
2. Check the host's **real** public IPv4; abort unless it is one of the broker-registered addresses.
3. Send the read-only Python exporter over SSH on stdin; it reads the private **Trading test** credentials file on EC2, without copying credentials to the Mac or changing the production container.
4. Fetch the instrument token from the official Angel One master, reject mismatches, and download OHLCV through the historical REST endpoint in 14-day batches at a conservative cadence.
5. Validate and deduplicate rows; exclude/report incomplete session dates, require sufficient range coverage, and write a private temporary CSV on EC2.
6. Transfer only the CSV via SCP to your Mac, verify SHA-256, apply file mode 0600, then clean the remote temporary export file.

Note: It cannot independently classify entirely absent weekday dates as NSE holidays. Treat the printed missing/partial-session caveats seriously. No actual historical download is performed by offline CI tests.

## Backtest locally after a successful download

The CSV is now on your Mac and uses the project's expected input format:

```bash
export HISTORY_CSV_PATH="$PWD/data/niftybees-5min-2026-05-20-to-2026-11-19.csv"
export HISTORY_SYMBOL=NIFTYBEES
./gradlew bootRun
```

In another Mac Terminal:

```bash
curl --fail-with-body -sS -X POST 'http://127.0.0.1:8081/api/backtests/ema-vwap-rsi' \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"NIFTYBEES","from":"2026-05-20","through":"2026-11-19"}' \
  -o backtest-result.json
```

Inspect `backtest-result.json` for tested sessions, skipped sessions, trades, and P&L. The backtest uses **09:35–14:45 new-entry** and **14:55 square-off**, not live trading.

## Troubleshooting

- **SSH permission denied / timeout:** no direct SSH path is configured from Mac to EC2. This workflow cannot change AWS security-group rules; have an authorized administrator configure access.
- **Registered IP mismatch:** EC2 no longer leaves through `13.202.233.29`. Download stops before reading broker credentials; check the Elastic IP and SmartAPI registration.
- **Docker permission denied:** your SSH user must be authorized to inspect the running parent container; the script does not auto-escalate privileges.
- **Missing Trading test credentials file:** create the owner-only file described below on EC2; never put it in the production runtime env.
- **Missing or expired JWT:** authenticate the Trading test app with Angel One and place the short-lived JWT in its private file. Do not paste JWTs, PINs or TOTP into chat, issues or logs.
- **Broker 401/403/429:** broker session, app entitlement, registered egress IP, or rate limits must be resolved with Angel One.
- **Incomplete history:** the downloader refuses to label a truncated range as a full six-month dataset. The backtester separately reports dates with missing required bars.
- **Options/futures:** this export is NSE `NIFTYBEES-EQ` cash/ETF history, **not** a historical NIFTY options/futures contract model.

## Offline verification

```bash
python3 -m unittest discover -s scripts -p 'test_ec2_*.py' -v
bash -n scripts/pull-history-from-ec2.sh
./gradlew clean test build
```
