import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

PATH = Path(__file__).with_name('ec2_export_angel_history.py')
SPEC = importlib.util.spec_from_file_location('history_export', PATH)
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


def fake_day(date, price=100, volume=100):
    return [
        [f'{date}T{time.isoformat()}+05:30', price, price + 1, price - 1, price + 0.2, volume]
        for time in mod.BAR_TIMES
    ]


class HistoryExporterTests(unittest.TestCase):
    def test_egress_denies_unregistered(self):
        with self.assertRaisesRegex(mod.DownloadError, 'not one of the registered'):
            mod.verify_egress('182.77.77.184')
        with self.assertRaises(mod.DownloadError):
            mod.verify_egress('92.62.121.100')
        mod.verify_egress('13.202.233.29')

    def test_candle_timestamp_normalization(self):
        timestamp, values = mod.validate_row(['2026-05-20T09:15:00+05:30',100,101,99,100.5,123])
        self.assertEqual(timestamp, dt.datetime(2026,5,20,9,15))
        self.assertEqual(len(values),5)
        utc, _ = mod.validate_row(['2026-05-20T03:45:00+00:00',100,101,99,100.5,123])
        self.assertEqual(utc, timestamp)

    def test_late_regular_session_candles_do_not_abort_history(self):
        """Angel One may return bars through 15:25 even if we need only 14:55."""
        day = dt.date(2026, 5, 20)
        extras = [
            [f'{day}T15:00:00+05:30', 100, 101, 99, 100.5, 123],
            [f'{day}T15:25:00+05:30', 100, 101, 99, 100.5, 123],
        ]
        with mock.patch.object(mod.time, 'sleep'):
            bars = mod.collect_history(day, day, 'token', {},
                 fetcher=lambda _tok, _hdr, _start, _end: fake_day(day) + extras)
        self.assertEqual(len(bars), 69)
        self.assertIn(dt.datetime(2026, 5, 20, 14, 55), bars)
        self.assertNotIn(dt.datetime(2026, 5, 20, 15, 0), bars)
        self.assertNotIn(dt.datetime(2026, 5, 20, 15, 25), bars)

    def test_broker_request_ends_at_strategy_cutoff(self):
        import io
        import json
        response = io.BytesIO(b'{"status":true,"data":[]}')
        with mock.patch.object(mod.urllib.request, 'urlopen', return_value=response) as call:
            result = mod.fetch_batch('123', {}, dt.date(2026,5,20), dt.date(2026,5,20))
            self.assertEqual(result, [])
            payload = json.loads(call.call_args.args[0].data)
            self.assertEqual(payload['todate'], '2026-05-20 14:55')

    def test_unexpected_session_times_still_rejected(self):
        for when in ('09:17:00', '15:30:00', '08:45:00'):
            with self.subTest(time=when):
                bad = [[f'2026-05-20T{when}+05:30', 100, 101, 99, 100.5, 123]]
                with self.assertRaisesRegex(mod.DownloadError, 'unexpected candle'):
                    mod.collect_history(dt.date(2026,5,20), dt.date(2026,5,20),
                                        'token', {}, fetcher=lambda *args: bad)

    def test_invalid_prices_and_time(self):
        with self.assertRaises(mod.DownloadError):
            mod.validate_row(['2026-05-20T09:17:00+05:30',100,101,99,100.5,123])
        with self.assertRaises(mod.DownloadError):
            mod.validate_row(['2026-05-20T09:15:00+05:30',100,99,99,100.5,123])
        with self.assertRaises(mod.DownloadError):
            mod.validate_row(['2026-05-20T09:15:00+05:30',100,101,99,100.5,0/0 if False else float('nan')])

    def test_complete_sessions_and_csv(self):
        by_time = {}
        for offset in range(20):
            day = dt.date(2026,5,20) + dt.timedelta(days=offset)
            if day.weekday() >= 5:
                continue
            for row in fake_day(day):
                stamp, values = mod.validate_row(row)
                by_time[stamp] = values
        records, skipped, sessions = mod.summarize_complete(by_time, dt.date(2026,5,20), dt.date(2026,6,8))
        self.assertEqual(len(skipped),0)
        self.assertEqual(len(records), sessions*69)
        with tempfile.TemporaryDirectory() as td:
            out = Path(td)/'candles.csv'
            mod.write_csv(out,records)
            content = out.read_text()
            self.assertTrue(content.startswith('timestamp,open,high,low,close,volume\n'))
            self.assertEqual(len(content.strip().splitlines()), 1+len(records))

    def test_incomplete_session_excluded(self):
        by_time={}
        for offset in range(22):
            day=dt.date(2026,5,20)+dt.timedelta(days=offset)
            if day.weekday()>=5:continue
            rows=fake_day(day)
            if day==dt.date(2026,5,21):rows=rows[:-1]
            for row in rows:
                stamp,values=mod.validate_row(row)
                by_time[stamp]=values
        records, skipped, sessions=mod.summarize_complete(by_time,dt.date(2026,5,20),dt.date(2026,6,10))
        self.assertEqual(len(skipped),1)
        self.assertNotIn(dt.datetime(2026,5,21,9,15),dict(records))

    def test_missing_coverage_rejected(self):
        by_time={}
        for offset in range(15):
            date=dt.date(2026,5,20)+dt.timedelta(days=offset)
            if date.weekday()>4:continue
            for row in fake_day(date):
                timestamp,values=mod.validate_row(row)
                by_time[timestamp]=values
        with self.assertRaisesRegex(mod.DownloadError, 'does not cover both ends'):
            mod.summarize_complete(by_time,dt.date(2026,5,20),dt.date(2026,8,20))

    def test_resume_after_http_403_without_refetching_completed_windows(self):
        start = dt.date(2026, 5, 20)
        through = dt.date(2026, 5, 31)
        first_calls = []
        second_calls = []
        def rows_for_window(window_start, window_end):
            rows=[]
            day=window_start
            while day<=window_end:
                if day.weekday()<5:
                    rows.extend(fake_day(day))
                day+=dt.timedelta(days=1)
            return rows
        def first_fetch(_token, _headers, window_start, window_end):
            first_calls.append(window_start)
            if window_start >= dt.date(2026,5,18):
                raise mod.DownloadError('Broker HTTP 403 Forbidden')
            return rows_for_window(window_start, window_end)
        with tempfile.TemporaryDirectory() as directory:
            cache=Path(directory)
            with self.assertRaisesRegex(mod.DownloadError, 'Window 2026-05-18 to 2026-05-31'):
                mod.collect_history(start, through, '123', {}, pause=0,
                                    fetcher=first_fetch, cache_dir=cache)
            self.assertEqual(len(first_calls), 3)
            self.assertEqual(len(list(cache.glob('*.json'))), 2)
            def second_fetch(_token, _headers, window_start, window_end):
                second_calls.append(window_start)
                return rows_for_window(window_start, window_end)
            out=mod.collect_history(start, through, '123', {}, pause=0,
                                    fetcher=second_fetch, cache_dir=cache)
            self.assertEqual(second_calls, [dt.date(2026,5,18)])
            self.assertIn(dt.datetime(2026,5,20,9,15), out)
            self.assertEqual(len(list(cache.glob('*.json'))), 3)

    def test_corrupt_window_cache_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            cache=Path(directory)
            path=mod.checkpoint_path(cache, '123', dt.date(2026,4,20), dt.date(2026,5,3))
            path.write_text('{"schema":1,"token":"other","start":"2026-04-20"}')
            with self.assertRaisesRegex(mod.DownloadError, 'checkpoint'):
                mod.collect_history(dt.date(2026,5,20), dt.date(2026,5,20),
                                    '123', {}, pause=0, cache_dir=cache)

    def test_ab1021_is_explained_as_broker_rate_limit(self):
        import io
        payload=b'{"status":false,"message":"Too many requests","errorcode":"AB1021","data":null}'
        with mock.patch.object(mod.urllib.request, 'urlopen', return_value=io.BytesIO(payload)):
            with self.assertRaisesRegex(mod.DownloadError, 'AB1021 Too many requests'):
                mod.fetch_batch('123', {}, dt.date(2026,5,20), dt.date(2026,5,20))

    def test_rate_limit_403_uses_safe_classification_not_raw_body(self):
        import io
        import urllib.error
        message=b'Access denied because of exceeding access rate - sensitive-debug-string'
        error=urllib.error.HTTPError('https://apiconnect.angelone.in',403,
                                   'Forbidden',{},io.BytesIO(message))
        with mock.patch.object(mod.urllib.request,'urlopen',side_effect=error):
            with self.assertRaises(mod.DownloadError) as caught:
                mod.fetch_batch('123', {}, dt.date(2026,5,20), dt.date(2026,5,20))
        self.assertIn('access-rate restriction',str(caught.exception))
        self.assertNotIn('sensitive-debug-string',str(caught.exception))

    def test_v2_1500_uses_distinct_cache_and_retains_real_last_open(self):
        start = dt.date(2026, 5, 20)
        old = mod.checkpoint_path(Path('/tmp/history'), '123', start, start)
        new = mod.checkpoint_path(Path('/tmp/history'), '123', start, start, dt.time(15, 0))
        self.assertNotEqual(old, new)
        self.assertTrue(new.name.endswith('-1500.json'))
        rows = fake_day(start) + [
            [f'{start}T15:00:00+05:30', 100, 101, 99, 100.5, 123],
            [f'{start}T15:05:00+05:30', 100, 101, 99, 100.5, 123]
        ]
        out = mod.collect_history(start, start, '123', {}, pause=0,
                                  last_open=dt.time(15, 0), fetcher=lambda *args: rows)
        self.assertEqual(len(out), 70)
        self.assertIn(dt.datetime(2026,5,20,15,0),out)
        self.assertNotIn(dt.datetime(2026,5,20,15,5),out)
        with self.assertRaises(mod.DownloadError):
            mod.validate_row(rows[-1], dt.time(15, 0))

    def test_v2_broker_request_end_at_1500(self):
        import io
        import json
        with mock.patch.object(mod.urllib.request, 'urlopen',
                               return_value=io.BytesIO(b'{"status":true,"data":[]}')) as call:
            mod.fetch_batch('123', {}, dt.date(2026,5,20),
                            dt.date(2026,5,20), dt.time(15,0))
            self.assertEqual(json.loads(call.call_args.args[0].data)['todate'],
                             '2026-05-20 15:00')

    def test_runtime_env_only_whitelisted_no_execute(self):
        with tempfile.TemporaryDirectory() as td:
            file=Path(td)/'runtime.env'
            file.write_text('ANGEL_ONE_API_KEY="abc123"\nDATABASE_PASSWORD=hidden\nANGEL_ONE_MAC_ADDRESS=a:b:c\n# comment\n')
            items=mod.read_runtime_config(file)
            self.assertEqual(items['ANGEL_ONE_API_KEY'],'abc123')
            self.assertNotIn('DATABASE_PASSWORD',items)

    def test_dedicated_credentials_are_isolated_and_owner_only(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'trading-test.env'
            path.write_text(
                'ANGEL_ONE_API_KEY=app-specific-key\n'
                'ANGEL_ONE_LOCAL_IP=172.31.2.40\n'
                'ANGEL_ONE_MAC_ADDRESS=00:11:22:33:44:55\n'
                'ANGEL_ONE_JWT_TOKEN=abc.def.ghi\n'
            )
            path.chmod(0o600)
            headers, token=mod.load_credentials('13.202.233.29', path)
            self.assertEqual(headers['X-PrivateKey'],'app-specific-key')
            self.assertEqual(headers['X-ClientPublicIP'],'13.202.233.29')
            self.assertEqual(headers['Authorization'],'Bearer abc.def.ghi')
            self.assertEqual(token,'')
            path.chmod(0o644)
            with self.assertRaisesRegex(mod.DownloadError,'mode 0600'):
                mod.load_credentials('13.202.233.29', path)
            path.chmod(0o600)
            path.unlink()
            with self.assertRaisesRegex(mod.DownloadError,'file missing'):
                mod.load_credentials('13.202.233.29', path)


if __name__=='__main__':unittest.main()
