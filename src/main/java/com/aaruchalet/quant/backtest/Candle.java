package com.aaruchalet.quant.backtest;

import java.time.LocalDateTime;
import java.util.Objects;

/** 5-minute candle; timestamp is its OPEN in Asia/Kolkata exchange time. */
public record Candle(LocalDateTime openTime, double open, double high, double low,
                     double close, double volume) {
    public Candle {
        Objects.requireNonNull(openTime, "openTime");
        if (!finite(open) || !finite(high) || !finite(low) || !finite(close) || !finite(volume)
                || open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0
                || high < Math.max(open, close) || low > Math.min(open, close) || high < low) {
            throw new IllegalArgumentException("Invalid OHLCV candle at " + openTime);
        }
    }
    private static boolean finite(double n) { return Double.isFinite(n); }
    public LocalDateTime closeTime() { return openTime.plusMinutes(5); }
    public double hlc3() { return (high + low + close) / 3.0; }
}
