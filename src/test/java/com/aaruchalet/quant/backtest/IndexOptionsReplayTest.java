package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.IndexOptionsReplay.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IndexOptionsReplayTest {
    static final LocalDate DATE = LocalDate.of(2026, 11, 19);
    static final Path OPTIONS = Path.of("config/index-options.yml");
    static final Path V2 = Path.of("config/strategy-vwap-ema.example.yml");
    @TempDir Path temp;

    static BigDecimal n(long x) { return BigDecimal.valueOf(x); }
    static Book book(int bid, int ask) { return new Book(n(bid), 650, n(ask), 650); }

    static Series series(Index index, boolean bullish, int ask, int laterBid, LocalDate day) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            var at = day.atTime(9,15).plusMinutes(i * 5L);
            int price = i < 23 ? 100 : bullish ? 110 : 90;
            Candle c = new Candle(at,price,price,price,price,100);
            Book book = book(i > 24 ? laterBid : ask, ask);
            List<Strike> strikes = List.of(new Strike(n(990),book,book),new Strike(n(1000),book,book),
                    new Strike(n(1050),book,book));
            Chain chain = new Chain(index,at,day.plusDays(6),index.lotSize,n(1003),strikes);
            frames.add(new Frame(c,chain));
        }
        return new Series(index == Index.NIFTY ? "NIFTYBEES" : "SENSEXETF",frames);
    }

    Report run(Path settings, Session... sessions) throws Exception {
        return new IndexOptionsReplay(IndexOptionsConfig.load(settings), VwapEmaV2Config.fromYaml(V2))
                .run(new Request(List.of(sessions)));
    }

    @Test void sharesSpendAcrossIndicesAndChargesPremiumStopOnCallsAndPuts() throws Exception {
        var session = new Session(DATE,Map.of(Index.NIFTY,series(Index.NIFTY,true,100,90,DATE),
                Index.SENSEX,series(Index.SENSEX,false,50,45,DATE)));
        Report report = run(OPTIONS,session);
        assertEquals(2,report.trades().size());
        var first = report.trades().getFirst();
        assertEquals(Right.CE,first.position().right());
        assertEquals(130,first.position().quantity());
        assertEquals(n(1000),first.position().strike());
        assertEquals(DATE.atTime(11,15),first.position().entryAt());
        assertEquals(BacktestReport.ExitReason.STOP_LOSS,first.reason());
        assertEquals(n(90),first.exitPremium());
        var second = report.trades().getLast();
        assertEquals(n(990),second.position().strike());
        assertEquals(Right.PE,second.position().right());
        assertEquals(20,second.position().quantity());
        assertTrue(second.netPnl().signum() < 0);
        var day = report.days().getFirst();
        assertEquals(0,day.entrySpend().compareTo(n(14000).add(first.position().entryFee()).add(second.position().entryFee())));
        assertTrue(day.remainingBudget().compareTo(n(1000)) < 0);
        assertTrue(report.skipped().stream().anyMatch(skip -> skip.reason().equals("DAILY_BUDGET")));
    }

    @Test void usesEditedYamlAndResetsDailyBudget() throws Exception {
        var file = temp.resolve("options.yml");
        Files.writeString(file,Files.readString(OPTIONS).replace("15000","10000").replace("maximumLots: 2","maximumLots: 3")
                .replace("putStrikeOffset: -1","putStrikeOffset: 0"));
        var one = new Session(DATE,Map.of(Index.SENSEX,series(Index.SENSEX,false,150,150,DATE)));
        var next = DATE.plusDays(1);
        var two = new Session(next,Map.of(Index.SENSEX,series(Index.SENSEX,false,150,150,next)));
        var report = run(file,one,two);
        assertEquals(2,report.trades().size());
        report.trades().forEach(t -> {
            assertEquals(60,t.position().quantity());
            assertEquals(n(1000),t.position().strike());
            assertEquals(BacktestReport.ExitReason.SQUARE_OFF,t.reason());
        });
    }

    @Test void includesEntryChargesAtBudgetBoundary() throws Exception {
        var data = new Session(DATE, Map.of(Index.NIFTY, series(Index.NIFTY,true,100,100,DATE)));
        var file = temp.resolve("options.yml");
        var cfg = IndexOptionsConfig.load(OPTIONS);
        var exact = n(6500).add(cfg.fee(Index.NIFTY,n(6500),true));
        String yaml = Files.readString(OPTIONS);
        Files.writeString(file,yaml.replace("15000",exact.toPlainString()));
        assertEquals(65,run(file,data).trades().getFirst().position().quantity());
        Files.writeString(file,yaml.replace("15000",exact.subtract(new BigDecimal("0.01")).toPlainString()));
        assertTrue(run(file,data).trades().isEmpty());
        Files.writeString(file,yaml.replace("maximumLots: 2","maximumLots: 1.5"));
        assertThrows(IllegalArgumentException.class,() -> run(file,data));
        Files.writeString(file,yaml + "lotSize: 17\n");
        assertThrows(IllegalArgumentException.class,() -> run(file,data));
    }

    @Test void respectsListedStrikesTiesMissingQuotesAndContractMetadata() {
        var at = DATE.atTime(10,0);
        var rows = List.of(new Strike(n(110),null,null),new Strike(n(100),null,null));
        var chain = new Chain(Index.NIFTY,at,DATE,65,n(105),rows);
        assertEquals(n(100),chain.select(0).strike());
        assertNull(chain.select(-1));
        assertThrows(IllegalArgumentException.class,() -> new Chain(Index.NIFTY,at,DATE,20,n(105),rows));
        assertThrows(IllegalArgumentException.class,() -> new Chain(Index.SENSEX,at,DATE.minusDays(1),20,n(105),rows));
        assertThrows(IllegalArgumentException.class,() -> new Book(n(110),65,n(100),65));
    }

    @Test void rejectsStaleSnapshotAndMissingHeldQuote() {
        var series = series(Index.NIFTY,true,100,100,DATE);
        var session = new Session(DATE,Map.of(Index.NIFTY,series));
        var frame = series.frames().get(25);
        series.frames().set(25,new Frame(frame.candle(),null));
        assertThrows(IllegalArgumentException.class,() -> run(OPTIONS,session));
        series.frames().set(25,new Frame(frame.candle(),series.frames().get(24).chain()));
        assertThrows(IllegalArgumentException.class,() -> run(OPTIONS,session));
    }
}
