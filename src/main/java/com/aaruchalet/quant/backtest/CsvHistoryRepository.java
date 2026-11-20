package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed server-configured path; the API cannot supply arbitrary local file paths. */
public final class CsvHistoryRepository {
    public record History(List<Candle> candles, List<String> skippedSessions, int completeSessions) {}

    public History load(Path file, LocalDate through) throws IOException {
        return load(file, through, LocalTime.of(14, 55));
    }

    /** V2 must have genuine 15:00 opening-price bars; V1 remains at 14:55. */
    public History load(Path file, LocalDate through, LocalTime requiredLastBar) throws IOException {
        if (requiredLastBar.isBefore(LocalTime.of(9, 15))
                || requiredLastBar.isAfter(LocalTime.of(15, 25))
                || requiredLastBar.getMinute() % 5 != 0 || requiredLastBar.getSecond() != 0)
            throw new IllegalArgumentException("History cutoff must be a valid NSE 5-minute bar open");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException(
                "No history CSV at " + file + ". Supply real licensed OHLCV candles via HISTORY_CSV_PATH");
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || !lines.getFirst().strip().equalsIgnoreCase("timestamp,open,high,low,close,volume"))
            throw new IllegalArgumentException("CSV header must be timestamp,open,high,low,close,volume");
        TreeMap<LocalDate, TreeMap<LocalTime, Candle>> days = new TreeMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) continue;
            String[] cells = line.split(",", -1);
            if (cells.length != 6) throw new IllegalArgumentException("Expected 6 CSV columns at line " + (i + 1));
            try {
                String rawTime = cells[0].strip().replace(' ', 'T');
                LocalDateTime time = LocalDateTime.parse(rawTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (time.toLocalDate().isAfter(through)) continue;
                if (time.getMinute() % 5 != 0 || time.getSecond() != 0 || time.getNano() != 0)
                    throw new IllegalArgumentException("Invalid 5-minute alignment");
                Candle c = new Candle(time, Double.parseDouble(cells[1]), Double.parseDouble(cells[2]),
                        Double.parseDouble(cells[3]), Double.parseDouble(cells[4]), Double.parseDouble(cells[5]));
                Candle prior = days.computeIfAbsent(time.toLocalDate(), k -> new TreeMap<>())
                        .putIfAbsent(time.toLocalTime(), c);
                if (prior != null) throw new IllegalArgumentException("Duplicate candle timestamp");
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid history row " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        List<Candle> complete = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int validDays = 0;
        for (var day : days.entrySet()) {
            TreeMap<LocalTime, Candle> bars = day.getValue();
            boolean allPresent = true;
            for (LocalTime t = LocalTime.of(9, 15); !t.isAfter(requiredLastBar); t = t.plusMinutes(5)) {
                if (!bars.containsKey(t)) { allPresent = false; break; }
            }
            if (!allPresent) {
                skipped.add(day.getKey() + ": missing required 5-minute session candle(s)");
                continue;
            }
            // An index dataset with 0 volume cannot support valid session VWAP.
            if (bars.values().stream().filter(b -> !b.openTime().toLocalTime().isAfter(requiredLastBar))
                    .mapToDouble(Candle::volume).sum() <= 0) {
                skipped.add(day.getKey() + ": zero traded volume; VWAP not valid");
                continue;
            }
            validDays++;
            // Do not consume candles after square-off. They cannot affect a closed intraday strategy.
            bars.values().stream().filter(b -> !b.openTime().toLocalTime().isAfter(requiredLastBar))
                    .forEach(complete::add);
        }
        if (complete.isEmpty()) throw new IllegalArgumentException(
                "No complete 5-minute sessions through " + requiredLastBar + " available through "
                        + through + "; V2 requires real 15:00 OPEN candles, not inferred 14:55 closes");
        return new History(List.copyOf(complete), List.copyOf(skipped), validDays);
    }
}
