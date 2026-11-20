package com.aaruchalet.quant.backtest;

import static com.aaruchalet.quant.backtest.BacktestReport.ExitReason;
import static com.aaruchalet.quant.backtest.BacktestReport.Side;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Isolated historical replay. All fills use the selected option's observed book at bar open. */
public final class IndexOptionsReplay {
    public enum Index {
        NIFTY(65), SENSEX(20);
        final int lotSize;
        Index(int lotSize) { this.lotSize = lotSize; }
    }
    public enum Right { CE, PE }
    public record Book(BigDecimal bid, long bidQuantity, BigDecimal ask, long askQuantity) {
        public Book {
            if (!validLevel(bid, bidQuantity) || !validLevel(ask, askQuantity)
                    || bidQuantity > 0 && askQuantity > 0 && bid.compareTo(ask) > 0)
                throw new IllegalArgumentException("Invalid option bid/ask or depth");
        }
        private static boolean validLevel(BigDecimal price, long size) {
            return size == 0 && (price == null || price.signum() == 0)
                    || size > 0 && price != null && price.signum() > 0 && price.stripTrailingZeros().scale() <= 2;
        }
    }
    public record Strike(BigDecimal strike, Book ce, Book pe) {
        Book book(Right right) { return right == Right.CE ? ce : pe; }
    }
    public record Chain(Index index, LocalDateTime asOf, LocalDate expiry, int lotSize,
                        BigDecimal spot, List<Strike> strikes) {
        public Chain {
            if (index == null || asOf == null || expiry == null || expiry.isBefore(asOf.toLocalDate())
                    || lotSize != index.lotSize || spot == null || spot.signum() <= 0 || strikes == null || strikes.isEmpty())
                throw new IllegalArgumentException("Invalid option-chain identity, lot size, spot, expiry or strikes");
            var ordered = new TreeMap<BigDecimal, Strike>();
            for (var row : strikes)
                if (row == null || row.strike == null || row.strike.signum() <= 0 || ordered.put(row.strike, row) != null)
                    throw new IllegalArgumentException("Invalid or duplicate option strike");
            strikes = List.copyOf(ordered.values());
        }
        Strike select(int offset) {
            if (spot.compareTo(strikes.getFirst().strike) < 0 || spot.compareTo(strikes.getLast().strike) > 0)
                return null;
            int atm = 0;
            for (int i = 1; i < strikes.size(); i++) {
                if (strikes.get(i).strike.subtract(spot).abs()
                        .compareTo(strikes.get(atm).strike.subtract(spot).abs()) >= 0) break;
                atm = i;
            }
            long selection = (long) atm + offset;
            return selection < 0 || selection >= strikes.size() ? null : strikes.get((int) selection);
        }
    }
    public record Frame(Candle candle, Chain chain) {}
    public record Series(String signalSymbol, List<Frame> frames) {}
    public record Session(LocalDate date, Map<Index, Series> markets) {}
    public record Request(List<Session> sessions) {}
    record Position(Index index, Right right, LocalDate expiry, BigDecimal strike, LocalDateTime entryAt,
                    int lots, long quantity, BigDecimal entryPremium, BigDecimal entryFee,
                    BigDecimal stop, BigDecimal target) {}
    record Trade(Position position, LocalDateTime exitAt, BigDecimal exitPremium, ExitReason reason,
                 BigDecimal exitFee, BigDecimal netPnl) {}
    record Skip(LocalDateTime at, Index index, String reason) {}
    record Day(LocalDate date, BigDecimal entrySpend, BigDecimal remainingBudget, BigDecimal netPnl) {}
    record Report(List<Trade> trades, List<Skip> skipped, List<Day> days) {}

    private final IndexOptionsConfig options;
    private final VwapEmaV2Config strategy;

    IndexOptionsReplay(IndexOptionsConfig options, VwapEmaV2Config strategy) {
        this.options = options;
        this.strategy = strategy;
    }

