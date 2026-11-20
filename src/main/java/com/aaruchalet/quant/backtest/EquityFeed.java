package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
class EquityFeed {
    private final Path path;
    private final EquityScreen screen;
    private final ObjectMapper json;

    EquityFeed(@Value("${trading.equity.snapshot-path:data/equity/snapshot.json}") String path,
               EquityScreen screen, ObjectMapper json) {
        this.path = Path.of(path);
        this.screen = screen;
        this.json = json;
    }
    EquityScreen.Snapshot read() throws IOException {
        return Files.isRegularFile(path) ? json.readValue(path.toFile(), EquityScreen.Snapshot.class) : null;
    }

    @PostMapping("/api/equity/screen/latest")
    ResponseEntity<?> latest(@RequestBody EquityScreen.Config config) throws IOException {
        var snapshot = read();
        if (snapshot == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(java.util.Map.of("error","No research feed configured. Import a dated equity snapshot to screen."));
        return ResponseEntity.ok(screen.screen(new EquityScreen.Request(config,snapshot)));
    }
}
