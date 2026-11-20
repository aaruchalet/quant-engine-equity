package com.aaruchalet.quant.backtest;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * Immutable strategy settings shared by the historical and market runners.
 */
public record VwapEmaV2Config(
        String strategyId, String productType, String orderType, String validity,
        ZoneId zone, LocalTime marketOpen, LocalTime strategyStart, LocalTime entryStart,
        LocalTime lastEntry, LocalTime squareOff, LocalTime marketClose,
        boolean carryOver, int fastEma, int slowEma, int rsiPeriod,
        boolean rsiEnabled, double longRsiAbove, double shortRsiBelow,
        boolean rsiExitEnabled, double rsiShortExitAbove,
        boolean shortsEnabled, boolean crossoverRequired,
        boolean exitOnVwapInvalidation, boolean exitOnEmaReversal,
        boolean stopEnabled, double stopPercent, boolean targetEnabled, double targetPercent,
        int quantity, int maximumQuantity, int maxConcurrentPositions,
        int maxTradesPerDay, double maxDailyLossInr, double maxDailyProfitInr,
        boolean closePositionOnDailyLoss, FeeConfig fees
) {
    public record FeeConfig(double brokeragePercent, double minimumBrokerageInr, double maximumBrokerageInr,
                            double exchangeTransactionPercent, double investorProtectionPercent,
                            double sebiTurnoverPercent, double stampDutyBuyPercent,
                            double sttSellPercent, double gstPercent) {}
    private static final Set<String> KEYS = Set.of(
            "schemaVersion", "strategyId", "trading.mode", "trading.automatedExecutionEnabled",
            "trading.orders.type", "trading.orders.productType", "trading.orders.validity",
            "trading.positions.defaultQuantity", "trading.positions.maximumQuantity",
            "trading.positions.maximumConcurrentPositions", "trading.session.zone",
            "trading.session.marketOpen", "trading.session.strategyStart", "trading.session.entryStart",
            "trading.session.lastEntry", "trading.session.squareOff", "trading.session.marketClose",
            "trading.session.carryOver.enabled", "trading.signal.shortsEnabled",
            "trading.signal.evaluationOrder[0]", "trading.signal.evaluationOrder[1]",
            "trading.signal.evaluationOrder[2]", "trading.signal.timeframe", "trading.signal.requireAllFilters",
            "trading.signal.ema.enabled", "trading.signal.ema.fastPeriod", "trading.signal.ema.slowPeriod",
            "trading.signal.ema.crossoverRequired", "trading.signal.ema.crossoverScope",
            "trading.signal.vwap.enabled", "trading.signal.vwap.source",
            "trading.signal.vwap.requireLongAbove", "trading.signal.vwap.requireShortBelow",
            "trading.signal.vwap.recheckSideAtNextOpen", "trading.signal.rsi.enabled",
            "trading.signal.rsi.period", "trading.signal.rsi.entry.longAbove",
            "trading.signal.rsi.entry.shortBelow", "trading.signal.rsi.exit.enabled",
            "trading.signal.rsi.exit.longBelow", "trading.signal.rsi.exit.shortAbove",
            "trading.exits.exitOnCompositeEntryFilterLoss", "trading.exits.exitOnConfirmedVwapInvalidation",
            "trading.exits.exitOnConfirmedEmaReversal", "trading.risk.stopLoss.enabled",
            "trading.risk.stopLoss.mode", "trading.risk.stopLoss.value", "trading.risk.takeProfit.enabled",
            "trading.risk.takeProfit.mode", "trading.risk.takeProfit.value", "trading.risk.maximumTradesPerDay",
            "trading.risk.maximumDailyLossInr", "trading.risk.maximumDailyProfitInr",
            "trading.risk.closePositionOnDailyLoss", "trading.fees.model", "trading.fees.brokeragePercent",
            "trading.fees.minimumBrokerageInr", "trading.fees.maximumBrokerageInr",
            "trading.fees.exchangeTransactionPercent", "trading.fees.investorProtectionPercent",
            "trading.fees.sebiTurnoverPercent", "trading.fees.stampDutyBuyPercent", "trading.fees.sttSellPercent",
            "trading.fees.gstPercent", "logging.level.root");

    /** Read this strategy's YAML directly; missing files or settings cannot silently use Java defaults. */
    public static VwapEmaV2Config fromYaml(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("V2 YAML file does not exist: " + path);
        return fromYaml(new FileSystemResource(path));
    }

    static VwapEmaV2Config fromYaml(Resource resource) throws IOException {
        var sources = new YamlPropertySourceLoader().load("v2Strategy", resource);
        if (sources.size() != 1 || !(sources.getFirst() instanceof EnumerablePropertySource<?> source))
            throw new IllegalArgumentException("V2 configuration must contain exactly one YAML document");
        for (String name : source.getPropertyNames()) {
            if (!KEYS.contains(name)) throw new IllegalArgumentException("Unknown V2 YAML setting: " + name);
        }
        MutablePropertySources properties = new MutablePropertySources();
        properties.addLast(source);
        return from(new PropertySourcesPropertyResolver(properties));
    }

    private static VwapEmaV2Config from(PropertyResolver e) {
        require(e,"schemaVersion","2");
        require(e,"strategyId","VWAP_BOUNCE_EMA_V2");
        require(e,"trading.signal.evaluationOrder[0]","EMA_CROSSOVER");
        require(e,"trading.signal.evaluationOrder[1]","RSI");
        require(e,"trading.signal.evaluationOrder[2]","VWAP_SIDE");
        require(e,"trading.signal.timeframe","FIVE_MINUTE");
        require(e,"trading.signal.ema.crossoverScope","CURRENT_SESSION");
        require(e,"trading.signal.ema.enabled","true");
        require(e,"trading.signal.vwap.enabled","true");
        require(e,"trading.signal.requireAllFilters","true");
        require(e,"trading.exits.exitOnCompositeEntryFilterLoss","false");
        require(e,"trading.fees.model","ANGEL_ONE_NSE_EQUITY_INTRADAY");
        FeeConfig fees=new FeeConfig(decimal(e,"trading.fees.brokeragePercent"),
                decimal(e,"trading.fees.minimumBrokerageInr"), decimal(e,"trading.fees.maximumBrokerageInr"),
                decimal(e,"trading.fees.exchangeTransactionPercent"), decimal(e,"trading.fees.investorProtectionPercent"),
                decimal(e,"trading.fees.sebiTurnoverPercent"), decimal(e,"trading.fees.stampDutyBuyPercent"),
                decimal(e,"trading.fees.sttSellPercent"), decimal(e,"trading.fees.gstPercent"));
        validateFees(fees);
        String logLevel=e.getProperty("logging.level.root");
        if (logLevel!=null && !Set.of("TRACE","DEBUG","INFO","WARN","ERROR","FATAL","OFF").contains(logLevel))
            throw new IllegalArgumentException("Unsupported root logging level in V2 YAML");
        VwapEmaV2Config c = new VwapEmaV2Config(
                get(e,"strategyId"),
                get(e,"trading.orders.productType"),
                get(e,"trading.orders.type"),
                get(e,"trading.orders.validity"),
                ZoneId.of(get(e,"trading.session.zone")),
                LocalTime.parse(get(e,"trading.session.marketOpen")),
                LocalTime.parse(get(e,"trading.session.strategyStart")),
                LocalTime.parse(get(e,"trading.session.entryStart")),
                LocalTime.parse(get(e,"trading.session.lastEntry")),
                LocalTime.parse(get(e,"trading.session.squareOff")),
                LocalTime.parse(get(e,"trading.session.marketClose")),
                bool(e,"trading.session.carryOver.enabled"),
                integer(e,"trading.signal.ema.fastPeriod"),
                integer(e,"trading.signal.ema.slowPeriod"),
                integer(e,"trading.signal.rsi.period"),
                bool(e,"trading.signal.rsi.enabled"),
                decimal(e,"trading.signal.rsi.entry.longAbove"),
                decimal(e,"trading.signal.rsi.entry.shortBelow"),
                bool(e,"trading.signal.rsi.exit.enabled"),
                decimal(e,"trading.signal.rsi.exit.shortAbove"),
                bool(e,"trading.signal.shortsEnabled"),
                bool(e,"trading.signal.ema.crossoverRequired"),
                bool(e,"trading.exits.exitOnConfirmedVwapInvalidation"),
                bool(e,"trading.exits.exitOnConfirmedEmaReversal"),
                bool(e,"trading.risk.stopLoss.enabled"),
                decimal(e,"trading.risk.stopLoss.value"),
                bool(e,"trading.risk.takeProfit.enabled"),
                decimal(e,"trading.risk.takeProfit.value"),
                integer(e,"trading.positions.defaultQuantity"),
                integer(e,"trading.positions.maximumQuantity"),
                integer(e,"trading.positions.maximumConcurrentPositions"),
                integer(e,"trading.risk.maximumTradesPerDay"),
                decimal(e,"trading.risk.maximumDailyLossInr"),
                decimal(e,"trading.risk.maximumDailyProfitInr"),
                bool(e,"trading.risk.closePositionOnDailyLoss"), fees
        );
        c.validate(e);
        return c;
    }
    private static String get(PropertyResolver e, String name) {
        String value = e.getProperty(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing V2 YAML setting: " + name);
        return value;
    }
    private static void require(PropertyResolver e, String name, String expected) {
        if (!expected.equals(get(e,name)))
            throw new IllegalArgumentException("Unsupported V2 YAML setting: " + name + "; required " + expected);
    }
    private static int integer(PropertyResolver e,String n) {
        return Integer.parseInt(get(e,n));
    }
    private static double decimal(PropertyResolver e,String n) {
        return Double.parseDouble(get(e,n));
    }
    private static boolean bool(PropertyResolver e,String n) {
        String s=get(e,n);
        if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("V2 requires boolean value for "+n);
        return Boolean.parseBoolean(s);
    }
    private static boolean aligned(LocalTime t) {
        return t.getMinute()%5==0 && t.getSecond()==0 && t.getNano()==0;
    }
    private void validate(PropertyResolver e) {
        if (!"PAPER".equals(e.getProperty("trading.mode", "PAPER")))
            throw new IllegalArgumentException("Set trading.mode in application.yml, not in a legacy strategy file");
        if (!orderType.equals("MARKET") || !productType.equals("INTRADAY") || !validity.equals("DAY"))
            throw new IllegalArgumentException("V2 currently supports MARKET/INTRADAY/DAY research assumptions only");
        if (!zone.equals(ZoneId.of("Asia/Kolkata")))
            throw new IllegalArgumentException("V2 requires Asia/Kolkata time zone for IST CSV");
        if (carryOver) throw new IllegalArgumentException("Cannot carry positions overnight in INTRADAY V2");
        if (fastEma<2 || slowEma<=fastEma || rsiPeriod<2 || !rsiEnabled
                || !crossoverRequired)
            throw new IllegalArgumentException("V2 requires EMA crossover and RSI enabled");
        if (!(longRsiAbove>=0 && longRsiAbove<100 && shortRsiBelow>0 && shortRsiBelow<=100))
            throw new IllegalArgumentException("V2 RSI entry thresholds invalid");
        if (rsiExitEnabled)
            throw new IllegalArgumentException("V2 RSI exit currently disabled; configure a long exit threshold before enabling");
        if (!Double.isFinite(rsiShortExitAbove) || rsiShortExitAbove<0 || rsiShortExitAbove>100)
            throw new IllegalArgumentException("V2 inactive short RSI exit threshold must be 0..100");
        String longExit=e.getProperty("trading.signal.rsi.exit.longBelow");
        if (longExit!=null && !longExit.isBlank()) {
            double value=Double.parseDouble(longExit);
            if (!Double.isFinite(value) || value<0 || value>100)
                throw new IllegalArgumentException("V2 inactive long RSI exit threshold must be 0..100");
        }
        if (!stopEnabled || !targetEnabled || !finitePositive(stopPercent) || !finitePositive(targetPercent)
                || stopPercent>=100 || targetPercent>=100)
            throw new IllegalArgumentException("V2 requires valid enabled percentage stop and target");
        if (quantity<1 || maximumQuantity<quantity || maxConcurrentPositions!=1)
            throw new IllegalArgumentException("V2 quantity must be positive and within the configured maximum, one position at a time");
        if (maxTradesPerDay<1 || !finitePositive(maxDailyLossInr) || !finitePositive(maxDailyProfitInr))
            throw new IllegalArgumentException("V2 daily trade/P&L caps invalid");
        if (closePositionOnDailyLoss)
            throw new IllegalArgumentException("V2 immediate daily-loss close is not supported; keep false");
        if (!(marketOpen.isBefore(strategyStart) && !strategyStart.isAfter(entryStart)
                && entryStart.isBefore(lastEntry) && lastEntry.isBefore(squareOff)
                && !squareOff.isAfter(marketClose))
                || !aligned(marketOpen) || !aligned(strategyStart) || !aligned(entryStart)
                || !aligned(lastEntry) || !aligned(squareOff) || !aligned(marketClose)
                || marketOpen.isBefore(LocalTime.of(9,15)) || squareOff.isAfter(LocalTime.of(15,25))
                || marketClose.isAfter(LocalTime.of(15,30)))
            throw new IllegalArgumentException("V2 invalid 5-minute-aligned trading session schedule");
        if (e.containsProperty("trading.automatedExecutionEnabled") && bool(e,"trading.automatedExecutionEnabled"))
            throw new IllegalArgumentException("V2 test service cannot enable automated order execution");
        if (!"PERCENT".equals(get(e,"trading.risk.stopLoss.mode"))
                || !"PERCENT".equals(get(e,"trading.risk.takeProfit.mode")))
            throw new IllegalArgumentException("V2 stop/target must be PERCENT of actual entry price");
        if (!"HLC3".equals(get(e,"trading.signal.vwap.source")))
            throw new IllegalArgumentException("V2 requires HLC3 session VWAP for this backtester");
        if (!bool(e,"trading.signal.vwap.recheckSideAtNextOpen"))
            throw new IllegalArgumentException("V2 requires VWAP side recheck at the fill");
        if (!bool(e,"trading.signal.vwap.requireLongAbove")
                || !bool(e,"trading.signal.vwap.requireShortBelow"))
            throw new IllegalArgumentException("V2 requires strict VWAP side filters");
    }
    private static boolean finitePositive(double v) {
        return Double.isFinite(v) && v>0;
    }
    private static void validateFees(FeeConfig f) {
        double[] values={f.brokeragePercent(),f.minimumBrokerageInr(),f.maximumBrokerageInr(),
                f.exchangeTransactionPercent(),f.investorProtectionPercent(),f.sebiTurnoverPercent(),
                f.stampDutyBuyPercent(),f.sttSellPercent(),f.gstPercent()};
        for (double v : values) if (!Double.isFinite(v) || v<0)
            throw new IllegalArgumentException("V2 fee values must be finite and non-negative");
        if (f.minimumBrokerageInr()>f.maximumBrokerageInr() || f.brokeragePercent()>100
                || f.exchangeTransactionPercent()>100 || f.investorProtectionPercent()>100
                || f.sebiTurnoverPercent()>100 || f.stampDutyBuyPercent()>100
                || f.sttSellPercent()>100 || f.gstPercent()>100)
            throw new IllegalArgumentException("Invalid V2 fee ranges");
    }
}
