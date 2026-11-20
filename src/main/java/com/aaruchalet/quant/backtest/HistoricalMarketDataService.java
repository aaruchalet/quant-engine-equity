package com.aaruchalet.quant.backtest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fetches 5-minute candles, atomically caches validated OHLCV and reuses existing backtest CSV loader. */
@Service
public final class HistoricalMarketDataService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 15);
    private static final LocalTime END = LocalTime.of(14, 55);
    private final AngelOneHistoricalClient client;
    private final Path cacheDir;
    private final String exchange;
    private final String symbol;
    private final String symbolToken;
    private final Duration maxAge;
    private final int batchDays;

    public record DownloadedHistory(String symbol, String exchange, String symbolToken,
                                    LocalDate requestedFrom, LocalDate requestedThrough,
                                    boolean cacheHit, int candleCount, Path csv,
                                    CsvHistoryRepository.History validatedHistory) {}

    @Autowired
    public HistoricalMarketDataService(AngelOneHistoricalClient client,
            @Value("${trading.history.cache-dir:data/cache}") String cacheDir,
            @Value("${trading.history.symbol:NIFTYBEES}") String symbol,
            @Value("${trading.angel-one.exchange:NSE}") String exchange,
            @Value("${trading.angel-one.symbol-token:}") String symbolToken,
            @Value("${trading.history.cache-max-age-hours:24}") int maxAgeHours,
            @Value("${trading.history.batch-days:14}") int batchDays) {
        this(client, Path.of(cacheDir), symbol, exchange, symbolToken,
                Duration.ofHours(maxAgeHours), batchDays);
    }

    // Package-private seam for fake clients/temp-directory tests.
    HistoricalMarketDataService(AngelOneHistoricalClient client, Path cacheDir,
                                String symbol, String exchange, String token,
                                Duration maxAge, int batchDays) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_\\-]{1,40}"))
            throw new IllegalArgumentException("Invalid configured history symbol");
        if (exchange == null || !exchange.matches("[A-Z]{2,5}"))
            throw new IllegalArgumentException("Invalid configured exchange");
        if (batchDays < 1 || batchDays > 30 || maxAge.isNegative())
            throw new IllegalArgumentException("Invalid history fetch batch/cache settings");
        this.client = client;
        this.cacheDir = cacheDir;
        this.symbol = symbol;
        this.exchange = exchange;
        this.symbolToken = token;
        this.maxAge = maxAge;
        this.batchDays = batchDays;
    }

    public synchronized DownloadedHistory get(String requestedSymbol, LocalDate from,
                                               LocalDate through, boolean refresh) throws IOException {
        if (requestedSymbol == null || !requestedSymbol.equalsIgnoreCase(symbol))
            throw new IllegalArgumentException("Requested symbol must match configured HISTORY_SYMBOL: " + symbol);
        if (from == null || through == null || from.isAfter(through))
            throw new IllegalArgumentException("Provide valid from and through dates");
        if (through.isAfter(LocalDate.now(IST).minusDays(1)))
            throw new IllegalArgumentException("Only completed IST trading dates can be downloaded");
        if (from.isBefore(through.minusMonths(12)))
            throw new IllegalArgumentException("Limit historical download to 12 calendar months per request");
        if (symbolToken == null || !symbolToken.matches("[0-9]{1,12}"))
            throw new IllegalArgumentException("Set ANGEL_ONE_SYMBOL_TOKEN using the current instrument master");
        // Pre-range candles warm up EMA/RSI and are NOT included in strategy performance metrics.
        LocalDate warmupFrom = from.minusDays(30);
        String name = exchange + "-" + symbol + "-" + symbolToken + "-FIVE_MINUTE-"
                + warmupFrom + "-" + through + ".csv";
        Path csv = cacheDir.resolve(name).normalize();
        boolean hit = !refresh && Files.isRegularFile(csv)
                && Duration.between(Files.getLastModifiedTime(csv).toInstant(), Instant.now()).compareTo(maxAge) < 0;
        if (hit) {
            try {
                CsvHistoryRepository.History history = new CsvHistoryRepository().load(csv, through);
                return new DownloadedHistory(symbol, exchange, symbolToken, from, through,
                        true, history.candles().size(), csv, history);
            } catch (IllegalArgumentException badCache) {
                hit = false; // Never rely on corrupt cache.
            }
        }
        TreeMap<LocalDateTime, Candle> merged = new TreeMap<>();
        for (LocalDate start = warmupFrom; !start.isAfter(through); start = start.plusDays(batchDays)) {
            LocalDate end = start.plusDays(batchDays - 1);
            if (end.isAfter(through)) end = through;
            // 15:25 includes final daily session candle for the vendor; local CSV keeps through 14:55.
            List<Candle> response = client.fetch(exchange, symbolToken,
                    start.atTime(START), end.atTime(LocalTime.of(15, 25)));
            for (Candle bar : response) {
                LocalTime time = bar.openTime().toLocalTime();
                if (time.isBefore(START) || time.isAfter(END)) continue;
                Candle previous = merged.putIfAbsent(bar.openTime(), bar);
                if (previous != null && !previous.equals(bar))
                    throw new IOException("Conflicting duplicate historical candle at " + bar.openTime());
            }
        }
        if (merged.isEmpty()) throw new IOException("Angel One returned no historical candles for the requested range");
        // Reject impossible candle ordering and zero-volume results via the existing CSV session validator.
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,open,high,low,close,volume");
        for (Candle c : merged.values()) {
            lines.add(c.openTime() + "," + c.open() + "," + c.high() + "," + c.low()
                    + "," + c.close() + "," + c.volume());
        }
        Files.createDirectories(cacheDir);
        // Validate before atomically replacing existing cache; cache only successful downloads.
        Path tmp = Files.createTempFile(cacheDir, "history-", ".tmp");
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            CsvHistoryRepository.History history = new CsvHistoryRepository().load(tmp, through);
            if (history.candles().stream().noneMatch(c -> !c.openTime().toLocalDate().isBefore(from)))
                throw new IOException("No complete trading session within requested backtest dates");
            try { Files.move(tmp, csv, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, csv, StandardCopyOption.REPLACE_EXISTING); }
            return new DownloadedHistory(symbol, exchange, symbolToken, from, through,
                    false, history.candles().size(), csv, history);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
