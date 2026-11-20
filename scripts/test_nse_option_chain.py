import io
import unittest
from nse_option_chain import convert


class NseOptionChainTest(unittest.TestCase):
    def test_native_header_keeps_strike_and_sides(self):
        csv = ('CALLS,,PUTS\nBid Qty,Bid,Ask,Ask Qty,Strike,Bid Qty,Bid,Ask,Ask Qty\n'
               '2,101.05,101.10,3,"25,000.00",0,-,55.20,1\n')
        row, = convert(io.StringIO(csv), "lots", 65)
        self.assertEqual("25000.00", row["strike"])
        self.assertEqual(195, row["ce"]["askQuantity"])
        self.assertEqual("55.20", row["pe"]["ask"])
        self.assertIsNone(row["pe"]["bid"])
        self.assertEqual(3, convert(io.StringIO(csv), "units", 65)[0]["ce"]["askQuantity"])

    def test_rejects_missing_tradeable_side_fields(self):
        with self.assertRaises(ValueError):
            convert(io.StringIO("BID,ASK,STRIKE,BID,ASK\n"), "units", 65)


class ReplayDataTest(unittest.TestCase):
    def test_pairs_by_actual_timestamp_and_rejects_orphan_quotes(self):
        import json
        import tempfile
        from pathlib import Path
        from build_options_replay import sessions

        with tempfile.TemporaryDirectory() as tmp:
            candles = Path(tmp) / "bars.csv"
            chains = Path(tmp) / "chains.jsonl"
            candles.write_text("timestamp,open,high,low,close,volume\n2026-11-19T09:15,100,101,99,100,10\n")
            market = [("NIFTY", "NIFTYBEES", candles, chains)]
            chain = dict(index="NIFTY", asOf="2026-11-19T09:15:00")
            chains.write_text(json.dumps(chain))
            data = sessions(market)["sessions"][0]["markets"]["NIFTY"]["frames"][0]
            self.assertEqual(chain, data["chain"])
            chains.write_text(json.dumps(dict(chain, asOf="2026-11-19T09:16:00")))
            with self.assertRaises(ValueError):
                sessions(market)
