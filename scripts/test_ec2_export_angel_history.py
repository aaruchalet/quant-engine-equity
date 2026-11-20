import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest

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
            headers, token=mod.load_credentials(None,'13.202.233.29', Path('/does/not/exist'), credentials_file=path)
            self.assertEqual(headers['X-PrivateKey'],'app-specific-key')
            self.assertEqual(headers['X-ClientPublicIP'],'13.202.233.29')
            self.assertEqual(headers['Authorization'],'Bearer abc.def.ghi')
            self.assertEqual(token,'')
            path.chmod(0o644)
            with self.assertRaisesRegex(mod.DownloadError,'mode 0600'):
                mod.load_credentials(None,'13.202.233.29', Path('/does/not/exist'), credentials_file=path)
            path.chmod(0o600)
            path.unlink()
            with self.assertRaisesRegex(mod.DownloadError,'file missing'):
                mod.load_credentials(None,'13.202.233.29', Path('/does/not/exist'), credentials_file=path)


if __name__=='__main__':unittest.main()
