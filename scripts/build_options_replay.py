"""Join local ETF bars and timestamped option-chain JSON lines into a replay request."""
import argparse
import csv
import json
from datetime import datetime


def sessions(markets, index_candles=()):
    days = {}
    for index, symbol, candle_path, chain_path in markets:
        if index not in ("NIFTY", "SENSEX"):
            raise ValueError("Expected NIFTY or SENSEX")
        quotes = {}
        with open(chain_path, encoding="utf-8") as source:
            for line in source:
                chain = json.loads(line)
                at = datetime.fromisoformat(chain["asOf"])
                if at in quotes or chain["index"] != index or at.tzinfo is not None:
                    raise ValueError("Duplicate, nonlocal or wrong-index chain")
                quotes[at] = chain
        with open(candle_path, encoding="utf-8-sig", newline="") as source:
            for bar in csv.DictReader(source):
                at = datetime.fromisoformat(bar["timestamp"])
                if at.tzinfo is not None:
                    raise ValueError("Expected exchange-local IST timestamps without offset")
                series = days.setdefault(at.date().isoformat(), {}).setdefault(index, dict(signalSymbol=symbol, frames=[]))
                if series["signalSymbol"] != symbol:
                    raise ValueError("Mixed signal symbols for one index")
                candle = dict(openTime=at.isoformat(), **{k: float(bar[k]) for k in ("open", "high", "low", "close", "volume")})
                series["frames"].append(dict(candle=candle, chain=quotes.pop(at, None)))
        if quotes:
            raise ValueError("A chain snapshot has no matching candle open")
    for index, index_path in index_candles:
        bars = {}
        with open(index_path, encoding="utf-8-sig", newline="") as source:
            for bar in csv.DictReader(source):
                at = datetime.fromisoformat(bar["timestamp"]).isoformat()
                if at in bars:
                    raise ValueError("Duplicate index candle")
                bars[at] = dict(openTime=at, **{k: float(bar[k]) for k in ("open", "high", "low", "close")}, volume=0)
        for markets_on_day in days.values():
            if index in markets_on_day:
                for frame in markets_on_day[index]["frames"]:
                    frame["indexCandle"] = bars.pop(frame["candle"]["openTime"])
        if bars:
            raise ValueError("Index candles without matching proxy bars")
    for markets_on_day in days.values():
        for series in markets_on_day.values():
            series["frames"].sort(key=lambda frame: frame["candle"]["openTime"])
    return dict(sessions=[dict(date=date, markets=days[date]) for date in sorted(days)])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--market", required=True, action="append", nargs=4,
                        metavar=("INDEX", "SIGNAL_SYMBOL", "CANDLES_CSV", "CHAINS_JSONL"))
    parser.add_argument("--index-candles", action="append", nargs=2, default=[],
                        metavar=("INDEX", "SPOT_CANDLES_CSV"))
    args = parser.parse_args()
    print(json.dumps(sessions(args.market, args.index_candles), allow_nan=False))
