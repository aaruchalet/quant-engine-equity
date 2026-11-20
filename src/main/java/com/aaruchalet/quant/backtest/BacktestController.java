package com.aaruchalet.quant.backtest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/backtests")
public class BacktestController {
    private final Path historicalCsv;
    private final String historySymbol;
    public BacktestController(@Value("${trading.history.csv-path}") String csvPath,
                              @Value("${trading.history.symbol}") String historySymbol) {
        this.historicalCsv = Path.of(csvPath);
        this.historySymbol = historySymbol;
    }

    /**
     * Metadata only: never disclose the absolute server file path or CSV contents.
     * Reachability is localhost-only by default via application.yml.
     */
    @GetMapping("/history-status")
    public Map<String, Object> historyStatus() throws IOException {
        boolean present = Files.isRegularFile(historicalCsv) && Files.size(historicalCsv) > 0;
        String filename = historicalCsv.getFileName() == null ? "history.csv" : historicalCsv.getFileName().toString();
        return Map.of(
                "symbol", historySymbol,
                "available", present,
                "fileName", filename,
                "sizeBytes", present ? Files.size(historicalCsv) : 0L
        );
    }

    @PostMapping("/ema-vwap-rsi")
    public BacktestReport backtest(@RequestBody BacktestRequest request) throws IOException {
        StrategyConfig c = StrategyConfig.from(request);
        if (!c.symbol().equalsIgnoreCase(historySymbol)) throw new IllegalArgumentException(
                "Requested symbol does not match configured CSV instrument " + historySymbol);
        CsvHistoryRepository.History history = new CsvHistoryRepository().load(historicalCsv, c.through());
        return new BacktestEngine().run(history, c);
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> errors(Exception e) {
        return ResponseEntity.status(e instanceof IOException ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "Unexpected backtest error" : e.getMessage()));
    }
}
