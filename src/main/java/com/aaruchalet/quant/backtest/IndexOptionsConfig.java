package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.FileSystemResource;

record IndexOptionsConfig(BigDecimal dailyBudget, int maximumLots, int callOffset, int putOffset,
                          String niftySignal, String sensexSignal,
                          BigDecimal brokerage, BigDecimal niftyExchange, BigDecimal sensexExchange,
                          BigDecimal niftyIpft, BigDecimal sensexIpft, BigDecimal sebi,
                          BigDecimal stamp, BigDecimal stt, BigDecimal gst) {
    static IndexOptionsConfig load(Path path) throws IOException {
        var sources = new YamlPropertySourceLoader().load("indexOptions", new FileSystemResource(path));
        if (sources.size() != 1 || !(sources.getFirst() instanceof EnumerablePropertySource<?> source))
            throw new IllegalArgumentException("Expected one options YAML document");
        var remaining = new HashSet<>(Arrays.asList(source.getPropertyNames()));
        java.util.function.Function<String, BigDecimal> number = key -> {
            if (!remaining.remove(key)) throw new IllegalArgumentException("Missing options YAML setting: " + key);
            return new BigDecimal(String.valueOf(source.getProperty(key)));
        };
        java.util.function.Function<String, String> symbol = key -> {
            if (!remaining.remove(key) || !(source.getProperty(key) instanceof String s) || s.isBlank())
                throw new IllegalArgumentException("Missing options signal symbol: " + key);
            return s;
        };
        try {
            var cfg = new IndexOptionsConfig(number.apply("dailyBudgetInr").setScale(2, RoundingMode.UNNECESSARY),
                    number.apply("maximumLots").intValueExact(), number.apply("callStrikeOffset").intValueExact(),
                    number.apply("putStrikeOffset").intValueExact(), symbol.apply("signalSymbols.nifty"),
                    symbol.apply("signalSymbols.sensex"), number.apply("fees.brokeragePerOrderInr"),
                    number.apply("fees.niftyExchangePercent"), number.apply("fees.sensexExchangePercent"),
                    number.apply("fees.niftyIpftPercent"), number.apply("fees.sensexIpftPercent"),
                    number.apply("fees.sebiPercent"), number.apply("fees.stampDutyBuyPercent"),
                    number.apply("fees.sttSellPercent"), number.apply("fees.gstPercent"));
            if (!remaining.isEmpty()) throw new IllegalArgumentException("Unknown options YAML settings: " + remaining);
            return cfg;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Expected whole lots/strike offsets and a budget in rupees and paise", e);
        }
    }

    IndexOptionsConfig {
        if (dailyBudget.signum() <= 0 || maximumLots < 1 || brokerage.signum() < 0)
            throw new IllegalArgumentException("Invalid options budget, lot cap or brokerage");
        for (var rate : java.util.List.of(niftyExchange, sensexExchange, niftyIpft, sensexIpft, sebi, stamp, stt, gst))
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0)
                throw new IllegalArgumentException("Options fee percentages must be in [0, 100]");
    }

    BigDecimal fee(IndexOptionsReplay.Index index, BigDecimal turnover, boolean buy) {
        boolean nifty = index == IndexOptionsReplay.Index.NIFTY;
        var rates = (nifty ? niftyExchange.add(niftyIpft) : sensexExchange.add(sensexIpft)).add(sebi);
        var taxable = brokerage.add(turnover.multiply(rates).movePointLeft(2));
        return taxable.add(taxable.multiply(gst).movePointLeft(2))
                .add(turnover.multiply(buy ? stamp : stt).movePointLeft(2)).setScale(2, RoundingMode.HALF_UP);
    }
}
