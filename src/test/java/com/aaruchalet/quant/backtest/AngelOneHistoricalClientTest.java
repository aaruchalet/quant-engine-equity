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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AngelOneHistoricalClientTest {
    private static final AngelOneHistoricalClient.Credentials VALID =
            new AngelOneHistoricalClient.Credentials("fake-api-key", "abc.def.ghi", "",
                    "127.0.0.1", "198.51.100.1", "02:00:00:00:00:01");

    @Test
    void downloadsFiveMinuteCandlesWithRealSmartApiHeaders() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(AngelOneHistoricalClient.PATH, exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] content = """
                    {"status":true,"message":"SUCCESS","errorcode":"","data":[
                     ["2026-11-19T09:15:00+05:30",250.1,251.3,249.9,250.7,1500],
                     ["2026-11-19T09:20:00+05:30",250.7,252.0,250.4,251.5,1900]
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        try {
            var client = client(server, VALID);
            var result = client.fetch("NSE", "12345",
                    LocalDateTime.parse("2026-11-19T09:15:00"),
                    LocalDateTime.parse("2026-11-19T15:25:00"));
            assertEquals(2, result.size());
            assertEquals(LocalTime.of(9, 15), result.getFirst().openTime().toLocalTime());
            assertEquals(1500.0, result.getFirst().volume());
            assertEquals("Bearer abc.def.ghi", auth.get());
            assertTrue(requestBody.get().contains("\"interval\":\"FIVE_MINUTE\""));
            assertTrue(requestBody.get().contains("\"symboltoken\":\"12345\""));
        } finally { server.stop(0); }
    }

    @Test
    void remoteAuthFailureDoesNotLeakCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(AngelOneHistoricalClient.PATH, exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            var exception = assertThrows(AngelOneHistoricalClient.ProviderException.class,
                    () -> client(server, VALID).fetch("NSE", "12345",
                            LocalDateTime.parse("2026-11-19T09:15:00"),
                            LocalDateTime.parse("2026-11-19T15:25:00")));
            assertEquals(403, exception.httpStatus());
            assertFalse(exception.getMessage().contains(VALID.jwt()));
        } finally { server.stop(0); }
    }

    @Test
    void jwtCanBeReadFromExplicitLocalSessionFile(@TempDir Path dir) throws Exception {
        Path jwtFile = dir.resolve("jwt.txt");
        Files.writeString(jwtFile, "abc.def.ghi\nrefresh-secret\nfeed-secret\n");
        var credentials = new AngelOneHistoricalClient.Credentials("fake-api-key", "",
                jwtFile.toString(), "127.0.0.1", "198.51.100.1", "02:00:00:00:00:01");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(AngelOneHistoricalClient.PATH, exchange -> {
            boolean valid = "Bearer abc.def.ghi".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"status\":true,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            assertTrue(client(server, credentials).fetch("NSE", "12345",
                    LocalDateTime.parse("2026-11-19T09:15:00"),
                    LocalDateTime.parse("2026-11-19T15:25:00")).isEmpty());
        } finally { server.stop(0); }
    }

    @Test
    void parsesOffsetTimestampsAsIst() {
        assertEquals(LocalDateTime.parse("2026-11-19T09:15:00"),
                AngelOneHistoricalClient.parseTime("2026-11-19T03:45:00Z"));
        assertEquals(LocalDateTime.parse("2026-11-19T09:15:00"),
                AngelOneHistoricalClient.parseTime("2026-11-19T09:15:00+05:30"));
    }

    private AngelOneHistoricalClient client(HttpServer server, AngelOneHistoricalClient.Credentials credentials) {
        return new AngelOneHistoricalClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + AngelOneHistoricalClient.PATH),
                credentials, Duration.ZERO);
    }
}
