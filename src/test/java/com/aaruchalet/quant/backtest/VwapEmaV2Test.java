package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.BacktestReport.Side;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VwapEmaV2Test {
    private static final LocalDate WARMUP = LocalDate.of(2026,5,19);
    private static final LocalDate TEST_DAY = LocalDate.of(2026,5,20);

    @Test
    void vwapSideAcceptsUntouchedOrTouchedCandlesButRejectsEqualityAndWrongSide() {
        Candle above=bar(TEST_DAY,LocalTime.of(10,0),102,101,103);
        Candle below=bar(TEST_DAY,LocalTime.of(10,5),98,97,99);
        Candle touchingAbove=bar(TEST_DAY,LocalTime.of(10,10),101,99,102);
        Candle touchingBelow=bar(TEST_DAY,LocalTime.of(10,15),99,98,101);
        Candle equal=bar(TEST_DAY,LocalTime.of(10,20),100,99,101);
        assertTrue(BacktestEngineV2.confirmsVwapSide(above,100,Side.LONG));
        assertTrue(BacktestEngineV2.confirmsVwapSide(below,100,Side.SHORT));
        assertTrue(BacktestEngineV2.confirmsVwapSide(touchingAbove,100,Side.LONG));
        assertTrue(BacktestEngineV2.confirmsVwapSide(touchingBelow,100,Side.SHORT));
        assertFalse(BacktestEngineV2.confirmsVwapSide(above,100,Side.SHORT));
        assertFalse(BacktestEngineV2.confirmsVwapSide(below,100,Side.LONG));
        assertFalse(BacktestEngineV2.confirmsVwapSide(equal,100,Side.LONG));
        assertFalse(BacktestEngineV2.confirmsVwapSide(equal,100,Side.SHORT));
    }

    @Test
    void longCanEnterAfterCrossoverAndRsiWithoutTouchingVwap() {
        VwapEmaV2Config c=config();
        assertEquals(1.0,c.stopPercent());
        assertEquals(2.0,c.targetPercent());
        assertEquals(LocalTime.of(15,0),c.squareOff());
        List<Candle> candles=longScenario(false);
        assertSignalCandleDoesNotTouchVwap(candles,Side.LONG);
        BacktestReport report=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(candles,List.of(),2),
                StrategyConfig.from(request()),c);
        assertTrue(report.totalTrades()>=1);
        assertEquals(Side.LONG,report.trades().getFirst().side());
        assertEquals(LocalTime.of(9,40),report.trades().getFirst().entryAt().toLocalTime());
        report.trades().forEach(t->{
            assertEquals(1,t.quantity());
            assertTrue(t.entryAt().toLocalTime().compareTo(LocalTime.of(9,30))>=0);
            assertTrue(t.exitAt().toLocalTime().compareTo(LocalTime.of(15,0))<=0);
            assertEquals(t.entryAt().toLocalDate(),t.exitAt().toLocalDate());
            assertEquals(t.entryPrice()*0.99,t.stopLoss(),0.03);
            assertEquals(t.entryPrice()*1.02,t.target(),0.03);
        });
    }

    @Test
    void cancelPendingLongWhenNextOpenLosesVwapSide() {
        BacktestReport report=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(longScenario(true),List.of(),2),
                StrategyConfig.from(request()),config());
        assertTrue(report.trades().stream().noneMatch(t ->
                t.entryAt().toLocalTime().equals(LocalTime.of(9,40))),
                "Never fill a long when the next open is below the last confirmed VWAP");
    }

    @Test
    void shortCanEnterAfterCrossoverAndRsiWithoutTouchingVwap() {
        List<Candle> candles=shortScenario(false);
        assertSignalCandleDoesNotTouchVwap(candles,Side.SHORT);
        BacktestReport report=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(candles,List.of(),2),
                StrategyConfig.from(request()),config());
        assertTrue(report.totalTrades()>=1);
        assertEquals(Side.SHORT,report.trades().getFirst().side());
        assertEquals(LocalTime.of(9,40),report.trades().getFirst().entryAt().toLocalTime());
        for(var trade:report.trades()) {
            assertEquals(1,trade.quantity());
            assertEquals(trade.entryPrice()*1.01,trade.stopLoss(),0.04);
            assertEquals(trade.entryPrice()*0.98,trade.target(),0.04);
        }
    }

    @Test
    void cancelPendingShortWhenNextOpenLosesVwapSide() {
        BacktestReport report=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(shortScenario(true),List.of(),2),
                StrategyConfig.from(request()),config());
        assertTrue(report.trades().stream().noneMatch(t ->
                t.entryAt().toLocalTime().equals(LocalTime.of(9,40))),
                "Never fill a short when the next open is above the last confirmed VWAP");
    }

    @Test
    void rsiStillBlocksEntriesEvenWhenPriceIsOnTheCorrectVwapSide() {
        var config=configWith("longAbove: 50", "longAbove: 99.999", "shortBelow: 50", "shortBelow: 0.001");
        var longReport=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(longScenario(false),List.of(),2),
                StrategyConfig.from(request()),config);
        var shortReport=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(shortScenario(false),List.of(),2),
                StrategyConfig.from(request()),config);
        assertEquals(0,longReport.totalTrades());
        assertEquals(0,shortReport.totalTrades());
    }

    @Test
    void priorSessionAlignmentWithoutCurrentSessionCrossoverDoesNotEnter() {
        List<Candle> candles=new ArrayList<>();
        int index=0;
        for (LocalDate day : List.of(WARMUP,TEST_DAY)) {
            for(LocalTime t=LocalTime.of(9,15);!t.isAfter(LocalTime.of(15,0));t=t.plusMinutes(5)) {
                double p=100+index++ * 0.1;
                candles.add(bar(day,t,p,p-.01,p+.01));
            }
        }
        BacktestReport report=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(candles,List.of(),2),
                StrategyConfig.from(request()),config());
        assertEquals(0,report.totalTrades(),"EMA alignment alone is not a current-session crossover");
    }

    private void assertSignalCandleDoesNotTouchVwap(List<Candle> candles,Side side) {
        IndicatorState indicators=new IndicatorState(9,20,14);
        for (Candle candle : candles) {
            var snapshot=indicators.add(candle);
            if (candle.openTime().equals(LocalDateTime.of(TEST_DAY,LocalTime.of(9,35)))) {
                assertNotNull(snapshot.vwap());
                if (side==Side.LONG) assertTrue(candle.low()>snapshot.vwap());
                else assertTrue(candle.high()<snapshot.vwap());
                return;
            }
        }
        fail("Missing signal candle");
    }

    @Test
    void rejectLiveModeAndOvernightIntraday() {
        assertThrows(IllegalArgumentException.class,()->configWith("trading:", "trading:\n  mode: LIVE"));
        assertThrows(IllegalArgumentException.class,()->configWith("enabled: false              # true + INTRADAY", "enabled: true              # true + INTRADAY"));
    }

    @Test
    void yamlValuesControlQuantityStopsTargetsAndShortPermission() {
        var config=configWith("defaultQuantity: 1", "defaultQuantity: 3",
                "value: 1.0", "value: 0.7", "value: 2.0", "value: 1.5",
                "shortsEnabled: true", "shortsEnabled: false");
        BacktestReport longReport=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(longScenario(false),List.of(),2),
                StrategyConfig.from(request()),config);
        assertFalse(longReport.trades().isEmpty());
        var trade=longReport.trades().getFirst();
        assertEquals(3,trade.quantity());
        assertEquals(trade.entryPrice()*0.993,trade.stopLoss(),0.03);
        assertEquals(trade.entryPrice()*1.015,trade.target(),0.03);
        BacktestReport shortReport=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(shortScenario(false),List.of(),2),
                StrategyConfig.from(request()),config);
        assertTrue(shortReport.trades().stream().noneMatch(t -> t.side()==Side.SHORT));
    }

    @Test
    void angelOneFeesUseBuySellSidesAndYamlRates() {
        var fees=config().fees();
        double buy=AngelOneIntradayFees.orderFee(10_000,true,fees);
        double sell=AngelOneIntradayFees.orderFee(11_000,false,fees);
        // Buy brokerage 10, sell brokerage 11; stamp duty only on buy and STT only on sell.
        assertEquals(28.615526,buy+sell,1e-6);
        assertEquals(11.8354812,AngelOneIntradayFees.orderFee(100,true,fees)
                +AngelOneIntradayFees.orderFee(100,false,fees),1e-6);
        var modified=configWith("minimumBrokerageInr: 5", "minimumBrokerageInr: 0",
                "maximumBrokerageInr: 20", "maximumBrokerageInr: 10");
        assertTrue(AngelOneIntradayFees.orderFee(100,true,modified.fees())
                < AngelOneIntradayFees.orderFee(100,true,fees));
    }

    @Test
    void enabledOptionalExitsReportTheirActualCause() {
        List<Candle> candles=new ArrayList<>(longScenario(false));
        for (int i=0;i<candles.size();i++) {
            Candle c=candles.get(i);
            if (c.openTime().equals(LocalDateTime.of(TEST_DAY,LocalTime.of(9,45))))
                candles.set(i,bar(TEST_DAY,LocalTime.of(9,45),94,93.9,94.1));
        }
        for (var scenario : List.of(
                new String[]{"exitOnConfirmedVwapInvalidation: false","exitOnConfirmedVwapInvalidation: true","VWAP_INVALIDATION"},
                new String[]{"exitOnConfirmedEmaReversal: false","exitOnConfirmedEmaReversal: true","EMA_REVERSAL"})) {
            var config=configWith("value: 1.0", "value: 50.0", "value: 2.0", "value: 50.0",
                    scenario[0],scenario[1]);
            var report=new BacktestEngineV2().run(new CsvHistoryRepository.History(candles,List.of(),2),
                    StrategyConfig.from(request()),config);
            assertEquals(scenario[2],report.trades().getFirst().exitReason().name());
        }
    }

    @Test
    void v2ControllerReloadsYamlForEachRequest() throws Exception {
        Path csv=Files.createTempFile("v2-controller-", ".csv");
        Path yaml=Files.createTempFile("v2-controller-", ".yml");
        try {
            StringBuilder data=new StringBuilder("timestamp,open,high,low,close,volume\n");
            for (Candle c : longScenario(false))
                data.append(c.openTime()).append(',').append(c.open()).append(',').append(c.high())
                        .append(',').append(c.low()).append(',').append(c.close()).append(',').append(c.volume()).append('\n');
            Files.writeString(csv,data.toString());
            String settings=Files.readString(Path.of("src/main/resources/strategy-vwap-ema.example.yml"));
            Files.writeString(yaml,settings);
            var controller=new VwapEmaV2Controller(csv.toString(),"NIFTYBEES",yaml.toString());
            assertEquals(1,controller.run(request()).trades().getFirst().quantity());
            Files.writeString(yaml,settings.replace("defaultQuantity: 1","defaultQuantity: 3"));
            assertEquals(3,controller.run(request()).trades().getFirst().quantity());
        } finally { Files.deleteIfExists(csv); Files.deleteIfExists(yaml); }
    }

    @Test
    void yamlValidationRejectsMissingUnsupportedAndUnknownSettings() {
        assertThrows(IllegalArgumentException.class,()->configWith("timeframe: FIVE_MINUTE", "timeframe: ONE_MINUTE"));
        assertThrows(IllegalArgumentException.class,()->configWith("fastPeriod: 9", "fastPerod: 9"));
        assertThrows(IllegalArgumentException.class,()->configWith("fastPeriod: 9", ""));
        assertThrows(IllegalArgumentException.class,()->configWith("VWAP_SIDE]", "VWAP_BOUNCE]"));
        assertThrows(IllegalArgumentException.class,()->configWith("gstPercent: 18", "gstPercent: -1"));
        assertThrows(UncheckedIOException.class,()-> {
            try { VwapEmaV2Config.fromYaml(Path.of("does-not-exist-v2.yml")); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @Test
    void yamlMarketOpenControlsIncludedCandlesAndSessionVwap() throws Exception {
        var config=configWith("marketOpen: \"09:15\"", "marketOpen: \"09:25\"",
                "strategyStart: \"09:20\"", "strategyStart: \"09:30\"",
                "entryStart: \"09:30\"", "entryStart: \"09:35\"");
        Path file=Files.createTempFile("v2-open-", ".csv");
        try {
            StringBuilder csv=new StringBuilder("timestamp,open,high,low,close,volume\n");
            for(LocalTime t=LocalTime.of(9,15);!t.isAfter(config.squareOff());t=t.plusMinutes(5)) {
                int price=t.isBefore(config.marketOpen())?200:100;
                csv.append("2026-05-20T").append(t).append(":00,")
                        .append(price).append(',').append(price).append(',').append(price).append(',')
                        .append(price).append(",1000\n");
            }
            Files.writeString(file,csv.toString());
            var history=new CsvHistoryRepository().load(file,TEST_DAY,config.marketOpen(),config.squareOff());
            assertEquals(config.marketOpen(),history.candles().getFirst().openTime().toLocalTime());
            var indicators=new IndicatorState(config.fastEma(),config.slowEma(),config.rsiPeriod());
            assertEquals(100.0,indicators.add(history.candles().getFirst()).vwap(),1e-9);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void failClosedWithoutAuthentic1500OpeningPrice() throws Exception {
        Path file=Files.createTempFile("niftybees-v1-only", ".csv");
        try {
            StringBuilder csv=new StringBuilder("timestamp,open,high,low,close,volume\n");
            for(LocalTime t=LocalTime.of(9,15);!t.isAfter(LocalTime.of(14,55));t=t.plusMinutes(5))
                csv.append("2026-05-20T").append(t).append(":00,100,101,99,100,1000\n");
            Files.writeString(file,csv.toString());
            assertEquals(69,new CsvHistoryRepository().load(file,TEST_DAY).candles().size());
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
                    ()->new CsvHistoryRepository().load(file,TEST_DAY,LocalTime.of(15,0)));
            assertTrue(error.getMessage().contains("15:00"));
        } finally {Files.deleteIfExists(file);}
    }

    private VwapEmaV2Config config() {
        try { return VwapEmaV2Config.fromYaml(Path.of("src/main/resources/strategy-vwap-ema.example.yml")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private VwapEmaV2Config configWith(String... replacements) {
        try {
            String yaml=Files.readString(Path.of("src/main/resources/strategy-vwap-ema.example.yml"));
            for (int i=0; i<replacements.length; i+=2) {
                if (!yaml.contains(replacements[i])) throw new IllegalArgumentException("Missing test fixture: "+replacements[i]);
                yaml=yaml.replace(replacements[i],replacements[i+1]);
            }
            Path temp=Files.createTempFile("v2-settings-", ".yml");
            try {
                Files.writeString(temp,yaml);
                return VwapEmaV2Config.fromYaml(temp);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private BacktestRequest request() {
        return new BacktestRequest(TEST_DAY,TEST_DAY,"NIFTYBEES",
                9,20,14,0.0,100.0,0.0,100.0,false,
                100000.0,1.0,0.5,2.0,0.0,null,null,
                3.0,5,1,LocalTime.of(9,35),LocalTime.of(14,45),LocalTime.of(14,55));
    }

    private List<Candle> longScenario(boolean gapUnderVwap) {
        List<Candle> all=new ArrayList<>();
        for(LocalTime t=LocalTime.of(9,15);!t.isAfter(LocalTime.of(15,0));t=t.plusMinutes(5))
            all.add(bar(WARMUP,t,100,99.9,100.1));
        for(LocalTime t=LocalTime.of(9,15);!t.isAfter(LocalTime.of(15,0));t=t.plusMinutes(5)) {
            double p=104.5;
            if (t.equals(LocalTime.of(9,15))||t.equals(LocalTime.of(9,20))) p=97;
            else if(t.equals(LocalTime.of(9,25))) p=98;
            else if(t.equals(LocalTime.of(9,30))) p=101;
            else if(t.equals(LocalTime.of(9,35))) p=104;
            else if(t.equals(LocalTime.of(9,40))&&gapUnderVwap) p=95;
            all.add(bar(TEST_DAY,t,p,p-.1,p+.1));
        }
        return all;
    }

    private List<Candle> shortScenario(boolean gapOverVwap) {
        List<Candle> all=new ArrayList<>();
        for(LocalTime t=LocalTime.of(9,15);!t.isAfter(LocalTime.of(15,0));t=t.plusMinutes(5))
            all.add(bar(WARMUP,t,100,99.9,100.1));
        for(LocalTime t=LocalTime.of(9,15);!t.isAfter(LocalTime.of(15,0));t=t.plusMinutes(5)) {
            double p=95.5;
            if(t.equals(LocalTime.of(9,15))||t.equals(LocalTime.of(9,20))) p=103;
            else if(t.equals(LocalTime.of(9,25))) p=102;
            else if(t.equals(LocalTime.of(9,30))) p=99;
            else if(t.equals(LocalTime.of(9,35))) p=96;
            else if(t.equals(LocalTime.of(9,40))&&gapOverVwap) p=105;
            all.add(bar(TEST_DAY,t,p,p-.1,p+.1));
        }
        return all;
    }

    private Candle bar(LocalDate day,LocalTime time,double close,double low,double high) {
        return new Candle(LocalDateTime.of(day,time),close,high,low,close,1000);
    }
}