    Report run(Request request) {
        if (request == null || request.sessions == null || request.sessions.isEmpty())
            throw new IllegalArgumentException("Provide at least one complete session");
        var trades = new ArrayList<Trade>();
        var skips = new ArrayList<Skip>();
        var days = new ArrayList<Day>();
        var signals = new EnumMap<Index,V2SignalState>(Index.class);
        LocalDate prior = null;
        for (var session : request.sessions) {
            if (session == null || session.date == null || prior != null && !session.date.isAfter(prior)
                    || session.markets == null || session.markets.isEmpty())
                throw new IllegalArgumentException("Sessions must include markets and have unique ascending dates");
            prior = session.date;
            int count = validateSession(session);
            var previous = new EnumMap<Index,V2SignalState.Decision>(Index.class);
            BigDecimal spend = BigDecimal.ZERO, pnl = BigDecimal.ZERO;
            Position held = null;
            int entries = 0;
            for (int i = 0; i < count; i++) {
                var at = session.date.atTime(strategy.marketOpen()).plusMinutes(5L * i);
                boolean wasHolding = held != null;
                if (wasHolding) {
                    var frame = session.markets.get(held.index).frames.get(i);
                    Trade trade = exit(held, frame, previous.get(held.index), session.markets.get(held.index).frames.get(i - 1).candle.close(), at);
                    if (trade != null) {
                        trades.add(trade);
                        pnl = pnl.add(trade.netPnl);
                        held = null;
                    }
                }
                boolean eligible = !wasHolding && !at.toLocalTime().isBefore(strategy.entryStart())
                        && !at.toLocalTime().isBefore(strategy.strategyStart()) && !at.toLocalTime().isAfter(strategy.lastEntry())
                        && entries < strategy.maxTradesPerDay() && pnl.compareTo(BigDecimal.valueOf(-strategy.maxDailyLossInr())) > 0
                        && pnl.compareTo(BigDecimal.valueOf(strategy.maxDailyProfitInr())) < 0;
                boolean entryFilled = false;
                for (Index index : Index.values()) {
                    Series series = session.markets.get(index);
                    if (series == null) continue;
                    Frame frame = series.frames.get(i);
                    var decision = previous.get(index);
                    if (eligible && !entryFilled && decision != null && decision.permitsOpen(frame.candle.open())) {
                        held = enter(index, frame.chain, decision.entry(), at, options.dailyBudget().subtract(spend), skips);
                        if (held != null) {
                            spend = spend.add(held.entryPremium.multiply(BigDecimal.valueOf(held.quantity))).add(held.entryFee);
                            entries++;
                            entryFilled = true;
                            Trade immediate = exit(held, frame, decision, series.frames.get(i - 1).candle.close(), at);
                            if (immediate != null) {
                                trades.add(immediate);
                                pnl = pnl.add(immediate.netPnl);
                                held = null;
                            }
                        }
                    }
                    previous.put(index, signals.computeIfAbsent(index, k -> new V2SignalState(strategy)).onClose(frame.candle));
                }
            }
            days.add(new Day(session.date, spend, options.dailyBudget().subtract(spend), pnl));
        }
        return new Report(trades, skips, days);
    }

    private int validateSession(Session session) {
        int count = 1 + (int) Duration.between(strategy.marketOpen(), strategy.squareOff()).toMinutes() / 5;
        for (var entry : session.markets.entrySet()) {
            Index index = entry.getKey();
            Series series = entry.getValue();
            String symbol = index == Index.NIFTY ? options.niftySignal() : options.sensexSignal();
            if (series == null || !symbol.equals(series.signalSymbol) || series.frames == null || series.frames.size() != count)
                throw new IllegalArgumentException("Expected complete session of " + symbol + " candles for " + index);
            boolean volume = false;
            LocalDate expiry = null;
            for (int i = 0; i < count; i++) {
                var at = session.date.atTime(strategy.marketOpen()).plusMinutes(5L * i);
                Frame f = series.frames.get(i);
                if (f == null || f.candle == null || !at.equals(f.candle.openTime()))
                    throw new IllegalArgumentException("Missing or misaligned signal candle at " + at);
                volume |= f.candle.volume() > 0;
                if (f.chain == null) continue;
                if (!at.equals(f.chain.asOf) || f.chain.index != index || expiry != null && !expiry.equals(f.chain.expiry))
                    throw new IllegalArgumentException("Chain timestamp, index or session expiry mismatch at " + at);
                expiry = f.chain.expiry;
            }
            if (!volume) throw new IllegalArgumentException("VWAP requires traded volume for " + symbol);
        }
        return count;
    }

