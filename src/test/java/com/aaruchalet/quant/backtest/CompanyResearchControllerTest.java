package com.aaruchalet.quant.backtest;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanyResearchControllerTest {
    @TempDir Path folder;
    @Test void savesSelectedListingAndReevaluatesPositionWhenArchiveFallsBack() throws Exception {
        var json=JsonMapper.builder().findAndAddModules().build();
        var archive=mock(EquityResearchController.class);
        var discovery=mock(EquityDiscovery.class);
        var feed=mock(CompanyEvidenceFeed.class);
        var news=mock(ExchangeNews.class);
        var isin=CompanyResearchTest.ISIN;
        var now=OffsetDateTime.now(CompanyResearch.IST);
        var day=new EquityDiscovery.Archive(now.toLocalDate(),URI.create("https://example.org/eod"),
                Map.of(isin,new EquityDiscovery.Quote("NSE","TEST",isin,50,100000000)));
        when(archive.research(isin,"NSE")).thenReturn(EquityResearchController.summarize(isin,"NSE",List.of(day),now,List.of()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        when(discovery.classification(any())).thenReturn(new EquityDiscovery.Classification(URI.create("https://example.org/amfi"),Map.of(isin,"TEST"),now.toLocalDate()));
        var other=new EquityScreen.Stock("BSE","WRONG",isin,"Other listing",80d,20d,null,null,null,null);
        when(feed.read()).thenReturn(new CompanyEvidenceFeed.Read(new EquityScreen.Snapshot(now.minusSeconds(1),"NEUTRAL",List.of(other)),false,now.toInstant()));
        var controller=new CompanyResearchController(archive,discovery,feed,news,json,folder.toString());
        try {
            var config=new EquityScreen.Config(10,100,20,45,null,null);
            var inputs=new CompanyResearch.Inputs(config,null,null,null,null,null);
            var first=controller.analysis("nse",isin,inputs);
            assertEquals("TEST",first.symbol());assertEquals(CompanyResearch.Decision.WAIT,first.verdict().decision());
            var saved=json.readTree(folder.resolve("NSE-"+isin).resolve(first.id()+".json").toFile());
            assertEquals(first.id(),saved.path("report").path("id").asText());
            assertTrue(saved.path("providerInputs").isNull());
            var held=new CompanyResearch.Inputs(config,CompanyResearch.Position.HELD,10L,52d,51d,null);
            var second=controller.analysis("NSE",isin,held);
            assertNotEquals(first.id(),second.id());assertEquals(CompanyResearch.Quality.STALE,second.dataStatus());
            assertEquals(CompanyResearch.Decision.WAIT,second.verdict().decision());
            assertEquals(first.market().asOf(),second.market().asOf());
        } finally {controller.close();}
    }
}
