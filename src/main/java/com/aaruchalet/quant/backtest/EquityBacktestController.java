package com.aaruchalet.quant.backtest;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** Historical NSE/BSE cash-equity OHLCV simulation; no broker call or persistent upload. */
@RestController
@RequestMapping("/api/equity")
class EquityBacktestController {
    private final ObjectMapper mapper;
    EquityBacktestController(ObjectMapper mapper) { this.mapper=mapper; }

    @PostMapping(value="/backtest", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    BacktestReport backtest(@RequestParam("file") MultipartFile file,
            @RequestParam("symbol") String symbol,
            @RequestParam("from") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("through") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate through,
            @RequestParam("initialCapital") double initialCapital) throws IOException {
        String canonical=symbol==null?"":symbol.strip().toUpperCase(Locale.ROOT);
        if(!canonical.matches("[A-Z0-9&_.-]{1,25}"))
            throw new IllegalArgumentException("A valid NSE/BSE equity symbol is required");
        if(file.isEmpty() || file.getSize()>5_000_000L)
            throw new IllegalArgumentException("Upload a nonempty CSV no larger than 5MB");
        if(from==null || through==null || from.isAfter(through) || through.isAfter(LocalDate.now(AngelOneHistoricalClient.IST)))
            throw new IllegalArgumentException("Historical start/end dates must be valid and not in the future");
        if(!Double.isFinite(initialCapital) || initialCapital<100)
            throw new IllegalArgumentException("Initial capital must be at least ₹100");
        // Cash-equity simulation shares the legacy 5-minute V1 logic, with explicit
        // user-uploaded genuine candles and no options chain or vendor history requests.
        var request=mapper.convertValue(Map.of(
                "symbol",canonical,"from",from.toString(),"through",through.toString(),
                "initialCapital",initialCapital),BacktestRequest.class);
        StrategyConfig config=StrategyConfig.from(request);
        Path temp=Files.createTempFile("quant-equity-bars-", ".csv");
        try {
            try(var source=file.getInputStream()) {
                Files.copy(source,temp,StandardCopyOption.REPLACE_EXISTING);
            }
            var history=new CsvHistoryRepository().load(temp,through);
            return new BacktestEngine().run(history,config);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, IOException.class})
    ResponseEntity<Map<String,String>> failure(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error",
                e.getMessage()==null?"Invalid historical CSV backtest input":e.getMessage()));
    }
}
