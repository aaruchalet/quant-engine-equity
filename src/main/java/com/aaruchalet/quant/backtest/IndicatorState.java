package com.aaruchalet.quant.backtest;

import java.time.LocalDate;

/** O(1) incremental, warmup-safe EMA(SMA seed), Wilder RSI and per-session HLC3 VWAP. */
public final class IndicatorState {
    private final int fastPeriod, slowPeriod, rsiPeriod;
    private int bars, deltaCount;
    private double fastSeed, slowSeed, gainSeed, lossSeed, avgGain, avgLoss;
    private Double fast, slow, rsi, previousClose;
    private LocalDate session;
    private double cumulativePriceVolume, cumulativeVolume;

    public IndicatorState(int fastPeriod, int slowPeriod, int rsiPeriod) {
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || rsiPeriod < 2)
            throw new IllegalArgumentException("Invalid indicator periods");
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.rsiPeriod = rsiPeriod;
    }

    public Snapshot add(Candle b) {
        return add(b, b);
    }

    public Snapshot add(Candle priceBar, Candle b) {
        if (!priceBar.openTime().equals(b.openTime())) throw new IllegalArgumentException("Unaligned signal and VWAP bars");
        LocalDate currentDate = b.openTime().toLocalDate();
        if (!currentDate.equals(session)) {
            session = currentDate;
            cumulativePriceVolume = 0;
            cumulativeVolume = 0;
        }
        // Includes 09:15-09:35 candles, even though entries cannot start yet.
        cumulativePriceVolume += b.hlc3() * b.volume();
        cumulativeVolume += b.volume();
        bars++;
        double price = priceBar.close();

        if (fast == null) {
            fastSeed += price;
            if (bars == fastPeriod) fast = fastSeed / fastPeriod;
        } else fast += (price - fast) * 2.0 / (fastPeriod + 1.0);

        if (slow == null) {
            slowSeed += price;
            if (bars == slowPeriod) slow = slowSeed / slowPeriod;
        } else slow += (price - slow) * 2.0 / (slowPeriod + 1.0);

        if (previousClose != null) {
            double change = price - previousClose;
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            if (rsi == null) {
                gainSeed += gain;
                lossSeed += loss;
                deltaCount++;
                if (deltaCount == rsiPeriod) {
                    avgGain = gainSeed / rsiPeriod;
                    avgLoss = lossSeed / rsiPeriod;
                    rsi = calculateRsi();
                }
            } else {
                avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod;
                avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod;
                rsi = calculateRsi();
            }
        }
        previousClose = price;
        Double vwap = cumulativeVolume > 0 ? cumulativePriceVolume / cumulativeVolume : null;
        return new Snapshot(fast, slow, rsi, vwap);
    }

    private double calculateRsi() {
        if (avgGain == 0 && avgLoss == 0) return 50.0;
        if (avgLoss == 0) return 100.0;
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss);
    }

    public record Snapshot(Double emaFast, Double emaSlow, Double rsi, Double vwap) {
        public boolean ready() {
            return emaFast != null && emaSlow != null && rsi != null && vwap != null;
        }
    }
}
