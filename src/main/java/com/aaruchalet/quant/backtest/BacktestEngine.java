package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.BacktestReport.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Long/optional short, one position at a time, no lookahead:
 * - Indicator state sees a candle only AFTER its full 5-minute period closes.
 * - A confirmed signal fills at NEXT candle open, with adverse slippage.
 * - Stops/targets use the current bar's OHLC AFTER the open (both touched => stop first).
 * - Forced exit uses the 14:55 candle OPEN, not its future close.
 * - No overnight position; no broker integrations.
 */
public final class BacktestEngine {
    private enum Pending { NONE, LONG, SHORT, EXIT }

    private static final class Position {
        final Side side;
        final LocalDateTime entered;
        final double entry, stop, target, entryFee;
        final long qty;
        Position(Side side, LocalDateTime entered, double entry, double stop, double target,
                 double entryFee, long qty) {
            this.side = side; this.entered = entered; this.entry = entry;
            this.stop = stop; this.target = target; this.entryFee = entryFee; this.qty = qty;
        }
    }

    public BacktestReport run(CsvHistoryRepository.History history, StrategyConfig c) {
        List<Candle> candles = history.candles();
        IndicatorState indicators = new IndicatorState(c.fastEma(), c.slowEma(), c.rsiPeriod());
        List<Trade> trades = new ArrayList<>();
        List<EquityPoint> curve = new ArrayList<>();
        double equity = c.initialCapital(), peak = equity, maxDrawdown = 0;
        curve.add(new EquityPoint(c.from().atStartOfDay(), equity));
        Position position = null;
        Pending pending = Pending.NONE;
        LocalDate previousDay = null;
        double sessionStartEquity = equity;
        int sessionTrades = 0, candlesProcessed = 0;
        boolean priorLongSetup = false, priorShortSetup = false;
        Set<LocalDate> testedDays = new HashSet<>();

        for (Candle bar : candles) {
            LocalDate date = bar.openTime().toLocalDate();
            if (!date.equals(previousDay)) {
                if (position != null) throw new IllegalStateException("Position carried overnight; invalid history");
                previousDay = date;
                pending = Pending.NONE;
                sessionStartEquity = equity;
                sessionTrades = 0;
                priorLongSetup = false;
                priorShortSetup = false;
            }
            LocalTime time = bar.openTime().toLocalTime();
            boolean inRequestedRange = !date.isBefore(c.from()) && !date.isAfter(c.through());
            if (inRequestedRange) testedDays.add(date);
            if (inRequestedRange) candlesProcessed++;

            // Orders decided on the prior confirmed close: execute at this candle's OPEN.
            if (position != null && (time.equals(c.squareOff()) || pending == Pending.EXIT)) {
                ExitReason reason = time.equals(c.squareOff()) ? ExitReason.SQUARE_OFF : ExitReason.TREND_REVERSAL;
                Trade exit = close(position, bar.openTime(), bar.open(), reason, c);
                equity += exit.netProfit();
                trades.add(exit);
                curve.add(new EquityPoint(exit.exitAt(), equity));
                peak = Math.max(peak, equity);
                maxDrawdown = Math.max(maxDrawdown, 100 * (peak - equity) / peak);
                position = null;
                pending = Pending.NONE;
            }
            if (position == null && inRequestedRange && (pending == Pending.LONG || pending == Pending.SHORT)
                    && !time.isBefore(c.firstEntry()) && !time.isAfter(c.lastEntry())
                    && equity > sessionStartEquity * (1 - c.maxDailyLossPercent() / 100.0)
                    && sessionTrades < c.maxTradesPerDay()) {
                Side side = pending == Pending.LONG ? Side.LONG : Side.SHORT;
                if (side == Side.LONG || c.allowShort()) {
                    position = enter(side, bar, equity, c);
                    if (position != null) sessionTrades++;
                }
            }
            pending = Pending.NONE;

            // Intrabar stop/target evaluation is permitted because the entry is at this bar's open.
            if (position != null && time.isBefore(c.squareOff())) {
                ExitReason reason = null;
                double rawExit = 0;
                if (position.side == Side.LONG) {
                    if (bar.open() <= position.stop) { reason = ExitReason.STOP_LOSS; rawExit = bar.open(); }
                    else if (bar.open() >= position.target) { reason = ExitReason.TARGET; rawExit = bar.open(); }
                    else if (bar.low() <= position.stop) { reason = ExitReason.STOP_LOSS; rawExit = position.stop; }
                    else if (bar.high() >= position.target) { reason = ExitReason.TARGET; rawExit = position.target; }
                } else {
                    if (bar.open() >= position.stop) { reason = ExitReason.STOP_LOSS; rawExit = bar.open(); }
                    else if (bar.open() <= position.target) { reason = ExitReason.TARGET; rawExit = bar.open(); }
                    else if (bar.high() >= position.stop) { reason = ExitReason.STOP_LOSS; rawExit = position.stop; }
                    else if (bar.low() <= position.target) { reason = ExitReason.TARGET; rawExit = position.target; }
                }
                if (reason != null) {
                    Trade exit = close(position, bar.closeTime(), rawExit, reason, c);
                    equity += exit.netProfit();
                    trades.add(exit);
                    curve.add(new EquityPoint(exit.exitAt(), equity));
                    peak = Math.max(peak, equity);
                    maxDrawdown = Math.max(maxDrawdown, 100 * (peak - equity) / peak);
                    position = null;
                }
            }

            // At this point a bar is completed; indicator values and decisions are now known.
            IndicatorState.Snapshot s = indicators.add(bar);
            if (!inRequestedRange || !s.ready()) continue;
            boolean bullish = s.emaFast() > s.emaSlow() && bar.close() > s.vwap()
                    && s.rsi() >= c.longRsiMin() && s.rsi() <= c.longRsiMax();
            boolean bearish = s.emaFast() < s.emaSlow() && bar.close() < s.vwap()
                    && s.rsi() >= c.shortRsiMin() && s.rsi() <= c.shortRsiMax();

            // Confirmed close at time+5 may schedule entry at NEXT 5-minute bar open.
            LocalTime decisionAt = bar.closeTime().toLocalTime();
            boolean eligible = !decisionAt.isBefore(c.firstEntry()) && !decisionAt.isAfter(c.lastEntry());
            if (position == null && eligible && sessionTrades < c.maxTradesPerDay()
                    && equity > sessionStartEquity * (1 - c.maxDailyLossPercent() / 100.0)) {
                // Rising edge prevents repeated entry on every bullish/bearish candle.
                if (bullish && !priorLongSetup) pending = Pending.LONG;
                else if (c.allowShort() && bearish && !priorShortSetup) pending = Pending.SHORT;
            } else if (position != null && decisionAt.isBefore(c.squareOff())) {
                boolean reversed = position.side == Side.LONG ? !bullish : !bearish;
                if (reversed) pending = Pending.EXIT; // execute at next bar open
            }
            if (eligible) { priorLongSetup = bullish; priorShortSetup = bearish; }
        }
        if (position != null) throw new IllegalStateException("Missing square-off candle after live position");
        double grossWins = 0, grossLosses = 0;
        int winners = 0;
        for (Trade t : trades) {
            if (t.netProfit() > 0) { winners++; grossWins += t.netProfit(); }
            else grossLosses += -t.netProfit();
        }
        Double profitFactor = grossLosses == 0 ? null : grossWins / grossLosses;
        return new BacktestReport(c.symbol(), "Asia/Kolkata", c.from().toString(), c.through().toString(),
                candlesProcessed, testedDays.size(), history.skippedSessions(),
                round(c.initialCapital()), round(equity), round(equity - c.initialCapital()),
                round(100 * (equity / c.initialCapital() - 1)), trades.size(), winners,
                trades.size() - winners, trades.isEmpty() ? 0 : round(100.0 * winners / trades.size()),
                profitFactor == null ? null : round(profitFactor), round(maxDrawdown),
                List.copyOf(trades), List.copyOf(curve));
    }

