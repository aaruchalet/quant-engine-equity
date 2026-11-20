package com.aaruchalet.quant.backtest;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Offline tests: no market API calls, broker sessions, or order requests. */
class EquityResearchControllerTest {
    private static final String ISIN="INE000A01012";

    private static List<EquityDiscovery.Archive> archives(int count) {
        var result=new ArrayList<EquityDiscovery.Archive>();
        for(int i=0;i<count;i++) {
            var day=LocalDate.of(2026,11,20).minusDays(count-i-1);
            var quote=new EquityDiscovery.Quote("NSE","EXAMPLE",ISIN,30+i,80_000_000);
            result.add(new EquityDiscovery.Archive(day,URI.create("https://nsearchives.nseindia.com/test"+i),
                    Map.of(ISIN,quote)));
        }
        return result;
    }

    @Test void completesOnlyPreliminaryResearchForTwentyVerifiedSessions() {
        var research=EquityResearchController.summarize(ISIN,"NSE",archives(20),
                OffsetDateTime.parse("2026-11-20T16:30:00+05:30"),List.of());
        assertEquals("PRELIMINARY_RESEARCH",research.status());
        assertEquals(20,research.observedSessions());
        assertEquals("EXAMPLE",research.symbol());
        assertEquals(49d,research.latestClose());
        assertEquals(48d,research.previousClose());
        assertEquals(8d,research.averageTradedValueCr());
        assertEquals(100d,research.rsi14Indicative());
        assertTrue(research.ema9Indicative()>research.ema20Indicative());
        assertEquals(20,research.dailyPrices().size());
        assertTrue(research.missingChecks().stream().anyMatch(s->s.contains("surveillance")));
        assertFalse(research.status().contains("BUY"));
    }

    @Test void insufficientSessionsNeverReportVerifiedLiquidityOrIndicators() {
        var result=EquityResearchController.summarize(ISIN,"NSE",archives(7),
                OffsetDateTime.parse("2026-11-20T16:30:00+05:30"),List.of());
        assertEquals("PARTIAL_DATA",result.status());
        assertNull(result.averageTradedValueCr());
        assertNull(result.ema20Indicative());
        assertNull(result.rsi14Indicative());
        assertEquals(7,result.dailyPrices().size());
    }

    @Test void missingSecurityCannotCreateFictitiousResearch() {
        assertThrows(ResponseStatusException.class,
                ()->EquityResearchController.summarize("INE999Z01010","NSE",archives(20),
                        OffsetDateTime.parse("2026-11-20T16:30:00+05:30"),List.of()));
    }
}
