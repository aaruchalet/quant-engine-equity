package com.aaruchalet.quant.backtest;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aaruchalet.quant.backtest.EquityScreen.*;

class EquityScreenTest {
    private final OffsetDateTime asOf = OffsetDateTime.parse("2025-12-31T14:00:00+05:30");
    private final Config config = new Config(10,100,20,45,100000d,null);

    private Result run(double price, Boolean surveillance, double target) {
        return run(price,surveillance,target,config,"NOW",true,asOf.plusMinutes(5));
    }

    private Result run(double price, Boolean surveillance, double target, Config config, String setup, boolean confirmed, OffsetDateTime expiry) {
        var evidence = new HashMap<String,Evidence>();
        for (var key : FACTS) evidence.put(key,new Evidence("https://example.org/data",asOf,"Test evidence",null,null));
        FILED_TESTS.forEach(k->evidence.put(k,new Evidence("https://example.org/data",asOf,"Synthetic fixture",null,true)));
        var gates = new HashMap<String,Boolean>();
        GATES.forEach(k -> gates.put(k,true));
        gates.put("surveillanceClear",surveillance);
        var bars = new ArrayList<Candle>();
        for (int i=0; i<250; i++) bars.add(new Candle(asOf.toLocalDate().minusDays(249-i).atTime(9,15),10,11,9,10,500));
        var p = new Plan(setup,confirmed,10d,9d,target,20d,.1,.1,.1,1000L,25,"Loss of support",10d,.2,0d,0d,0d,0d,100L,"Retest of support",expiry,asOf,true,true);
        var stock = new Stock("NSE","TEST","TEST-ISIN","Example",price,10d,gates,evidence,bars,p);
        return new EquityScreen(Clock.fixed(asOf.toInstant(), ZoneOffset.UTC)).screen(new Request(config,new Snapshot(asOf,"NEUTRAL",List.of(stock)))).results().getFirst();
    }
    @Test void sizesAfterFeesAndIncludesMinimumPrice() {
        var r = run(10,true,15);
        assertEquals("BUY_NOW",r.decision());
        assertEquals(416,r.position_size());
        assertEquals(4,r.net_rr(),1e-8);
        assertEquals(9.7 / 1.2,r.net_rr_t2(),1e-8);
        assertEquals(20,r.score_breakdown().size());
        assertEquals(50,r.indicator_values().get("rsi14"),1e-8);
    }
    @Test void excludesCeilingAndFailsClosedOnEvidenceAndFees() {
        assertEquals("REJECTED",run(100,true,15).decision());
        assertEquals("INSUFFICIENT_DATA",run(10,null,15).decision());
        assertEquals("REJECTED",run(10,false,15).decision());
        assertEquals("REJECTED",run(10,true,12).decision());
    }
    @Test void conditionalPlansExpireAndCapitalIsOptional() {
        var noCapital = new Config(10,100,20,45,null,null);
        var pending = run(10,true,15,noCapital,"PULLBACK",false,asOf.plusHours(1));
        assertEquals("BUY_ON_PULLBACK",pending.decision());
        assertNull(pending.position_size());
        assertEquals("WATCHLIST",run(10,true,15,config,"PULLBACK",false,asOf).decision());
        assertEquals("WATCHLIST",run(11,true,15,config,"NOW",true,asOf.plusHours(1)).decision());
        assertEquals("WATCHLIST",run(10,true,15,new Config(10,100,20,45,100000d,1d),"NOW",true,asOf.plusHours(1)).decision());
    }
}