    private Position enter(Side side, Candle bar, double equity, StrategyConfig c) {
        double adverse = c.slippageBps() / 10000.0;
        double entry = bar.open() * (side == Side.LONG ? (1 + adverse) : (1 - adverse));
        double perUnitRisk = entry * c.stopLossPercent() / 100.0;
        long qtyForRisk = (long) Math.floor((equity * c.riskPercent() / 100.0) / perUnitRisk);
        // Cash-equity/ETF sizing. Futures/options require a margin-aware sizing adapter.
        long qtyForCash = (long) Math.floor(equity / (entry * (1 + c.feeBps() / 10000.0)));
        long qty = Math.min(qtyForRisk, qtyForCash);
        qty = (qty / c.lotSize()) * c.lotSize();
        if (qty <= 0) return null;
        double stop = side == Side.LONG ? entry - perUnitRisk : entry + perUnitRisk;
        double target = side == Side.LONG ? entry + c.targetRiskReward() * perUnitRisk
                : entry - c.targetRiskReward() * perUnitRisk;
        double entryFee = fee(entry * qty, c);
        if (entry * qty + entryFee > equity) {
            qty -= c.lotSize();
            if (qty <= 0) return null;
            entryFee = fee(entry * qty, c);
        }
        return new Position(side, bar.openTime(), entry, stop, target, entryFee, qty);
    }

    private Trade close(Position p, LocalDateTime at, double rawPrice,
                        ExitReason reason, StrategyConfig c) {
        double adverse = c.slippageBps() / 10000.0;
        double exit = rawPrice * (p.side == Side.LONG ? 1 - adverse : 1 + adverse);
        double gross = p.qty * (p.side == Side.LONG ? exit - p.entry : p.entry - exit);
        double totalFees = p.entryFee + fee(exit * p.qty, c);
        return new Trade(p.side, p.entered, at, round(p.entry), round(exit), p.qty,
                round(p.stop), round(p.target), reason, round(gross), round(totalFees), round(gross - totalFees));
    }
    private double fee(double notional, StrategyConfig c) {
        return notional * c.feeBps() / 10000.0 + c.feePerOrder();
    }
    private static double round(double n) { return Math.round(n * 100.0) / 100.0; }
}
