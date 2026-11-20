# Export Angel One history using the existing EC2 server

This is an **optional, read-only research data workflow**. It does not install software into the production Quant Engine application or place trading orders.

## Critical prerequisite — registered outbound IP

The two SmartAPI applications in the current user's portal list registered IPs **92.62.121.100** and **92.62.121.166**. Public ASN registration for the `92.62.121.0/24` block identifies Cyberzon S.A., **not AWS**. This does not establish your EC2 server's actual egress address: a server can route traffic through an authorized network gateway. The scripts **verify the actual EC2 egress IP** and abort before touching a broker credential if it does not match either registered address. They never set a fake public-IP header to bypass a mismatch.

Before running, ensure the EC2 host is actually permitted to send historical requests and that Angel One has granted your SmartAPI application the relevant access. If the IP check fails, resolve the legitimate routing/static-IP setup through your network and broker administrators; do not disable the check.

## Prerequisites

- Your Mac has an **authorized SSH connection** to the existing EC2 instance. The documented production deployment temporarily opens SSH only for its CI runner. MacBook SSH may be blocked by EC2's security group. Prefer a suitably secured Session Manager or temporary single-IP SSH arrangement administered within AWS; never open SSH globally just for this script.
- EC2 has Python 3, Docker CLI, and permission for the SSH user to inspect the running `quant-engine` container.
- The parent `quant-engine` container has `ANGEL_ONE_API_KEY`, `ANGEL_ONE_LOCAL_IP`, `ANGEL_ONE_MAC_ADDRESS` configured in its environment or the private `/opt/aaru/deploy/runtime.env` file, and a **currently valid broker JWT** (first line of `/app/.local/recovery/angel-one-session.cache` inside the container). If the JWT has expired, login through the **existing authorized** broker session flow first. No PIN/TOTP/JWT should be pasted into a command or committed.
- The current Angel One NSE instrument master contains exactly one `NIFTYBEES-EQ` entry; this script verifies the token against master metadata.
- Check broker entitlement, market data limits, historical data license, and calendar completeness. No guarantee is made that six months will be available from the API.

## Run on your Mac

From your local clone of **`quant-engine-test`**:

```bash
git pull origin main
EC2_SSH=ubuntu@quant-engine.aaruchalet.com ./scripts/pull-history-from-ec2.sh
```

If your SSH setup uses a different host alias, for example `quant-prod`, set `EC2_SSH=quant-prod`. Configure your private SSH key or AWS-managed access **outside this repository**.

Default export: `data/niftybees-5min-2026-05-20-to-2026-11-19.csv` on the Mac. The remote export includes **30 extra calendar days for indicator warmup**, and only **complete 09:15–14:55 IST 5-minute sessions** are included. No market data is committed to GitHub.

The script performs the following steps:

1. Connect to the EC2 host using your existing SSH credentials.
2. Check the host's **real** public IPv4; abort unless it is one of the broker-registered addresses.
3. Send the read-only Python exporter over SSH on stdin; the exporter reads the running parent container's broker configuration and existing private JWT session cache **in memory**, without copying credentials to the Mac.
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
- **Registered IP mismatch:** EC2 does not leave through `92.62.121.100` or `.166`. Download stops before reading the broker session; do not attempt to spoof headers.
- **Docker permission denied:** your SSH user must be authorized to inspect the running parent container; the script does not auto-escalate privileges.
- **Missing API key/IP/MAC:** parent runtime configuration is incomplete or unavailable to the SSH user.
- **Missing or expired JWT:** login to Angel One using the parent's legitimate session flow; do not paste JWTs or PINs into issue comments or logs.
- **Broker 401/403/429:** broker session, app entitlement, registered egress IP, or rate limits must be resolved with Angel One.
- **Incomplete history:** the downloader refuses to label a truncated range as a full six-month dataset. The backtester separately reports dates with missing required bars.
- **Options/futures:** this export is NSE `NIFTYBEES-EQ` cash/ETF history, **not** a historical NIFTY options/futures contract model.

## Offline verification

```bash
python3 -m unittest discover -s scripts -p 'test_ec2_*.py' -v
bash -n scripts/pull-history-from-ec2.sh
./gradlew clean test build
```
