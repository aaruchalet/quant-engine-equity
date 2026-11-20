package com.aaruchalet.quant.backtest;

import java.time.LocalDateTime;
import java.util.List;

/** Realized trade P&L; equity curve is sampled after completed trades (not tick-to-tick MTM). */
public record BacktestReport(
        String symbol, String timezone, String from, String through,
        int candlesProcessed, int tradingSessions, List<String> skippedSessions,
        double initialCapital, double finalCapital, double netProfit,
        double returnPercent, int totalTrades, int winningTrades, int losingTrades,
        double winRatePercent, Double profitFactor, double maxClosedEquityDrawdownPercent,
        List<Trade> trades, List<EquityPoint> closedTradeEquityCurve
) {
    public enum Side { LONG, SHORT }
    public enum ExitReason { STOP_LOSS, TARGET, TREND_REVERSAL, SQUARE_OFF }
    public record Trade(Side side, LocalDateTime entryAt, LocalDateTime exitAt,
                        double entryPrice, double exitPrice, long quantity,
                        double stopLoss, double target, ExitReason exitReason,
                        double grossProfit, double totalFees, double netProfit) {}
    public record EquityPoint(LocalDateTime timestamp, double equity) {}
}
