package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backtests/vwap-ema-v2/options")
public class IndexOptionsController {
    private final Path options;
    private final Path strategy;

    public IndexOptionsController(@Value("${trading.strategy.options-config-path}") String options,
                                  @Value("${trading.strategy.v2-config-path}") String strategy) {
        this.options = Path.of(options);
        this.strategy = Path.of(strategy);
    }

    @PostMapping
    public IndexOptionsReplay.Report replay(@RequestBody IndexOptionsReplay.Request request) throws IOException {
        return new IndexOptionsReplay(IndexOptionsConfig.load(options), VwapEmaV2Config.fromYaml(strategy)).run(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
