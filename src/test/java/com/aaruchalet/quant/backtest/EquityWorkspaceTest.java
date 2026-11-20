package com.aaruchalet.quant.backtest;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** Strict, offline market-data tests: no network, credentials or order API calls. */
class EquityWorkspaceTest {
    private final JsonMapper mapper=JsonMapper.builder().findAndAddModules().build();

    @Test void preliminaryNseScanUsesExclusiveUpperPriceBoundWithoutBuySignal() throws Exception {
        var payload=mapper.readTree("""
            {"timestamp":"20-Nov-2026 15:30:00","data":[
              {"symbol":"NIFTY SMALLCAP 250","lastPrice":15000},
              {"symbol":"ABC","lastPrice":10,"pChange":2},
              {"symbol":"XYZ","lastPrice":99.9,"pChange":-1},
              {"symbol":"BOUNDARY","lastPrice":100},
              {"symbol":"NODATA","lastPrice":"-"}
            ]}
            """);
        var result=EquityPublicMarket.parseIndex(payload,10,100,Instant.parse("2026-11-20T10:00:00Z"));
        assertEquals(2,result.get("matched"));
        assertEquals(Boolean.FALSE,result.get("completeAmfiCoverage"));
        @SuppressWarnings("unchecked")
        var symbols=(List<Map<String,Object>>)result.get("stocks");
        assertEquals(List.of("ABC","XYZ"),symbols.stream().map(s->s.get("symbol")).toList());
        assertTrue(symbols.stream().allMatch(s->"INSUFFICIENT_DATA".equals(s.get("decision"))));
    }

    @Test void sourceQuoteNeverBecomesUnverifiedBuyAndRejectsMissingIdentity() throws Exception {
        var root=mapper.readTree("""
          {"info":{"symbol":"SBIN","companyName":"State Bank of India","isin":"INE062A01020"},
           "metadata":{"lastUpdateTime":"20-Nov-2026 15:30:00"},
           "priceInfo":{"lastPrice":"1,234.50","previousClose":1200,"pChange":2.875}}
          """);
        var quote=EquityPublicMarket.parseQuote("SBIN",root,Instant.parse("2026-11-20T11:00:00Z"));
        assertEquals(1234.5,(Double)quote.get("price"),.001);
        assertEquals("INSUFFICIENT_DATA",quote.get("decision"));
        assertEquals(Boolean.FALSE,quote.get("liveVerified"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->EquityPublicMarket.parseQuote("OTHER",root,Instant.now()));
    }

    @Test void uploadedEquityCsvRunsLocallyWithNoBrokerOrServerFileNavigation() throws Exception {
        var csv=new StringBuilder("timestamp,open,high,low,close,volume\n");
        var date=LocalDate.of(2026,11,18);
        for(int i=0;i<69;i++) {
            var at=date.atTime(9,15).plusMinutes(i*5L);
            csv.append(at).append(",100,101,99,100,1000\n");
        }
        var file=new MockMultipartFile("file","test.csv","text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        var report=new EquityBacktestController(mapper).backtest(file,"TEST",date,date,100000);
        assertEquals("TEST",report.symbol());
        assertEquals(1,report.tradingSessions());
        var oversized=new MockMultipartFile("file","too-large.csv","text/csv",new byte[5_000_001]);
        assertThrows(IllegalArgumentException.class,
                ()->new EquityBacktestController(mapper).backtest(oversized,"TEST",date,date,100000));
    }

    @Test void packagedIndexPagesExcludeHistoricalReplayAndExposeEquitySearch() throws Exception {
        var loader=Thread.currentThread().getContextClassLoader();
        try(var html=loader.getResourceAsStream("static/index.html");
            var script=loader.getResourceAsStream("static/workspace.js")) {
            assertNotNull(html); assertNotNull(script);
            var page=new String(html.readAllBytes(),StandardCharsets.UTF_8);
            var js=new String(script.readAllBytes(),StandardCharsets.UTF_8);
            assertFalse(page.contains("href=\"#trading\""));
            assertTrue(page.contains("id=\"equityQuery\""));
            assertTrue(page.contains("id=\"equityBacktest\""));
            assertTrue(page.contains("id=\"equityResearchTab\""));
            assertTrue(page.contains("id=\"equityResearchPane\""));
            assertTrue(page.contains("id=\"equityResearchDetail\""));
            assertTrue(js.contains("/api/equity/research?isin="));
            assertTrue(js.contains("candidateTable(data)"));
            assertTrue(js.contains("/api/trading/indices/"));
            assertTrue(js.contains("/api/equity/discover"));
            assertTrue(js.contains("/api/equity/eod/"));
            assertTrue(js.contains("/api/equity/lookup"));
            assertTrue(js.contains("/api/equity/backtest"));
            assertFalse(js.contains("/api/backtests/vwap-ema-v2/options/"));
        }
    }
}
