package com.aaruchalet.quant.backtest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalMarketDataServiceTest {
    @Test
    void downloadsCachesAndRefreshesWithoutReaccessingProviderOnCacheHit(@TempDir Path tmp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = fakeHistoryServer(calls);
        server.start();
        try {
            var client = client(server);
            var svc = new HistoricalMarketDataService(client, tmp, "NIFTYBEES", "NSE", "12345",
                    Duration.ofHours(24), 14);
            var first = svc.get("NIFTYBEES", LocalDate.parse("2026-11-02"),
                    LocalDate.parse("2026-11-03"), false);
            assertFalse(first.cacheHit());
            assertTrue(first.candleCount() >= 138);
            assertTrue(first.validatedHistory().completeSessions() >= 2);
            assertTrue(Files.readString(first.csv()).startsWith("timestamp,open,high,low,close,volume"));
            int afterFetch = calls.get();
            assertTrue(afterFetch >= 3, "warmup means multiple broker requests");
            var second = svc.get("NIFTYBEES", LocalDate.parse("2026-11-02"),
                    LocalDate.parse("2026-11-03"), false);
            assertTrue(second.cacheHit());
            assertEquals(afterFetch, calls.get());
            var refreshed = svc.get("NIFTYBEES", LocalDate.parse("2026-11-02"),
                    LocalDate.parse("2026-11-03"), true);
            assertFalse(refreshed.cacheHit());
            assertTrue(calls.get() > afterFetch);
            assertThrows(IllegalArgumentException.class,
                    () -> svc.get("NIFTY", LocalDate.parse("2026-11-02"),
                            LocalDate.parse("2026-11-03"), false));
        } finally { server.stop(0); }
    }

    @Test
    void forbidsFuturePeriodsAndArbitraryInstrument(@TempDir Path dir) throws Exception {
        var svc = new HistoricalMarketDataService(clientNotCalled(), dir, "NIFTYBEES", "NSE", "12345",
                Duration.ofHours(1), 14);
        var tomorrow = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1);
        assertThrows(IllegalArgumentException.class, () -> svc.get("NIFTYBEES",
                tomorrow, tomorrow.plusDays(1), false));
        assertThrows(IllegalArgumentException.class, () -> svc.get("../secret",
                LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03"), false));
    }

    private HttpServer fakeHistoryServer(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = JsonMapper.builder().build();
        server.createContext(AngelOneHistoricalClient.PATH, exchange -> {
            calls.incrementAndGet();
            var json = mapper.readTree(exchange.getRequestBody().readAllBytes());
            LocalDate from = LocalDate.parse(json.get("fromdate").asText().substring(0, 10));
            LocalDate through = LocalDate.parse(json.get("todate").asText().substring(0, 10));
            StringBuilder result = new StringBuilder("{\"status\":true,\"data\":[");
            boolean first = true;
            for (var d = from; !d.isAfter(through); d = d.plusDays(1)) {
                if (d.getDayOfWeek().getValue() > 5) continue;
                for (var t = LocalTime.of(9, 15); !t.isAfter(LocalTime.of(14, 55)); t = t.plusMinutes(5)) {
                    if (!first) result.append(',');
                    first = false;
                    double price = 200.0 + d.getDayOfMonth() * 0.02 + t.getMinute() * 0.001;
                    result.append("[\"").append(LocalDateTime.of(d,t)).append("+05:30\",")
                          .append(price).append(',').append(price + 1).append(',')
                          .append(price - 1).append(',').append(price + 0.5).append(",1000]");
                }
            }
            result.append("]}");
            byte[] response = result.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private AngelOneHistoricalClient client(HttpServer server) {
        return new AngelOneHistoricalClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + AngelOneHistoricalClient.PATH),
                new AngelOneHistoricalClient.Credentials("key", "abc.def.ghi", "",
                        "127.0.0.1", "198.51.100.1", "02:00:00:00:00:01"), Duration.ZERO);
    }

    private AngelOneHistoricalClient clientNotCalled() {
        return new AngelOneHistoricalClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:49162" + AngelOneHistoricalClient.PATH),
                new AngelOneHistoricalClient.Credentials("key", "abc.def.ghi", "",
                        "127.0.0.1", "198.51.100.1", "02:00:00:00:00:01"), Duration.ZERO);
    }
}
