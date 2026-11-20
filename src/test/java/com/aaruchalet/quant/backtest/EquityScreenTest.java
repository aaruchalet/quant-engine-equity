package com.aaruchalet.quant.backtest;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aaruchalet.quant.backtest.EquityScreen.*;

class EquityScreenTest {
    private final OffsetDateTime asOf = OffsetDateTime.parse("2025-12-31T17:00:00+05:30");
    private final Config config = new Config(10,100,20,45,100000);

    private Result run(double price, Boolean surveillance, double target) {
        var evidence = new HashMap<String,Evidence>();
        for (var key : FACTS) evidence.put(key,new Evidence("https://example.org/data",asOf,"Test evidence",null));
        WEIGHTS.forEach((k,v)->evidence.put(k,new Evidence("https://example.org/data",asOf,"Synthetic fixture",v)));
        var gates = new HashMap<String,Boolean>();
        GATES.forEach(k -> gates.put(k,true));
        gates.put("surveillanceClear",surveillance);
        var bars = new ArrayList<Candle>();
        for (int i=0; i<250; i++) bars.add(new Candle(asOf.toLocalDate().minusDays(249-i).atTime(9,15),10,11,9,10,500));
        var p = new Plan("NOW",true,10d,9d,target,20d,.1,.1,.1,1000L,25,"Loss of support");
        var stock = new Stock("NSE","TEST","TEST-ISIN","Example",price,5d,gates,evidence,bars,p);
        return new EquityScreen().screen(new Request(config,new Snapshot(asOf,"NEUTRAL",List.of(stock)))).results().getFirst();
    }
    @Test void sizesAfterFeesAndIncludesMinimumPrice() {
        var r = run(10,true,15);
        assertEquals("BUY_NOW",r.decision());
        assertEquals(833,r.position_size());
        assertEquals(4,r.net_rr(),1e-8);
        assertEquals(50,r.indicator_values().get("rsi14"),1e-8);
    }
    @Test void excludesCeilingAndFailsClosedOnEvidenceAndFees() {
        assertEquals("REJECTED",run(100,true,15).decision());
        assertEquals("INSUFFICIENT_DATA",run(10,null,15).decision());
        assertEquals("REJECTED",run(10,false,15).decision());
        assertEquals("REJECTED",run(10,true,12).decision());
    }
}
