package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.core.io.Resource;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backtests/vwap-ema-v2/options")
public class IndexOptionsController {
    private final Resource options;
    private final Resource strategy;

    public IndexOptionsController(@Value("${trading.strategy.options-config-path}") String options,
                                  @Value("${trading.strategy.v2-config-path}") String strategy) {
        this.options = SettingsResource.resolve(options);
        this.strategy = SettingsResource.resolve(strategy);
    }

    @PostMapping
    public IndexOptionsReplay.Report replay(@RequestBody IndexOptionsReplay.Request request) throws IOException {
        return new IndexOptionsReplay(IndexOptionsConfig.load(options), VwapEmaV2Config.fromYaml(strategy)).run(request);
    }

    @PostMapping("/{index}")
    public IndexOptionsReplay.Report replayIndex(@PathVariable IndexOptionsReplay.Index index,
                                                  @RequestBody IndexOptionsReplay.Request request) throws IOException {
        if (request.sessions() == null) throw new IllegalArgumentException("Provide sessions");
        var selected = request.sessions().stream().map(session -> {
            if (session == null || session.markets() == null || session.markets().get(index) == null)
                throw new IllegalArgumentException("Missing " + index + " data in session");
            return new IndexOptionsReplay.Session(session.date(), Map.of(index, session.markets().get(index)));
        }).toList();
        return replay(new IndexOptionsReplay.Request(selected));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