    private Position enter(Index index, Chain chain, Side side, LocalDateTime at, BigDecimal available, List<Skip> skips) {
        Right right = side == Side.LONG ? Right.CE : Right.PE;
        Strike row = chain == null ? null : chain.select(right == Right.CE ? options.callOffset() : options.putOffset());
        Book book = row == null ? null : row.book(right);
        if (book == null || book.askQuantity < index.lotSize) {
            skips.add(new Skip(at, index, "STRIKE_OR_ASK_UNAVAILABLE"));
            return null;
        }
        long depth = Math.min(book.askQuantity, book.bidQuantity);
        if (depth < index.lotSize) {
            skips.add(new Skip(at, index, "EXIT_BID_UNAVAILABLE"));
            return null;
        }
        long cap = Math.min(options.maximumLots(), depth / index.lotSize);
        BigDecimal lotPremium = book.ask.multiply(BigDecimal.valueOf(index.lotSize));
        // Per-order fees are monotone. Binary search remains bounded for large configured caps.
        long low = 0, high = cap;
        while (low < high) {
            long lots = (low + high + 1) / 2;
            var premium = lotPremium.multiply(BigDecimal.valueOf(lots));
            if (premium.add(options.fee(index, premium, true)).compareTo(available) <= 0) low = lots;
            else high = lots - 1;
        }
        if (low == 0) {
            skips.add(new Skip(at, index, "DAILY_BUDGET"));
            return null;
        }
        var fee = options.fee(index, lotPremium.multiply(BigDecimal.valueOf(low)), true);
        return new Position(index, right, chain.expiry, row.strike, at, (int) low, low * index.lotSize, book.ask, fee,
                book.ask.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(strategy.stopPercent()).movePointLeft(2))),
                book.ask.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(strategy.targetPercent()).movePointLeft(2))));
    }

    private Trade exit(Position p, Frame frame, V2SignalState.Decision prior, double lastClose, LocalDateTime at) {
        Book book = frame.chain == null ? null : frame.chain.strikes.stream()
                .filter(s -> s.strike.compareTo(p.strike) == 0).map(s -> s.book(p.right))
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (book == null || book.bidQuantity < p.quantity)
            throw new IllegalArgumentException("Insufficient exit bid data for held " + p.index + " " + p.strike + " at " + at);
        BigDecimal bid = book.bid;
        boolean call = p.right == Right.CE;
        ExitReason reason;
        if (at.toLocalTime().equals(strategy.squareOff())) reason = ExitReason.SQUARE_OFF;
        else if (bid.compareTo(p.stop) <= 0) reason = ExitReason.STOP_LOSS;
        else if (bid.compareTo(p.target) >= 0) reason = ExitReason.TARGET;
        else if (strategy.exitOnVwapInvalidation() && prior.vwap() != null &&
                (call ? lastClose < prior.vwap() : lastClose > prior.vwap())) reason = ExitReason.VWAP_INVALIDATION;
        else if (strategy.exitOnEmaReversal() && prior.cross() == (call ? Side.SHORT : Side.LONG)) reason = ExitReason.EMA_REVERSAL;
        else return null;
        var quantity = BigDecimal.valueOf(p.quantity);
        var fee = options.fee(p.index, bid.multiply(quantity), false);
        return new Trade(p, at, bid, reason, fee, bid.subtract(p.entryPremium).multiply(quantity).subtract(p.entryFee).subtract(fee));
    }
}
