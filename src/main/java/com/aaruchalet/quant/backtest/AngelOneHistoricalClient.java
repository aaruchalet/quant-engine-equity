package com.aaruchalet.quant.backtest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Read-only Angel One SmartAPI historical-candle client; never submits trading orders. */
@Component
public final class AngelOneHistoricalClient {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final String PATH = "/rest/secure/angelbroking/historical/v1/getCandleData";
    private static final DateTimeFormatter PARAM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private final HttpClient http;
    private final JsonMapper json;
    private final URI endpoint;
    private final Credentials credentials;
    private final Duration interval;
    private Instant lastRequest = Instant.EPOCH;

    public record Credentials(String apiKey, String jwt, String jwtFile,
                              String localIp, String publicIp, String macAddress) {}

    /** Messages identify the failing setting without including its value. */
    static final class ConfigurationException extends IllegalArgumentException {
        ConfigurationException(String message) {super(message);}
    }

    public static final class ProviderException extends IOException {
        private final int httpStatus;
        public ProviderException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
        public int httpStatus() { return httpStatus; }
    }

    @Autowired
    public AngelOneHistoricalClient(JsonMapper json,
            @Value("${trading.angel-one.api-key:}") String apiKey,
            @Value("${trading.angel-one.jwt:}") String jwt,
            @Value("${trading.angel-one.jwt-file:}") String jwtFile,
            @Value("${trading.angel-one.local-ip:}") String localIp,
            @Value("${trading.angel-one.public-ip:}") String publicIp,
            @Value("${trading.angel-one.mac-address:}") String macAddress) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), json,
                URI.create("https://apiconnect.angelone.in" + PATH),
                new Credentials(apiKey, jwt, jwtFile, localIp, publicIp, macAddress),
                Duration.ofMillis(1100));
    }

    // Package-private constructor permits deterministic local HTTP tests without broker credentials.
    AngelOneHistoricalClient(HttpClient http, JsonMapper json, URI endpoint,
                             Credentials credentials, Duration minRequestInterval) {
        if (!endpoint.getScheme().equals("https") && !endpoint.getHost().equals("127.0.0.1")
                && !endpoint.getHost().equals("localhost"))
            throw new IllegalArgumentException("History provider must use HTTPS");
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.interval = minRequestInterval;
    }

    /** Serialized and conservative (<=1 request/second) to limit provider rate-pressure. */
    public synchronized List<Candle> fetch(String exchange, String symbolToken,
                                           LocalDateTime from, LocalDateTime until) throws IOException {
        if (!exchange.matches("[A-Z]{2,5}") || !symbolToken.matches("[0-9]{1,12}"))
            throw new ConfigurationException("Invalid exchange or Angel One symbol token");
        if (until.isBefore(from)) throw new ConfigurationException("Invalid date range");
        String requestBody = "{\"exchange\":\"" + exchange + "\",\"symboltoken\":\"" + symbolToken
                + "\",\"interval\":\"FIVE_MINUTE\",\"fromdate\":\"" + PARAM_TIME.format(from)
                + "\",\"todate\":\"" + PARAM_TIME.format(until) + "\"}";
        throttle();
        HttpRequest req = authenticated(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        HttpResponse<String> response;
        try {
            response = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Historical download interrupted", e);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403)
            throw new ProviderException(status, "Angel One rejected historical-data authentication or access (HTTP " + status
                    + "). Check JWT expiry, API permissions and rate restrictions");
        if (status == 429)
            throw new ProviderException(status, "Angel One historical API rate limit (429); try again later");
        if (status < 200 || status >= 300)
            throw new ProviderException(status, "Angel One historical API HTTP " + status + "; no candles cached");

        final JsonNode root;
        try { root = json.readTree(response.body()); }
        catch (RuntimeException e) { throw new ProviderException(502, "Angel One returned invalid JSON"); }
        if (root == null || !root.isObject()) throw new ProviderException(502, "Invalid historical API response");
        if (!root.path("status").asBoolean(false)) {
            String error = safeCode(root.path("errorcode").asText(""));
            throw new ProviderException(502, "Angel One historical API declined request (code " + error + ")");
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) throw new ProviderException(502, "Angel One returned no historical-candle array");
        List<Candle> candles = new ArrayList<>();
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 6) throw new ProviderException(502, "Malformed candle received from Angel One");
            try {
                for (int n = 1; n <= 5; n++)
                    if (row.get(n) == null || !row.get(n).isNumber())
                        throw new IllegalArgumentException("OHLCV field is not numeric");
                LocalDateTime at = parseTime(row.get(0).asText());
                if (at.isBefore(from) || at.isAfter(until)) continue;
                // Broker may return an additional 15:30 close or pre-market data: ignore outside session.
                if (at.toLocalTime().isBefore(java.time.LocalTime.of(9, 15))
                        || at.toLocalTime().isAfter(java.time.LocalTime.of(15, 25))) continue;
                if (at.getMinute() % 5 != 0 || at.getSecond() != 0 || at.getNano() != 0)
                    throw new IllegalArgumentException("Unaligned candle timestamp");
                candles.add(new Candle(at, row.get(1).asDouble(), row.get(2).asDouble(),
                        row.get(3).asDouble(), row.get(4).asDouble(), row.get(5).asDouble()));
            } catch (RuntimeException e) {
                throw new ProviderException(502, "Invalid OHLCV candle in Angel One response");
            }
        }
        return List.copyOf(candles);
    }

    HttpRequest.Builder authenticated(URI endpoint) throws IOException {
        String bearer = resolveJwt();
        require(credentials.apiKey(), "ANGEL_ONE_API_KEY");
        require(credentials.localIp(), "ANGEL_ONE_CLIENT_LOCAL_IP");
        require(credentials.publicIp(), "ANGEL_ONE_CLIENT_PUBLIC_IP");
        require(credentials.macAddress(), "ANGEL_ONE_MAC_ADDRESS");
        return HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + bearer)
                .header("X-PrivateKey", credentials.apiKey().strip())
                .header("X-ClientLocalIP", credentials.localIp().strip())
                .header("X-ClientPublicIP", credentials.publicIp().strip())
                .header("X-MACAddress", credentials.macAddress().strip())
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    static LocalDateTime parseTime(String raw) {
        String t = raw.strip();
        try { return OffsetDateTime.parse(t).atZoneSameInstant(IST).toLocalDateTime(); }
        catch (RuntimeException ignored) { /* Some providers omit offset */ }
        try { return Instant.parse(t).atZone(IST).toLocalDateTime(); }
        catch (RuntimeException ignored) { /* Local timestamp */ }
        return LocalDateTime.parse(t.replace(' ', 'T'));
    }

    private String resolveJwt() throws IOException {
        String jwt = credentials.jwt() == null ? "" : credentials.jwt().strip();
        if (jwt.isEmpty() && credentials.jwtFile() != null && !credentials.jwtFile().isBlank()) {
            Path p = Path.of(credentials.jwtFile());
            if (!Files.isRegularFile(p)) throw new ConfigurationException("ANGEL_ONE_JWT_FILE does not point to a regular file");
            try (var lines = Files.lines(p)) { jwt = lines.findFirst().orElse("").strip(); }
        }
        if (jwt.regionMatches(true, 0, "Bearer ", 0, 7)) jwt = jwt.substring(7).strip();
        require(jwt, "ANGEL_ONE_JWT_TOKEN or ANGEL_ONE_JWT_FILE");
        // Header injection prevention; format validation does not establish authentication validity.
        if (!jwt.matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+"))
            throw new ConfigurationException("Invalid ANGEL_ONE_JWT_TOKEN or ANGEL_ONE_JWT_FILE token format");
        return jwt;
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank())
            throw new ConfigurationException("Missing required environment setting: " + key);
        if (value.chars().anyMatch(c -> c < 32 || c > 126))
            throw new ConfigurationException("Invalid header configuration: " + key);
    }

    private static String safeCode(String code) {
        return code.matches("[A-Za-z0-9_\\-]{0,32}") ? code : "unknown";
    }

    private void throttle() throws IOException {
        Duration delay = interval.minus(Duration.between(lastRequest, Instant.now()));
        if (!delay.isNegative() && !delay.isZero()) {
            try { Thread.sleep(delay.toMillis() + 1); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Historical download interrupted", e);
            }
        }
        lastRequest = Instant.now();
    }
}
