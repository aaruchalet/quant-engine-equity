package com.aaruchalet.quant.backtest;

import org.springframework.core.env.Environment;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Immutable V2 settings read from the external YAML imported by application.yml.
 * This research service must never accept LIVE mode or place broker orders.
 */
public record VwapEmaV2Config(
        String strategyId, String mode, String productType, String orderType, String validity,
        ZoneId zone, LocalTime marketOpen, LocalTime strategyStart, LocalTime entryStart,
        LocalTime lastEntry, LocalTime squareOff, LocalTime marketClose,
        boolean carryOver, int fastEma, int slowEma, int rsiPeriod,
        boolean rsiEnabled, double longRsiAbove, double shortRsiBelow,
        boolean rsiExitEnabled, double rsiShortExitAbove,
        boolean shortsEnabled, boolean crossoverRequired,
        boolean bounceEnabled, double touchToleranceBps,
        boolean exitOnVwapInvalidation, boolean exitOnEmaReversal,
        boolean stopEnabled, double stopPercent, boolean targetEnabled, double targetPercent,
        int quantity, int maximumQuantity, int maxConcurrentPositions,
        int maxTradesPerDay, double maxDailyLossInr, double maxDailyProfitInr,
        boolean closePositionOnDailyLoss
) {
    public static VwapEmaV2Config from(Environment e) {
        VwapEmaV2Config c = new VwapEmaV2Config(
                get(e, "strategyId", "VWAP_BOUNCE_EMA_V2"),
                get(e, "trading.mode", "PAPER"),
                get(e, "trading.orders.productType", "INTRADAY"),
                get(e, "trading.orders.type", "MARKET"),
                get(e, "trading.orders.validity", "DAY"),
                ZoneId.of(get(e, "trading.session.zone", "Asia/Kolkata")),
                LocalTime.parse(get(e, "trading.session.marketOpen", "09:15")),
                LocalTime.parse(get(e, "trading.session.strategyStart", "09:20")),
                LocalTime.parse(get(e, "trading.session.entryStart", "09:30")),
                LocalTime.parse(get(e, "trading.session.lastEntry", "14:45")),
                LocalTime.parse(get(e, "trading.session.squareOff", "15:00")),
                LocalTime.parse(get(e, "trading.session.marketClose", "15:30")),
                bool(e,"trading.session.carryOver.enabled",false),
                integer(e,"trading.signal.ema.fastPeriod",9),
                integer(e,"trading.signal.ema.slowPeriod",20),
                integer(e,"trading.signal.rsi.period",14),
                bool(e,"trading.signal.rsi.enabled",true),
                decimal(e,"trading.signal.rsi.entry.longAbove",50),
                decimal(e,"trading.signal.rsi.entry.shortBelow",50),
                bool(e,"trading.signal.rsi.exit.enabled",false),
                decimal(e,"trading.signal.rsi.exit.shortAbove",55),
                bool(e,"trading.signal.shortsEnabled",true),
                bool(e,"trading.signal.ema.crossoverRequired",true),
                bool(e,"trading.signal.vwap.bounce.enabled",true),
                decimal(e,"trading.signal.vwap.bounce.touchToleranceBps",0),
                bool(e,"trading.exits.exitOnConfirmedVwapInvalidation",false),
                bool(e,"trading.exits.exitOnConfirmedEmaReversal",false),
                bool(e,"trading.risk.stopLoss.enabled",true),
                decimal(e,"trading.risk.stopLoss.value",1),
                bool(e,"trading.risk.takeProfit.enabled",true),
                decimal(e,"trading.risk.takeProfit.value",2),
                integer(e,"trading.positions.defaultQuantity",1),
                integer(e,"trading.positions.maximumQuantity",10),
                integer(e,"trading.positions.maximumConcurrentPositions",1),
                integer(e,"trading.risk.maximumTradesPerDay",5),
                decimal(e,"trading.risk.maximumDailyLossInr",5000),
                decimal(e,"trading.risk.maximumDailyProfitInr",10000),
                bool(e,"trading.risk.closePositionOnDailyLoss",false)
        );
        c.validate(e);
        return c;
    }
    private static String get(Environment e,String name,String fallback) {
        return e.getProperty(name, fallback);
    }
    private static int integer(Environment e,String n,int fallback) {
        return Integer.parseInt(get(e,n,Integer.toString(fallback)));
    }
    private static double decimal(Environment e,String n,double fallback) {
        return Double.parseDouble(get(e,n,Double.toString(fallback)));
    }
    private static boolean bool(Environment e,String n,boolean fallback) {
        String s=get(e,n,Boolean.toString(fallback));
        if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("V2 requires boolean value for "+n);
        return Boolean.parseBoolean(s);
    }
    private static boolean aligned(LocalTime t) {
        return t.getMinute()%5==0 && t.getSecond()==0 && t.getNano()==0;
    }
    private void validate(Environment e) {
        if (!mode.equals("PAPER"))
            throw new IllegalArgumentException("quant-engine-test is read-only: V2 LIVE mode is forbidden");
        if (!orderType.equals("MARKET") || !productType.equals("INTRADAY") || !validity.equals("DAY"))
            throw new IllegalArgumentException("V2 currently supports MARKET/INTRADAY/DAY research assumptions only");
        if (!zone.equals(ZoneId.of("Asia/Kolkata")))
            throw new IllegalArgumentException("V2 requires Asia/Kolkata time zone for IST CSV");
        if (carryOver) throw new IllegalArgumentException("Cannot carry positions overnight in INTRADAY V2");
        if (fastEma<2 || slowEma<=fastEma || rsiPeriod<2 || !rsiEnabled
                || !crossoverRequired || !bounceEnabled)
            throw new IllegalArgumentException("V2 requires EMA crossover, VWAP bounce and RSI enabled");
        if (!(longRsiAbove>=0 && longRsiAbove<100 && shortRsiBelow>0 && shortRsiBelow<=100))
            throw new IllegalArgumentException("V2 RSI entry thresholds invalid");
        if (rsiExitEnabled)
            throw new IllegalArgumentException("V2 RSI exit currently disabled; configure a long exit threshold before enabling");
        if (!Double.isFinite(touchToleranceBps) || touchToleranceBps<0 || touchToleranceBps>100)
            throw new IllegalArgumentException("V2 VWAP touch tolerance must be 0..100 bps");
        if (!stopEnabled || !targetEnabled || !finitePositive(stopPercent) || !finitePositive(targetPercent)
                || stopPercent>=100 || targetPercent>=100)
            throw new IllegalArgumentException("V2 requires valid enabled percentage stop and target");
        if (quantity<1 || maximumQuantity<quantity || maximumQuantity>10 || maxConcurrentPositions!=1)
            throw new IllegalArgumentException("V2 quantity must be 1..10, one position at a time");
        if (maxTradesPerDay<1 || !finitePositive(maxDailyLossInr) || !finitePositive(maxDailyProfitInr))
            throw new IllegalArgumentException("V2 daily trade/P&L caps invalid");
        if (closePositionOnDailyLoss)
            throw new IllegalArgumentException("V2 immediate daily-loss close is not supported; keep false");
        if (!(marketOpen.isBefore(strategyStart) && !strategyStart.isAfter(entryStart)
                && entryStart.isBefore(lastEntry) && lastEntry.isBefore(squareOff)
                && !squareOff.isAfter(marketClose))
                || !aligned(marketOpen) || !aligned(strategyStart) || !aligned(entryStart)
                || !aligned(lastEntry) || !aligned(squareOff) || !aligned(marketClose))
            throw new IllegalArgumentException("V2 invalid 5-minute-aligned trading session schedule");
        if (bool(e,"trading.automatedExecutionEnabled",false))
            throw new IllegalArgumentException("V2 test service cannot enable automated order execution");
        if (!"PERCENT".equals(get(e,"trading.risk.stopLoss.mode","PERCENT"))
                || !"PERCENT".equals(get(e,"trading.risk.takeProfit.mode","PERCENT")))
            throw new IllegalArgumentException("V2 stop/target must be PERCENT of actual entry price");
        if (!"HLC3".equals(get(e,"trading.signal.vwap.source","HLC3")))
            throw new IllegalArgumentException("V2 requires HLC3 session VWAP for this backtester");
        if (!bool(e,"trading.signal.vwap.recheckSideAtNextOpen",true))
            throw new IllegalArgumentException("V2 requires VWAP side recheck at the fill");
        if (!bool(e,"trading.signal.vwap.requireLongAbove",true)
                || !bool(e,"trading.signal.vwap.requireShortBelow",true))
            throw new IllegalArgumentException("V2 requires strict VWAP side filters");
    }
    private static boolean finitePositive(double v) {
        return Double.isFinite(v) && v>0;
    }
}
