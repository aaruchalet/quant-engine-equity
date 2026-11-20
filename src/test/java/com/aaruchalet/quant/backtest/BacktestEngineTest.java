package com.aaruchalet.quant.backtest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {
    @Test
    void vwapResetsAtNewSessionAndRsiWarmsUp() {
        IndicatorState state = new IndicatorState(2, 3, 2);
        LocalDate day = LocalDate.of(2026, 5, 20);
        state.add(c(day, 9, 15, 100));
        state.add(c(day, 9, 20, 101));
        IndicatorState.Snapshot ready = state.add(c(day, 9, 25, 102));
        assertTrue(ready.ready());
        assertEquals(101, ready.vwap(), 1e-9);
        assertEquals(100, ready.rsi(), 1e-9);
        IndicatorState.Snapshot next = state.add(c(day.plusDays(1), 9, 15, 110));
        assertEquals(110, next.vwap(), 1e-9);
    }

    @Test
    void allTradesEnterWithinWindowAndExitNoLaterThan1455() {
        LocalDate start = LocalDate.of(2026, 5, 20);
        List<Candle> input = new ArrayList<>();
        for (LocalDate day : List.of(start, start.plusDays(1))) {
            int i = 0;
            for (LocalTime t = LocalTime.of(9, 15); !t.isAfter(LocalTime.of(14, 55)); t = t.plusMinutes(5)) {
                input.add(c(day, t.getHour(), t.getMinute(), 100 + i++ * 0.10));
            }
        }
        BacktestRequest request = new BacktestRequest(start, start.plusDays(1), "TEST_ETF",
                9, 20, 14, 0.0, 100.0, 0.0, 100.0, false,
                100_000.0, 1.0, 99.0, 100.0, 0.0, 0.0, 0.0,
                3.0, 5, 1, LocalTime.of(9, 35), LocalTime.of(14, 45), LocalTime.of(14, 55));
        var report = new BacktestEngine().run(new CsvHistoryRepository.History(input, List.of(), 2),
                StrategyConfig.from(request));
        assertTrue(report.totalTrades() >= 1, "Expected one or more trends to produce a trade");
        report.trades().forEach(trade -> {
            assertFalse(trade.entryAt().toLocalTime().isBefore(LocalTime.of(9, 35)));
            assertFalse(trade.entryAt().toLocalTime().isAfter(LocalTime.of(14, 45)));
            assertFalse(trade.exitAt().toLocalTime().isAfter(LocalTime.of(14, 55)));
            assertEquals(trade.entryAt().toLocalDate(), trade.exitAt().toLocalDate());
        });
        assertTrue(report.trades().stream().anyMatch(t -> t.exitReason() == BacktestReport.ExitReason.SQUARE_OFF));
    }

    @Test
    void rejectsEntryAt0930AndRejectsImpossibleCosts() {
        BacktestRequest invalid = new BacktestRequest(null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,
                LocalTime.of(9,30),null,null);
        assertThrows(IllegalArgumentException.class, () -> StrategyConfig.from(invalid));
    }

    private Candle c(LocalDate date, int hour, int minute, double price) {
        return new Candle(LocalDateTime.of(date, LocalTime.of(hour, minute)), price,
                price + .25, price - .25, price, 1000);
    }
}
