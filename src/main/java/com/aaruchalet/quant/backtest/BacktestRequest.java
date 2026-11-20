package com.aaruchalet.quant.backtest;

import java.time.LocalDate;
import java.time.LocalTime;

/** Nullable request fields get documented defaults in StrategyConfig.from(). */
public record BacktestRequest(
        LocalDate from, LocalDate through, String symbol,
        Integer fastEma, Integer slowEma, Integer rsiPeriod,
        Double longRsiMin, Double longRsiMax, Double shortRsiMin, Double shortRsiMax,
        Boolean allowShort, Double initialCapital, Double riskPercent,
        Double stopLossPercent, Double targetRiskReward,
        Double slippageBps, Double feeBps, Double feePerOrder,
        Double maxDailyLossPercent, Integer maxTradesPerDay, Integer lotSize,
        LocalTime firstEntry, LocalTime lastEntry, LocalTime squareOff
) {
    public static BacktestRequest syntheticDefaults() {
        return new BacktestRequest(null, null, "SYNTHETIC_DEMO",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
