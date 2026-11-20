package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.IndexOptionsReplay.*;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndexOptionsHttpTest {
    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;

    @Test void serializesRealRouteAndRejectsInvalidSession() throws Exception {
        var at = IndexOptionsReplayTest.DATE;
        var request = new Request(List.of(new Session(at, Map.of(Index.NIFTY,
                IndexOptionsReplayTest.series(Index.NIFTY,true,100,90,at)))));
        var response = post(json.writeValueAsString(request));
        assertEquals(200,response.statusCode(),response.body());
        var report = json.readTree(response.body());
        assertEquals(130,report.at("/trades/0/position/quantity").asInt());
        assertEquals("STOP_LOSS",report.at("/trades/0/reason").asString());
        assertEquals(400,post("{\"sessions\":[]}").statusCode());
    }

    private HttpResponse<String> post(String body) throws Exception {
        var url = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port")
                + "/api/backtests/vwap-ema-v2/options");
        var http = HttpRequest.newBuilder(url).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(http,HttpResponse.BodyHandlers.ofString());
    }
}
