#!/usr/bin/env bash
# Run ON THE MAC. Uses an already-authorized SSH account; never uploads broker credentials.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# EC2_SSH must point to the actual SSH-reachable EC2 address or an authorized SSH alias.
# The public application domain may be routed through a reverse proxy and is NOT a known SSH endpoint.
EC2_SSH="${EC2_SSH:-}"
if [[ -z "$EC2_SSH" ]]; then
  echo 'Missing EC2_SSH. Find quant-engine-prod public IPv4 in AWS EC2 (ap-south-1).' >&2
  echo 'Usage: EC2_SSH=ubuntu@ACTUAL_EC2_PUBLIC_IP ./scripts/pull-history-from-ec2.sh' >&2
  echo 'Do not assume quant-engine.aaruchalet.com accepts SSH.' >&2
  exit 2
fi
OUT="${1:-${SCRIPT_DIR}/../data/niftybees-5min-2026-05-20-to-2026-11-19.csv}"
FROM_DATE="${FROM_DATE:-2026-05-20}"
THROUGH_DATE="${THROUGH_DATE:-2026-11-19}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=12)
if [[ ! "$FROM_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ || ! "$THROUGH_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo 'Invalid date format' >&2; exit 2
fi
command -v ssh >/dev/null || { echo 'ssh is required' >&2; exit 2; }
command -v scp >/dev/null || { echo 'scp is required' >&2; exit 2; }

# Source address is verified by the remote machine, not an untrusted header.
if ! EXIT_IP="$(ssh "${SSH_OPTS[@]}" "$EC2_SSH" 'curl -4 -fsS --max-time 12 https://api.ipify.org')"; then
  echo 'Unable to SSH to EC2 or check egress. Configure authorized SSH access first.' >&2
  exit 1
fi
case "$EXIT_IP" in
  13.202.233.29) echo "EC2 registered Trading test egress verified: $EXIT_IP" >&2 ;;
  *) echo "STOP: EC2 egress is $EXIT_IP, not a registered Angel One IP." >&2
     echo 'No broker request made. Register actual STATIC egress or configure an authorized gateway first.' >&2
     exit 1;;
esac
REMOTE_DIR="$(ssh "${SSH_OPTS[@]}" "$EC2_SSH" 'umask 077; mktemp -d /tmp/quant-history.XXXXXXXX')"
if [[ ! "$REMOTE_DIR" =~ ^/tmp/quant-history\.[[:alnum:]]{8}$ ]]; then
  echo 'Invalid remote temporary directory; refusing to continue' >&2; exit 1
fi
cleanup() {
  ssh "${SSH_OPTS[@]}" "$EC2_SSH" "rm -f '$REMOTE_DIR/history.csv'; rmdir '$REMOTE_DIR'" >/dev/null 2>&1 || true
}
trap cleanup EXIT
printf 'Starting read-only Angel One export on EC2...\n' >&2
ssh "${SSH_OPTS[@]}" "$EC2_SSH" \
  "python3 - --from-date '$FROM_DATE' --through-date '$THROUGH_DATE' --output '$REMOTE_DIR/history.csv' --credentials-file '/home/ubuntu/.config/quant-engine-test/trading-test.env'" \
  < "$SCRIPT_DIR/ec2_export_angel_history.py"
mkdir -p "$(dirname "$OUT")"
umask 077
scp "${SSH_OPTS[@]}" "$EC2_SSH:$REMOTE_DIR/history.csv" "$OUT"
expected="$(ssh "${SSH_OPTS[@]}" "$EC2_SSH" "sha256sum '$REMOTE_DIR/history.csv' | cut -d ' ' -f1")"
actual="$(shasum -a 256 "$OUT" | awk '{print $1}')"
if [[ "$expected" != "$actual" ]]; then
  rm -f "$OUT"
  echo 'CSV SHA-256 mismatch; deleted local copy' >&2
  exit 1
fi
chmod 600 "$OUT"
printf 'Verified CSV saved to %s\n' "$OUT" >&2
printf 'Run your local backtester with HISTORY_CSV_PATH=%s HISTORY_SYMBOL=NIFTYBEES\n' "$OUT" >&2
