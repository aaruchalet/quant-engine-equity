package com.aaruchalet.quant.backtest;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aaruchalet.quant.backtest.IndexOptionsReplay.Index.*;

class SmartMarketTest {
    @Test void resolvesMasterAndReadsFreshBrokerDepthOverHttp() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var date=LocalDate.of(2026,11,23);
        var json=JsonMapper.builder().build();
        var timestamp=new AtomicReference<>("23-Nov-2026 10:00:00");
        var header=new AtomicReference<String>();
        var body=new AtomicReference<String>();
        server.createContext("/master",ex -> {
            var bytes="""
                    [{"name":"NIFTY","symbol":"Nifty 50","token":"1","exch_seg":"NSE","instrumenttype":"AMXIDX"},
                    {"name":"SENSEX","symbol":"SENSEX","token":"2","exch_seg":"BSE","instrumenttype":"AMXIDX"},
                    {"symbol":"SENSEXETF-EQ","token":"3","exch_seg":"NSE","instrumenttype":""}]
                    """.getBytes();
            ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.createContext("/market/v1/quote/",ex -> {
            header.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes()));
            var bytes=("""
                    {"status":true,"data":{"fetched":[{"exchange":"BSE","symbolToken":"2","ltp":100,
                    "exchFeedTime":"%s","depth":{"buy":[{"price":99.95,"quantity":80}],
                    "sell":[{"price":100.05,"quantity":100}]}}]}}
                    """).formatted(timestamp.get()).getBytes();
            ex.sendResponseHeaders(200,bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            var host=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            var creds=new AngelOneHistoricalClient.Credentials("test","a.b.c","","127.0.0.1","192.0.2.1","test");
            var hist=new AngelOneHistoricalClient(HttpClient.newHttpClient(),json,host,creds,Duration.ZERO);
            var clock=Clock.fixed(date.atTime(10,0,3).atZone(AngelOneHistoricalClient.IST).toInstant(),AngelOneHistoricalClient.IST);
            var m=new SmartMarket(hist,json,20,host,host.resolve("master"),clock);
            var config=IndexOptionsConfig.load(SettingsResource.resolve("classpath:/index-options.yml"));
            m.loadMaster(date);
            assertEquals("Nifty 50",m.underlying(NIFTY,config,false).symbol());
            assertEquals("SENSEXETF-EQ",m.underlying(SENSEX,config,true).symbol());
            var i=m.underlying(SENSEX,config,false);
            assertEquals(80,m.quotes(i).get(i).bidSize());
            assertEquals("Bearer a.b.c",header.get());
            assertEquals("2",json.readTree(body.get()).at("/exchangeTokens/BSE/0").asText());
            timestamp.set("23-Nov-2026 09:59:00");
            assertThrows(IllegalStateException.class,()->m.quotes(i));
        } finally { server.stop(0); }
    }
}
