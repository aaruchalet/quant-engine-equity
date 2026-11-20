package com.aaruchalet.quant.backtest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Isolated V2 simulation. NEVER sends broker orders and LIVE mode is rejected. */
@RestController
@RequestMapping("/api/backtests")
public class VwapEmaV2Controller {
    private final Path csv;
    private final String symbol;
    private final Path strategyConfig;

    public VwapEmaV2Controller(
            @Value("${trading.history.csv-path}") String csvPath,
            @Value("${trading.history.symbol}") String historySymbol,
            @Value("${trading.strategy.v2-config-path}") String strategyConfigPath) {
        this.csv = Path.of(csvPath);
        this.symbol = historySymbol;
        this.strategyConfig = Path.of(strategyConfigPath);
    }

    @PostMapping("/vwap-ema-v2")
    public BacktestReport run(@RequestBody BacktestRequest request) throws IOException {
        VwapEmaV2Config config = VwapEmaV2Config.fromYaml(strategyConfig);
        if (request != null && (request.feeBps()!=null || request.feePerOrder()!=null))
            throw new IllegalArgumentException("V2 fees come from the strategy YAML; omit feeBps and feePerOrder");
        StrategyConfig settings = StrategyConfig.from(request);
        if (!settings.symbol().equalsIgnoreCase(symbol))
            throw new IllegalArgumentException("Requested V2 symbol must match configured historical CSV instrument");
        CsvHistoryRepository.History history =
                new CsvHistoryRepository().load(csv, settings.through(), config.marketOpen(), config.squareOff());
        if (history.candles().stream().noneMatch(c -> !c.openTime().toLocalDate().isBefore(settings.from())))
            throw new IllegalArgumentException("No complete historical session in the requested date range");
        return new BacktestEngineV2().run(history, settings, config);
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class, IllegalStateException.class})
    public ResponseEntity<Map<String,String>> handle(Exception ex) {
        return ResponseEntity.status(ex instanceof IOException ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "V2 backtest request rejected" : ex.getMessage()));
    }
}
