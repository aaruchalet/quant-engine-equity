package com.aaruchalet.quant.backtest;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import static org.junit.jupiter.api.Assertions.*;

class DemoHistoryFactoryTest {
    @Test
    void syntheticDemoHasOnlyProperlyAlignedSessionBarsAndProducesReport() {
        LocalDate from = LocalDate.of(2026, 5, 20);
        LocalDate through = LocalDate.of(2026, 11, 19);
        var history = new DemoHistoryFactory().generate(from, through);
        assertTrue(history.completeSessions() >= 100);
        assertTrue(history.skippedSessions().getFirst().contains("SYNTHETIC"));
        assertTrue(history.candles().stream().allMatch(c ->
                !c.openTime().toLocalTime().isBefore(LocalTime.of(9, 15))
                        && !c.openTime().toLocalTime().isAfter(LocalTime.of(14, 55))));
        var report = new BacktestEngine().run(history,
                StrategyConfig.from(new BacktestRequest(from, through, "SYNTHETIC_DEMO",
                    null,null,null,null,null,null,null,null,null,null,null,null,null,
                    null,null,null,null,null,null,null,null)));
        assertEquals("SYNTHETIC_DEMO", report.symbol());
        assertTrue(report.candlesProcessed() > 1000);
        report.trades().forEach(t -> {
            assertFalse(t.entryAt().toLocalTime().isBefore(LocalTime.of(9, 35)));
            assertFalse(t.entryAt().toLocalTime().isAfter(LocalTime.of(14, 45)));
            assertFalse(t.exitAt().toLocalTime().isAfter(LocalTime.of(14, 55)));
        });
    }
}
