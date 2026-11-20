package com.aaruchalet.quant.backtest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Generates visibly labeled SYNTHETIC fixtures for clone-and-run demonstrations; not market data. */
public final class DemoHistoryFactory {
    public CsvHistoryRepository.History generate(LocalDate from, LocalDate through) {
        List<Candle> candles = new ArrayList<>();
        int sessions = 0;
        for (LocalDate day = from.minusDays(7); !day.isAfter(through); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            sessions++;
            int n = 0;
            double base = 225 + sessions * 0.09;
            double previous = base;
            for (LocalTime time = LocalTime.of(9, 15); !time.isAfter(LocalTime.of(14, 55)); time = time.plusMinutes(5)) {
                // Predictable deterministic price path; never represented as NSE price history.
                double close = base + 0.055 * n
                        + 2.25 * Math.sin(n / 11.0 + sessions * 0.43)
                        + 0.5 * Math.cos(n / 3.0 + sessions * 0.20);
                double high = Math.max(previous, close) + 0.16;
                double low = Math.min(previous, close) - 0.16;
                candles.add(new Candle(LocalDateTime.of(day, time), previous, high, low, close, 1000 + (n % 11) * 90));
                previous = close;
                n++;
            }
        }
        return new CsvHistoryRepository.History(List.copyOf(candles), List.of(
                "SYNTHETIC DEMO DATA — NOT NSE HISTORY; weekends omitted, exchange holidays not modeled"), sessions);
    }
}
