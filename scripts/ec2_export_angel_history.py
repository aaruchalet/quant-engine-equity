#!/usr/bin/env python3
"""Read-only Angel One 5m history export, intended for an authorized EC2 host.

Uses a dedicated private test-app credential file by default, or explicitly selected parent
container config for legacy manual use;
never emits credentials or performs order operations. Requires registered egress IP.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
REGISTERED_EGRESS = frozenset({'13.202.233.29'})
MASTER_URL = 'https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json'
CANDLE_URL = 'https://apiconnect.angelone.in/rest/secure/angelbroking/historical/v1/getCandleData'
BAR_TIMES = tuple((dt.datetime(2026, 1, 1, 9, 15) + dt.timedelta(minutes=5*i)).time() for i in range(69))
# The strategy squares off at the 14:55 candle OPEN; Angel One may still
# return later regular NSE session candles (15:00 through 15:25).
EXTRA_SESSION_TIMES = tuple(
    (dt.datetime(2026, 1, 1, 15, 0) + dt.timedelta(minutes=5*i)).time()
    for i in range(6)
)
ALLOWED_CONFIG = ('ANGEL_ONE_API_KEY', 'ANGEL_ONE_LOCAL_IP', 'ANGEL_ONE_CLIENT_LOCAL_IP',
                  'ANGEL_ONE_PUBLIC_IP', 'ANGEL_ONE_CLIENT_PUBLIC_IP', 'ANGEL_ONE_MAC_ADDRESS',
                  'ANGEL_ONE_SYMBOL_TOKEN', 'ANGEL_ONE_JWT_TOKEN')


class DownloadError(Exception):
    pass


def get_public_ip() -> str:
    req = urllib.request.Request('https://api.ipify.org', headers={'User-Agent': 'quant-engine-test/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=12) as res:
            address = res.read(64).decode('ascii').strip()
        ipaddress.IPv4Address(address)
        return address
    except (OSError, ValueError, UnicodeError) as exc:
        raise DownloadError('Cannot verify actual EC2 outbound IPv4; refusing broker calls') from exc


def verify_egress(actual: str, allowed: frozenset[str] = REGISTERED_EGRESS) -> None:
    if actual not in allowed:
        raise DownloadError('EC2 outbound IPv4 is not one of the registered SmartAPI addresses; '
                            'no request sent to Angel One. Verify registered gateway/static IP first.')


def run_docker(*args: str) -> str:
    try:
        p = subprocess.run(['docker', *args], capture_output=True, text=True, check=False, timeout=15)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DownloadError('Docker access unavailable; run with an EC2 user authorized to inspect quant-engine') from exc
    if p.returncode != 0:
        raise DownloadError('Unable to inspect existing quant-engine container/session (no credentials printed)')
    return p.stdout


def select_container(explicit: str | None) -> str:
    if explicit:
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', explicit):
            raise DownloadError('Invalid Docker container name')
        return explicit
    names = [x.strip() for x in run_docker('ps', '--format', '{{.Names}}').splitlines()]
    matches = [x for x in names if x == 'quant-engine' or x.endswith('-quant-engine-1')]
    if len(matches) != 1:
        raise DownloadError('Find exactly one running quant-engine Docker container; pass --container NAME')
    return matches[0]


def read_docker_config(container: str) -> dict[str, str]:
    raw = run_docker('inspect', '--format', '{{json .Config.Env}}', container)
    try:
        items = json.loads(raw)
        return dict(item.split('=', 1) for item in items if '=' in item)
    except (ValueError, TypeError) as exc:
        raise DownloadError('Unable to read container environment') from exc


def read_runtime_config(path: Path) -> dict[str, str]:
    """Read simple KEY=VALUE lines without evaluating shell or disclosing contents."""
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line.startswith('#') or '=' not in line:
            continue
        key, val = line.split('=', 1)
        key = key.removeprefix('export ').strip()
        if key not in ALLOWED_CONFIG:
            continue
        val = val.strip()
        if len(val) >= 2 and val[0] in ('"', "'") and val[-1] == val[0]:
            val = val[1:-1]
        out[key] = val
    return out


def load_credentials(container: str | None, actual_public_ip: str, runtime_path: Path,
                     credentials_file: Path | None = None) -> tuple[dict[str, str], str]:
    """Separate test-app session prevents accidentally using a production API key/JWT."""
    if credentials_file is not None:
        try:
            info = credentials_file.stat()
        except OSError as exc:
            raise DownloadError('Dedicated Trading test credentials file missing on EC2') from exc
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or (info.st_mode & 0o077):
            raise DownloadError('Dedicated credentials must be owned by the EC2 user and mode 0600')
        env = read_runtime_config(credentials_file)
        container_env: dict[str, str] = {}
    else:
        if not container:
            raise DownloadError('Explicit container required when no dedicated credentials file is provided')
        container_env = read_docker_config(container)
        runtime = read_runtime_config(runtime_path)
        env = {**runtime, **{k:v for k,v in container_env.items() if v}}
    api_key = env.get('ANGEL_ONE_API_KEY', '')
    local_ip = env.get('ANGEL_ONE_LOCAL_IP', env.get('ANGEL_ONE_CLIENT_LOCAL_IP', ''))
    mac = env.get('ANGEL_ONE_MAC_ADDRESS', '')
    if not all([api_key, local_ip, mac]):
        raise DownloadError('Missing existing broker API key/local IP/MAC in EC2 runtime configuration')
    jwt = env.get('ANGEL_ONE_JWT_TOKEN', '')
    if not jwt:
        if credentials_file is not None:
            raise DownloadError('Dedicated Trading test credentials file has no JWT; authenticate this app first')
        cache = container_env.get('ANGEL_ONE_SESSION_CACHE_PATH', '/app/.local/recovery/angel-one-session.cache')
        if not cache.startswith('/app/.local/recovery/'):
            raise DownloadError('Configured broker session cache is outside expected private directory')
        jwt = run_docker('exec', container, 'head', '-n', '1', cache).strip()
    jwt = jwt.removeprefix('Bearer ').strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', jwt):
        raise DownloadError('No usable broker JWT in existing private container session cache; log in to parent first')
    fields = {'Authorization': f'Bearer {jwt}', 'X-PrivateKey': api_key,
              'X-ClientLocalIP': local_ip, 'X-ClientPublicIP': actual_public_ip,
              'X-MACAddress': mac, 'X-UserType': 'USER', 'X-SourceID': 'WEB',
              'Content-Type': 'application/json', 'Accept': 'application/json',
              'User-Agent': 'quant-engine-test-history/1.0'}
    for key, val in fields.items():
        if '\n' in val or '\r' in val:
            raise DownloadError(f'Invalid broker header configuration: {key}')
    return fields, env.get('ANGEL_ONE_SYMBOL_TOKEN', '')


def resolve_token(preset: str) -> str:
    try:
        with urllib.request.urlopen(MASTER_URL, timeout=45) as response:
            master = json.load(response)
    except (OSError, ValueError) as exc:
        raise DownloadError('Unable to read Angel One instrument master; no candle request sent') from exc
    matches = [item for item in master if item.get('exch_seg') == 'NSE'
               and item.get('name') == 'NIFTYBEES' and item.get('symbol') == 'NIFTYBEES-EQ'
               and str(item.get('token', '')).isdigit()]
    if len(matches) != 1:
        raise DownloadError('Expected one NSE NIFTYBEES-EQ entry in current instrument master')
    token = str(matches[0]['token'])
    if preset and preset != token:
        raise DownloadError('Existing ANGEL_ONE_SYMBOL_TOKEN differs from verified NSE NIFTYBEES token')
    return token


def parse_timestamp(value: str) -> dt.datetime:
    raw = value.replace('Z', '+00:00').replace(' ', 'T')
    stamp = dt.datetime.fromisoformat(raw)
    return stamp.astimezone(IST).replace(tzinfo=None) if stamp.tzinfo else stamp


def validate_row(row: list[object]) -> tuple[dt.datetime, tuple[str, ...]]:
    if not isinstance(row, list) or len(row) < 6:
        raise DownloadError('Vendor returned malformed OHLCV row')
    try:
        stamp = parse_timestamp(str(row[0]))
        if stamp.time() not in BAR_TIMES:
            raise DownloadError(f'Vendor returned unexpected candle at {stamp:%H:%M:%S} IST; expected a 5-minute candle from 09:15 to 14:55')
        vals = [float(x) for x in row[1:6]]
        if any(not (float('-inf') < v < float('inf')) for v in vals):
            raise ValueError('non-finite OHLCV')
        o,h,l,c,v = vals
        if min(o,h,l,c) <= 0 or v < 0 or h < max(o,c) or l > min(o,c):
            raise ValueError('invalid OHLCV')
    except (TypeError, ValueError, OverflowError) as exc:
        raise DownloadError('Vendor returned invalid candle values') from exc
    return stamp, tuple(str(x) for x in row[1:6])


def fetch_batch(token: str, headers: dict[str, str], start: dt.date, through: dt.date) -> list[list[object]]:
    payload = json.dumps({'exchange':'NSE','symboltoken':token,'interval':'FIVE_MINUTE',
                          'fromdate':f'{start:%Y-%m-%d} 09:15','todate':f'{through:%Y-%m-%d} 14:55'})
    req = urllib.request.Request(CANDLE_URL, data=payload.encode('utf-8'), headers=headers, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=35) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        # Interpret only known error signatures. Never echo arbitrary upstream
        # response text: broker replies must not leak sensitive information.
        if exc.code == 403:
            try:
                error_sample = exc.read(512).decode('utf-8', errors='replace').lower()
            except OSError:
                error_sample = ''
            if 'exceeding access rate' in error_sample or 'rate limit' in error_sample:
                raise DownloadError('Broker HTTP 403: historical API access-rate restriction. '
                                    'No automatic retry; pause and contact Angel One if it persists') from exc
            raise DownloadError('Broker HTTP 403 Forbidden: historical API request blocked; '
                                'check broker permissions, session, IP and account limits. '
                                'No automatic retry') from exc
        if exc.code == 429:
            raise DownloadError('Broker HTTP 429 Too Many Requests; no automatic retry') from exc
        raise DownloadError(f'Broker rejected history request: HTTP {exc.code} '
                            '(check JWT, permissions, egress)') from exc
    except (OSError, ValueError) as exc:
        raise DownloadError('Broker historical API unavailable or returned invalid JSON') from exc
    if not isinstance(result, dict) or result.get('status') is not True or not isinstance(result.get('data'), list):
        code = str(result.get('errorcode', 'unknown')) if isinstance(result, dict) else 'unknown'
        code = code if re.fullmatch(r'[A-Za-z0-9_-]{1,25}', code) else 'unknown'
        if code == 'AB1021':
            raise DownloadError('Broker AB1021 Too many requests: Angel One historical API '
                                'access-rate restriction. Stop this run; checkpoints are preserved. '
                                'Contact SmartAPI support if it persists')
        raise DownloadError(f'Broker declined historical request (error code {code})')
    return result['data']


def checkpoint_path(cache_dir: Path, token: str,
                    start: dt.date, through: dt.date) -> Path:
    if not re.fullmatch(r'[0-9]{1,20}', token):
        raise DownloadError('Invalid NSE instrument token for historical checkpoint')
    return cache_dir / f'NSE-NIFTYBEES-FIVE_MINUTE-{token}-{start:%Y%m%d}-{through:%Y%m%d}.json'


def read_checkpoint(path: Path, token: str, start: dt.date,
                    through: dt.date) -> dict[dt.datetime, tuple[str, ...]] | None:
    if not path.exists():
        return None
    if path.is_symlink() or not path.is_file():
        raise DownloadError('Unexpected checkpoint file type; refusing untrusted history')
    try:
        with path.open('r', encoding='utf-8') as file:
            payload = json.load(file)
        if (not isinstance(payload, dict) or payload.get('schema') != 1 or
            payload.get('token') != token or payload.get('start') != str(start) or
            payload.get('through') != str(through) or
            not isinstance(payload.get('rows'), list)):
            raise ValueError('checkpoint metadata mismatch')
        result: dict[dt.datetime, tuple[str, ...]] = {}
        for row in payload['rows']:
            stamp, values = validate_row(row)
            if not (start <= stamp.date() <= through):
                raise ValueError('checkpoint date outside window')
            if stamp in result and result[stamp] != values:
                raise ValueError('checkpoint contains conflicting bars')
            result[stamp] = values
        return result
    except (ValueError, TypeError, OSError, DownloadError) as exc:
        raise DownloadError(f'History checkpoint for {start} to {through} is invalid; '
                            'review or remove just that cache file before retrying') from exc


def write_checkpoint(path: Path, token: str, start: dt.date,
                     through: dt.date,
                     rows: dict[dt.datetime, tuple[str, ...]]) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink() or path.parent.stat().st_uid != os.geteuid():
        raise DownloadError('History cache directory owner/type is not trusted')
    path.parent.chmod(0o700)
    if path.is_symlink():
        raise DownloadError('History checkpoint symlink is not allowed')
    payload = {
        'schema': 1, 'token': token, 'start': str(start), 'through': str(through),
        'rows': [[stamp.isoformat(timespec='seconds'), *values]
                 for stamp, values in sorted(rows.items())]
    }
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='\n',
                                         prefix='.history-window-', suffix='.tmp',
                                         dir=path.parent, delete=False) as file:
            temp_path = Path(file.name)
            os.chmod(temp_path, 0o600)
            json.dump(payload, file, separators=(',', ':'))
        os.replace(temp_path, path)
    finally:
        if temp_path:
            temp_path.unlink(missing_ok=True)


def collect_history(start: dt.date, through: dt.date, token: str, headers: dict[str, str],
                    batch_days: int = 14, pause: float = 10.0,
                    fetcher=fetch_batch, cache_dir: Path | None = None
                    ) -> dict[dt.datetime, tuple[str, ...]]:
    if not 1 <= batch_days <= 30 or pause < 0:
        raise DownloadError('Invalid history batch size or request interval')
    candles: dict[dt.datetime, tuple[str, ...]] = {}
    cursor = start - dt.timedelta(days=30)
    iteration = 0
    last_fetch_at: float | None = None
    while cursor <= through:
        end = min(through, cursor + dt.timedelta(days=batch_days - 1))
        iteration += 1
        cache_path = checkpoint_path(cache_dir, token, cursor, end) if cache_dir else None
        window = read_checkpoint(cache_path, token, cursor, end) if cache_path else None
        if window is None:
            if last_fetch_at is not None:
                remaining = pause - (time.monotonic() - last_fetch_at)
                if remaining > 0:
                    time.sleep(remaining)
            # One request only. A 403/429 stops this run; never bypass broker limits.
            last_fetch_at = time.monotonic()
            try:
                vendor_rows = fetcher(token, headers, cursor, end)
            except DownloadError as exc:
                raise DownloadError(f'Window {cursor} to {end}: {exc}. '
                                    'Previously completed windows are cached when enabled') from exc
            window = {}
            ignored_late = 0
            for row in vendor_rows:
                # Some broker responses include later regular NSE session candles.
                if not isinstance(row, list) or len(row) < 6:
                    raise DownloadError('Vendor returned malformed OHLCV row')
                try:
                    row_timestamp = parse_timestamp(str(row[0]))
                except (ValueError, TypeError, OverflowError) as exc:
                    raise DownloadError('Vendor returned invalid candle timestamp') from exc
                if row_timestamp.time() in EXTRA_SESSION_TIMES:
                    ignored_late += 1
                    continue
                stamp, values = validate_row(row)
                if cursor <= stamp.date() <= end:
                    if stamp in window and window[stamp] != values:
                        raise DownloadError('Conflicting duplicate historical candle')
                    window[stamp] = values
            if cache_path:
                write_checkpoint(cache_path, token, cursor, end, window)
            print(f'Fetched window {iteration}: {cursor} to {end}' +
                  (f' (ignored {ignored_late} post-14:55 NSE candles)' if ignored_late else '') +
                  (' [checkpoint saved]' if cache_path else ''), file=sys.stderr)
        else:
            print(f'Cached window {iteration}: {cursor} to {end} '
                  f'({len(window)} validated candles)', file=sys.stderr)
        for stamp, values in window.items():
            if stamp in candles and candles[stamp] != values:
                raise DownloadError('Conflicting duplicate historical candle')
            candles[stamp] = values
        cursor = end + dt.timedelta(days=1)
    return candles


def summarize_complete(candles: dict[dt.datetime, tuple[str, ...]],
                       start: dt.date, through: dt.date) -> tuple[list[tuple[dt.datetime, tuple[str, ...]]], list[str], int]:
    by_day: dict[dt.date, dict[dt.time, tuple[str, ...]]] = {}
    for stamp, vals in candles.items():
        by_day.setdefault(stamp.date(), {})[stamp.time()] = vals
    valid_dates: set[dt.date] = set()
    skipped: list[str] = []
    for date, bars in sorted(by_day.items()):
        if any(t not in bars for t in BAR_TIMES):
            skipped.append(f'{date}: missing 5-minute bars')
            continue
        if sum(float(bars[t][4]) for t in BAR_TIMES) <= 0:
            skipped.append(f'{date}: no real trading volume')
            continue
        valid_dates.add(date)
    tested = [d for d in valid_dates if start <= d <= through]
    if len(tested) < 5:
        raise DownloadError('Insufficient complete sessions for requested six-month backtest')
    if min(tested) > start + dt.timedelta(days=10) or max(tested) < through - dt.timedelta(days=10):
        raise DownloadError('Historical feed does not cover both ends of requested date range')
    expected_weekdays = sum((start + dt.timedelta(days=i)).weekday() < 5 for i in range((through-start).days+1))
    if len(tested) < expected_weekdays * 0.75:
        raise DownloadError('Too many entirely missing sessions; do not treat incomplete history as six months')
    return sorted([(d,v) for d,v in candles.items() if d.date() in valid_dates]), skipped, len(tested)


def write_csv(output: Path, records: list[tuple[dt.datetime, tuple[str, ...]]]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='',
                                     prefix='.history-', suffix='.tmp',
                                     dir=output.parent, delete=False) as temp:
        tmp_path = Path(temp.name)
        os.chmod(tmp_path, 0o600)
        try:
            writer = csv.writer(temp, lineterminator='\n')
            writer.writerow(['timestamp','open','high','low','close','volume'])
            for stamp, values in records:
                writer.writerow([stamp.isoformat(timespec='seconds'), *values])
        except BaseException:
            tmp_path.unlink(missing_ok=True)
            raise
    os.replace(tmp_path, output)


def main() -> int:
    p = argparse.ArgumentParser(description='Read-only, verified-egress NIFTYBEES SmartAPI historical export')
    p.add_argument('--from-date', default='2026-05-20')
    p.add_argument('--through-date', default='2026-11-19')
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--container', help='Existing quant-engine Docker container name')
    p.add_argument('--runtime-env', type=Path, default=Path('/opt/aaru/deploy/runtime.env'))
    p.add_argument('--credentials-file', type=Path,
                   help='Private mode-0600 test-app env file; avoids reading production broker config')
    p.add_argument('--cache-dir', type=Path,
                   default=Path.home() / '.cache' / 'quant-engine-test' / 'angel-one-history',
                   help='Private persistent, resumable market-data checkpoints on EC2')
    p.add_argument('--request-interval', type=float, default=10.0,
                   help='Minimum seconds between new historical API requests; default 10')
    args = p.parse_args()
    try:
        start, through = dt.date.fromisoformat(args.from_date), dt.date.fromisoformat(args.through_date)
        if start > through or (through - start).days > 370 or through >= dt.datetime.now(IST).date():
            raise DownloadError('Invalid historical range; use completed dates and <=12 months')
        actual = get_public_ip()
        verify_egress(actual)  # Must happen before ANY access to broker credentials.
        print(f'Verified registered EC2 outbound IP: {actual}', file=sys.stderr)
        container = None if args.credentials_file else select_container(args.container)
        headers, preset = load_credentials(container, actual, args.runtime_env,
                                           credentials_file=args.credentials_file)
        token = resolve_token(preset)
        candles = collect_history(start, through, token, headers,
                                  pause=args.request_interval, cache_dir=args.cache_dir)
        usable, skipped, sessions = summarize_complete(candles, start, through)
        write_csv(args.output, usable)
        print(f'Exported {len(usable)} validated candles across {sessions} requested-range sessions; '
              f'{len(skipped)} partial/zero-volume sessions excluded', file=sys.stderr)
        if skipped:
            print('Excluded sessions: '+', '.join(skipped[:25]), file=sys.stderr)
        print('Note: fully absent dates cannot be classified as holidays without an exchange calendar.', file=sys.stderr)
        return 0
    except DownloadError as exc:
        print(f'History export blocked: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
