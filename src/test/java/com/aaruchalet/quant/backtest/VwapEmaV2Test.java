package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.BacktestReport.Side;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
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
    void oneTouchAndCloseIsEnoughRegardlessOfPreviousBounces() {
        Candle longBounce=bar(TEST_DAY,LocalTime.of(10,0),101,99,102);
        assertTrue(BacktestEngineV2.confirmsFinalVwapBounce(longBounce,100,Side.LONG,0));
        assertFalse(BacktestEngineV2.confirmsFinalVwapBounce(longBounce,100,Side.SHORT,0));
        Candle shortRejection=bar(TEST_DAY,LocalTime.of(10,5),99,98,101);
        assertTrue(BacktestEngineV2.confirmsFinalVwapBounce(shortRejection,100,Side.SHORT,0));
        assertFalse(BacktestEngineV2.confirmsFinalVwapBounce(shortRejection,100,Side.LONG,0));
        assertFalse(BacktestEngineV2.confirmsFinalVwapBounce(
                bar(TEST_DAY,LocalTime.of(10,10),102,101,103),100,Side.LONG,0));
        assertFalse(BacktestEngineV2.confirmsFinalVwapBounce(
                bar(TEST_DAY,LocalTime.of(10,15),100,99,101),100,Side.LONG,0));
    }

    @Test
    void canEnterAfterEmaRsiAndOnlyOneVwapBounceThenSquareOffAt1500() {
        VwapEmaV2Config c=VwapEmaV2Config.from(new MockEnvironment());
        assertEquals(1.0,c.stopPercent());
        assertEquals(2.0,c.targetPercent());
        assertEquals(LocalTime.of(15,0),c.squareOff());
        BacktestReport report=new BacktestEngineV2().run(
                new CsvHistoryRepository.History(longScenario(false),List.of(),2),
                StrategyConfig.from(request()),c);
        assertTrue(report.totalTrades()>=1,
                "EMA/RSI then a single VWAP rebound should permit at least one long");
        assertTrue(report.trades().stream().anyMatch(t->t.side()==Side.LONG));
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
                StrategyConfig.from(request()),VwapEmaV2Config.from(new MockEnvironment()));
        assertEquals(0,report.totalTrades(),
                "Never fill a long below the last confirmed VWAP");
    }

    @Test
    void rejectLiveModeAndOvernightIntraday() {
        assertThrows(IllegalArgumentException.class,()->VwapEmaV2Config.from(
                new MockEnvironment().withProperty("trading.mode","LIVE")));
        assertThrows(IllegalArgumentException.class,()->VwapEmaV2Config.from(
                new MockEnvironment().withProperty("trading.session.carryOver.enabled","true")));
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

    private BacktestRequest request() {
        return new BacktestRequest(TEST_DAY,TEST_DAY,"NIFTYBEES",
                9,20,14,0.0,100.0,0.0,100.0,false,
                100000.0,1.0,0.5,2.0,0.0,0.0,0.0,
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
            double low=t.equals(LocalTime.of(9,35))?96:p-.1;
            all.add(bar(TEST_DAY,t,p,low,p+.1));
        }
        return all;
    }

    private Candle bar(LocalDate day,LocalTime time,double close,double low,double high) {
        return new Candle(LocalDateTime.of(day,time),close,high,low,close,1000);
    }
}
