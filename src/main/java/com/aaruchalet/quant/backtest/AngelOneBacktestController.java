package com.aaruchalet.quant.backtest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** One-request history download + backtest; never requests or places actual orders. */
@RestController
@RequestMapping("/api")
public final class AngelOneBacktestController {
    private final HistoricalMarketDataService history;
    private final String accessKey;

    public record DownloadedBacktestResult(String source, boolean cacheHit,
                                           int validatedCandles, int validSessions,
                                           BacktestReport backtest) {}

    public AngelOneBacktestController(HistoricalMarketDataService history,
                                     @Value("${trading.history.access-key:}") String accessKey) {
        this.history = history;
        this.accessKey = accessKey;
    }

    @PostMapping("/backtests/ema-vwap-rsi/angel-one")
    public DownloadedBacktestResult run(@RequestBody BacktestRequest request,
                                       @RequestParam(defaultValue = "false") boolean refresh,
                                       @RequestHeader(value = "X-Backtest-Key", required = false) String providedKey,
                                       HttpServletRequest servlet) throws IOException {
        authorize(providedKey, servlet);
        StrategyConfig config = StrategyConfig.from(request);
        var downloaded = history.get(config.symbol(), config.from(), config.through(), refresh);
        BacktestReport report = new BacktestEngine().run(downloaded.validatedHistory(), config);
        return new DownloadedBacktestResult("ANGEL_ONE_FIVE_MINUTE", downloaded.cacheHit(),
                downloaded.candleCount(), downloaded.validatedHistory().completeSessions(), report);
    }

    @GetMapping(value = "/history/angel-one.csv", produces = "text/csv")
    public ResponseEntity<ByteArrayResource> export(@RequestParam String symbol,
                                                     @RequestParam LocalDate from,
                                                     @RequestParam LocalDate through,
                                                     @RequestParam(defaultValue = "false") boolean refresh,
                                                     @RequestHeader(value = "X-Backtest-Key", required = false) String providedKey,
                                                     HttpServletRequest servlet) throws IOException {
        authorize(providedKey, servlet);
        var downloaded = history.get(symbol, from, through, refresh);
        String filename = downloaded.symbol() + "-FIVE_MINUTE-" + from + "-" + through + ".csv";
        byte[] csv = Files.readAllBytes(downloaded.csv());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(csv.length)
                .body(new ByteArrayResource(csv));
    }

    private void authorize(String providedKey, HttpServletRequest request) {
        if (!accessKey.isBlank()) {
            if (providedKey == null || !MessageDigest.isEqual(accessKey.getBytes(StandardCharsets.UTF_8),
                    providedKey.getBytes(StandardCharsets.UTF_8)))
                throw new SecurityException("Invalid backtest API access key");
            return;
        }
        // Default app binds 127.0.0.1. If explicitly exposed, require BACKTEST_ACCESS_KEY.
        String remote = request.getRemoteAddr();
        if (!"127.0.0.1".equals(remote) && !"::1".equals(remote) && !"0:0:0:0:0:0:0:1".equals(remote))
            throw new SecurityException("Remote history downloads require BACKTEST_ACCESS_KEY");
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Historical API access denied"));
    }

    @ExceptionHandler(AngelOneHistoricalClient.ProviderException.class)
    public ResponseEntity<Map<String, String>> upstream(AngelOneHistoricalClient.ProviderException e) {
        // 401/403/429 are upstream conditions; keep them separate from local auth failures.
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> errors(Exception e) {
        return ResponseEntity.status(e instanceof IOException ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "Historical download failed" : e.getMessage()));
    }
}
