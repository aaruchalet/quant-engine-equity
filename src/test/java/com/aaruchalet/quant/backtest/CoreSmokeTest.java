package com.aaruchalet.quant.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** No JUnit needed: javac/java -ea quick validation for the dependency-free core. */
public class CoreSmokeTest {
    public static void main(String[] args) throws Exception {
        LocalDate from = LocalDate.of(2026, 5, 20);
        LocalDate through = LocalDate.of(2026, 11, 19);
        Path csv = Files.createTempFile("synthetic-backtest-smoke", ".csv");
        List<String> lines = new ArrayList<>(List.of("timestamp,open,high,low,close,volume"));
        // Synthetic fixture ONLY: test deterministic execution, NOT historical NIFTY data.
        for (LocalDate day = from.minusDays(5); !day.isAfter(through); day = day.plusDays(1)) {
            if (day.getDayOfWeek().getValue() >= 6) continue;
            int index = 0;
            for (LocalTime t = LocalTime.of(9, 15); !t.isAfter(LocalTime.of(14, 55)); t = t.plusMinutes(5)) {
                double price = 100 + 0.10 * index++;
                lines.add(LocalDateTime.of(day, t) + "," + price + "," + (price+.25) + "," +
                        (price-.25) + "," + price + ",1000");
            }
        }
        Files.write(csv, lines);
        try {
            CsvHistoryRepository.History history = new CsvHistoryRepository().load(csv, through);
            assert history.completeSessions() > 100;
            BacktestRequest request = new BacktestRequest(from, through, "SYNTHETIC_ETF",
                    9, 20, 14, 0.0, 100.0, 0.0, 100.0, false,
                    100000.0, 1.0, 99.0, 100.0,
                    0.0, 0.0, 0.0, 3.0, 5, 1,
                    LocalTime.of(9, 35), LocalTime.of(14, 45), LocalTime.of(14, 55));
            BacktestReport result = new BacktestEngine().run(history, StrategyConfig.from(request));
            assert result.totalTrades() > 0;
            for (BacktestReport.Trade trade : result.trades()) {
                assert !trade.entryAt().toLocalTime().isBefore(LocalTime.of(9,35));
                assert !trade.entryAt().toLocalTime().isAfter(LocalTime.of(14,45));
                assert !trade.exitAt().toLocalTime().isAfter(LocalTime.of(14,55));
                assert trade.entryAt().toLocalDate().equals(trade.exitAt().toLocalDate());
            }
            boolean hasSqoff = result.trades().stream().anyMatch(t -> t.exitReason() == BacktestReport.ExitReason.SQUARE_OFF);
            assert hasSqoff;
            System.out.println("CORE SMOKE OK - sessions=" + result.tradingSessions()
                    + ", trades=" + result.totalTrades() + ", no out-of-window trades");
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
