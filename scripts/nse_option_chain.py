"""Convert a user-downloaded NSE NIFTY single-expiry CSV to one options replay chain."""
import argparse
import csv
import json
from decimal import Decimal


def value(raw):
    raw = raw.strip().replace(",", "")
    if raw in ("", "-"):
        return None
    number = Decimal(raw)
    if not number.is_finite() or number < 0:
        raise ValueError("Expected a finite non-negative number")
    return number


def convert(stream, depth_unit, lot_size):
    rows = csv.reader(stream)
    for row in rows:
        header = [field.strip().upper() for field in row]
        if "STRIKE" in header:
            break
    else:
        raise ValueError("Missing NSE STRIKE header")
    strike = header.index("STRIKE")
    columns = []
    for start, end in ((0, strike), (strike + 1, len(header))):
        columns.append([header.index(name, start, end) for name in ("BID", "BID QTY", "ASK", "ASK QTY")])

    def book(row, offsets):
        result = {}
        for side, price_col, size_col in (("bid", *offsets[:2]), ("ask", *offsets[2:])):
            price, size = value(row[price_col]), value(row[size_col]) or 0
            if int(size) != size or bool(price) != bool(size):
                raise ValueError("Unpaired NSE book price/quantity or fractional quantity")
            result[side] = str(price) if price else None
            result[side + "Quantity"] = int(size) * (lot_size if depth_unit == "lots" else 1)
        return result

    strikes = []
    for row in rows:
        if not any(field.strip() for field in row):
            continue
        if row[strike].strip().upper() == "TOTAL":
            continue
        price = value(row[strike])
        if not price:
            # NSE sometimes appends an OI/volume totals row without a strike.
            if any(field.strip().upper() == "TOT" or field.strip().upper() == "TOTAL" for field in row):
                continue
            raise ValueError("Missing listed strike")
        strikes.append(dict(strike=str(price), ce=book(row, columns[0]), pe=book(row, columns[1])))
    if not strikes:
        raise ValueError("Empty NSE option chain")
    return strikes


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file")
    parser.add_argument("--spot", required=True)
    parser.add_argument("--as-of", required=True, help="Actual snapshot timestamp in IST")
    parser.add_argument("--expiry", required=True)
    parser.add_argument("--lot-size", required=True, type=int, help="From the dated contract master")
    parser.add_argument("--depth-unit", required=True, choices=["lots", "units"])
    args = parser.parse_args()
    with open(args.csv_file, encoding="utf-8-sig", newline="") as source:
        strikes = convert(source, args.depth_unit, args.lot_size)
    print(json.dumps(dict(index="NIFTY", asOf=args.as_of, expiry=args.expiry,
                          lotSize=args.lot_size, spot=args.spot, strikes=strikes)))
