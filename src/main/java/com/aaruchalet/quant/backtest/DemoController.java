package com.aaruchalet.quant.backtest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Zero-credential smoke endpoint. Never places real orders. */
@RestController
@RequestMapping("/api/backtests")
public class DemoController {
    @GetMapping("/demo")
    public DemoReport demo() {
        StrategyConfig config = StrategyConfig.from(BacktestRequest.syntheticDefaults());
        BacktestReport report = new BacktestEngine().run(
                new DemoHistoryFactory().generate(config.from(), config.through()), config);
        return new DemoReport("SYNTHETIC DEMO ONLY — not actual historical performance", report);
    }

    public record DemoReport(String dataNotice, BacktestReport result) {}
}
