package com.aaruchalet.quant.backtest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalTime;

/** Immutable, validated snapshot. Same instance feeds indicators + execution. */
public record StrategyConfig(
        LocalDate from, LocalDate through, String symbol,
        int fastEma, int slowEma, int rsiPeriod,
        double longRsiMin, double longRsiMax, double shortRsiMin, double shortRsiMax,
        boolean allowShort, double initialCapital, double riskPercent,
        double stopLossPercent, double targetRiskReward,
        double slippageBps, double feeBps, double feePerOrder,
        double maxDailyLossPercent, int maxTradesPerDay, int lotSize,
        LocalTime firstEntry, LocalTime lastEntry, LocalTime squareOff
) {
    public static StrategyConfig from(BacktestRequest r) {
        if (r == null) throw new IllegalArgumentException("Request body required");
        StrategyConfig c = new StrategyConfig(
                value(r.from(), value(r.through(), LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1)).minusMonths(6).plusDays(1)),
                value(r.through(), LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1)),
                value(r.symbol(), "VOLUME_BEARING_INSTRUMENT"),
                value(r.fastEma(), 9), value(r.slowEma(), 20), value(r.rsiPeriod(), 14),
                value(r.longRsiMin(), 55.0), value(r.longRsiMax(), 75.0),
                value(r.shortRsiMin(), 25.0), value(r.shortRsiMax(), 45.0),
                value(r.allowShort(), false), value(r.initialCapital(), 100000.0),
                value(r.riskPercent(), 1.0), value(r.stopLossPercent(), 0.5),
                value(r.targetRiskReward(), 2.0),
                value(r.slippageBps(), 2.0), value(r.feeBps(), 0.0), value(r.feePerOrder(), 0.0),
                value(r.maxDailyLossPercent(), 3.0), value(r.maxTradesPerDay(), 5),
                value(r.lotSize(), 1), value(r.firstEntry(), LocalTime.of(9, 35)),
                value(r.lastEntry(), LocalTime.of(14, 45)),
                value(r.squareOff(), LocalTime.of(14, 55))
        );
        c.validate();
        return c;
    }
    private static <T> T value(T v, T fallback) { return v == null ? fallback : v; }

    private void validate() {
        if (from.isAfter(through)) throw new IllegalArgumentException("from must be <= through");
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        if (fastEma < 2 || slowEma <= fastEma || rsiPeriod < 2)
            throw new IllegalArgumentException("Require 2 <= fastEma < slowEma and rsiPeriod >= 2");
        if (longRsiMin < 0 || longRsiMax > 100 || longRsiMin >= longRsiMax
                || shortRsiMin < 0 || shortRsiMax > 100 || shortRsiMin >= shortRsiMax)
            throw new IllegalArgumentException("Invalid RSI threshold ranges");
        if (!positive(initialCapital) || !positive(riskPercent) || riskPercent > 10
                || !positive(stopLossPercent) || stopLossPercent >= 100
                || !positive(targetRiskReward) || !positive(maxDailyLossPercent)
                || maxDailyLossPercent > 100 || maxTradesPerDay < 1 || lotSize < 1
                || !nonnegative(slippageBps) || !nonnegative(feeBps) || !nonnegative(feePerOrder))
            throw new IllegalArgumentException("Invalid capital, risk, cost or quantity settings");
        if (!firstEntry.isAfter(LocalTime.of(9, 30)) || lastEntry.isBefore(firstEntry)
                || !lastEntry.isBefore(squareOff) || squareOff.isAfter(LocalTime.of(14, 55)))
            throw new IllegalArgumentException("Require 09:30 < firstEntry <= lastEntry < squareOff <= 14:55");
        if (!aligned(firstEntry) || !aligned(lastEntry) || !aligned(squareOff))
            throw new IllegalArgumentException("Trading times must align to 5-minute candle boundaries");
    }
    private static boolean positive(double n) { return Double.isFinite(n) && n > 0; }
    private static boolean nonnegative(double n) { return Double.isFinite(n) && n >= 0; }
    private static boolean aligned(LocalTime t) { return t.getMinute() % 5 == 0 && t.getSecond() == 0 && t.getNano() == 0; }
}
