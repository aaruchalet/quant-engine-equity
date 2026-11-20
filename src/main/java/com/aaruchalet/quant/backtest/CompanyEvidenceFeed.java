package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A configured licensed feed uses the existing EquityScreen snapshot format. No client-supplied URL. */
@Component
class CompanyEvidenceFeed {
    record Read(EquityScreen.Snapshot snapshot,boolean stale,Instant retrievedAt) {}
    private final EquityFeed local;
    private final ObjectMapper json;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private Read cached;
    private Instant nextFetch=Instant.MIN;

    CompanyEvidenceFeed(EquityFeed local,ObjectMapper json,@Value("${trading.equity.provider-url:}") String url) {
        this.local=local;
        this.json=json;
        this.endpoint=url.isBlank()?null:URI.create(url);
        if(endpoint!=null && !"https".equalsIgnoreCase(endpoint.getScheme()))
            throw new IllegalArgumentException("Company evidence provider requires HTTPS");
    }
    synchronized Read read() throws IOException,InterruptedException {
        Instant now=Instant.now();
        if(endpoint==null) return new Read(local.read(),false,now);
        if(cached!=null && now.isBefore(nextFetch)) return cached;
        var req=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12)).header("Accept","application/json").GET().build();
        try {
            var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                if(response.statusCode()!=200) throw new IOException("Company feed unavailable");
                byte[] payload=body.readNBytes(32_000_001);
                if(payload.length>32_000_000) throw new IOException("Company feed exceeded limit");
                var snapshot=json.readValue(payload,EquityScreen.Snapshot.class);
                if(snapshot==null || snapshot.stocks()==null || snapshot.asOf()==null || snapshot.asOf().toInstant().isAfter(Instant.now()))
                    throw new IOException("Invalid provider snapshot");
                cached=new Read(snapshot,false,Instant.now());
            }
            nextFetch=now.plusSeconds(30);
        } catch(IOException | RuntimeException ex) {
            cached=cached==null ? new Read(local.read(),true,now) : new Read(cached.snapshot,true,cached.retrievedAt);
            nextFetch=now.plusSeconds(15);
        }
        return cached;
    }
}
