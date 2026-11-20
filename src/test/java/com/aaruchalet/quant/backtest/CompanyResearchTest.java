package com.aaruchalet.quant.backtest;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.aaruchalet.quant.backtest.CompanyResearch.*;
import static com.aaruchalet.quant.backtest.EquityScreen.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyResearchTest {
    static final String ISIN="INE000A01012";
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-20T14:00:00+05:30");
    final Config config=new Config(10,100,20,45,100000d,null);
    final Map<String,Evidence> evidence=new HashMap<>();
    final Map<String,Boolean> gates=new HashMap<>();
    boolean confirmed=true,down=false,fallback=false;
    double turnover=12,target=56,volume=250;
    OffsetDateTime asOf=NOW;

    CompanyResearchTest() {
        for(var key:FACTS) evidence.put(key,fact(NOW));
        for(var key:FILED_TESTS) evidence.put(key,fact(NOW));
        for(var key:GATES) gates.put(key,true);
    }
    private Evidence fact(OffsetDateTime at) {return new Evidence("https://example.org/filing",at,"Test filing",null,true);}
    private Inputs input(Position position,Double stop) {return new Inputs(config,position,null,null,stop,null);}
    private Report run(Position pos,Double stop) {
        var bars=new ArrayList<Candle>();
        var last=asOf.toLocalDate().minusDays(1);
        for(int i=0;i<250;i++) {
            double close=down ? 75-i*.1 : 25+i*.1;
            bars.add(new Candle(last.minusDays(249-i).atTime(9,15),close,close+.2,close-.2,close,i==249?volume:100));
        }
        var plan=new Plan("BREAKOUT",confirmed,50d,48d,target,65d,.5,0d,1d,1000L,25,"Below support",49d,1d,
                0d,0d,0d,0d,100L,"Trade at ₹50 with volume confirmation",NOW.plusHours(2),asOf,true,true);
        var stock=new Stock("NSE","EXAMPLE",ISIN,"Example",50d,turnover,gates,evidence,bars,plan);
        return evaluate("NSE",ISIN,input(pos,stop),null,new Snapshot(asOf,"NEUTRAL",List.of(stock)),
                "SMALL CAP",null,List.of(),NOW,fallback,List.of());
    }
    @Test void confirmedBreakoutRequiresTwoToOneAfterFees() {
        target=56.5; // 6.5 gross less 1.5 costs vs 2.5 modeled loss = exactly 2.
        var r=run(Position.NONE,null);
        assertEquals(Decision.BUY,r.verdict().decision());assertEquals(2,r.tradePlan().netRewardRisk());
        assertEquals(100,r.score());assertEquals(100,r.categories().stream().mapToInt(Category::maximum).sum());
        assertEquals(3,r.verdict().reasons().size());assertEquals(250,r.chart().daily().size());
        assertNull(r.chart().daily().get(198).sma200());assertNotNull(r.chart().daily().get(199).sma200());
        target=56.49;
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
    }
    @Test void unknownHeldAndUnheldUseSeparateActions() {
        target=58;
        assertEquals(Decision.WAIT,run(Position.UNKNOWN,null).verdict().decision());
        assertEquals(Decision.HOLD,run(Position.HELD,48d).verdict().decision());
        assertEquals(Decision.SELL,run(Position.HELD,50d).verdict().decision());
        gates.put("surveillanceClear",false);
        assertEquals(Decision.SELL,run(Position.HELD,48d).verdict().decision());
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
    }
    @Test void noEntryForPendingBearishLowVolumeIlliquidOrMissingEvidence() {
        target=58;confirmed=false;
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
        confirmed=true;down=true;
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
        down=false;volume=100;
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
        volume=250;turnover=4;
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
        turnover=12;evidence.remove("financials");
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
    }
    @Test void oldFilingsAndCachedInputsSuppressNewActions() {
        target=58;evidence.put("financials",fact(NOW.minusDays(180)));
        assertEquals(Decision.WAIT,run(Position.NONE,null).verdict().decision());
        evidence.put("financials",fact(NOW));fallback=true;
        var r=run(Position.NONE,null);
        assertEquals(Quality.STALE,r.dataStatus());assertEquals(Decision.WAIT,r.verdict().decision());
        assertEquals(Decision.WAIT,run(Position.HELD,51d).verdict().decision());
    }
    @Test void twentyArchiveClosesCannotCreateLongHistoryOrAnEntry() {
        var days=new ArrayList<EquityDiscovery.Archive>();
        for(int i=0;i<20;i++) {
            var date=NOW.toLocalDate().minusDays(20-i);
            days.add(new EquityDiscovery.Archive(date,URI.create("https://example.org/"+date),
                    Map.of(ISIN,new EquityDiscovery.Quote("NSE","EXAMPLE",ISIN,50+i,100000000))));
        }
        var market=EquityResearchController.summarize(ISIN,"NSE",days,NOW,List.of());
        var report=evaluate("NSE",ISIN,input(null,null),market,null,"SMALL CAP",null,List.of(),NOW,false,List.of());
        assertEquals(Position.NONE,report.inputs().positionStatus());assertEquals(Quality.PRELIMINARY,report.dataStatus());
        assertNull(report.score());assertNull(report.tradePlan().target1());
        assertEquals(Decision.WAIT,report.verdict().decision());assertEquals(3,report.verdict().reasons().size());
        assertNull(report.chart().daily().getLast().sma200());assertTrue(report.indicators().isEmpty());
    }
}
