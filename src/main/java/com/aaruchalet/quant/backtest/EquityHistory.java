package com.aaruchalet.quant.backtest;

import java.time.LocalDate;
import java.util.*;

/** Adjusted, completed daily bars, in exchange-session order. */
final class EquityHistory {
    static Map<String,Double> indicators(List<Candle> bars, LocalDate asOf) {
        if (bars == null || bars.size() < 250) return Map.of();
        var fast = new IndicatorState(9,20,14);
        var macd = new IndicatorState(12,26,14);
        var medium = new IndicatorState(20,50,14);
        var out = new TreeMap<String,Double>();
        double sum200 = 0, volume20 = 0, obv = 0, macdSignal = 0, tr = 0, plus = 0, minus = 0, adx = 0;
        int macdCount = 0, dxCount = 0;
        Candle prev = null;
        for (int i = 0; i < bars.size(); i++) {
            Candle b = bars.get(i);
            if (b == null || b.openTime().toLocalDate().isAfter(asOf) ||
                    prev != null && !b.openTime().toLocalDate().isAfter(prev.openTime().toLocalDate()))
                throw new IllegalArgumentException("Expected distinct, ascending completed daily candles through snapshot date");
            var a = fast.add(b);
            var m = macd.add(b);
            var slow = medium.add(b);
            if (i >= bars.size()-200) sum200 += b.close();
            if (i >= bars.size()-21 && i < bars.size()-1) volume20 += b.volume();
            if (m.emaSlow() != null) {
                double line = m.emaFast()-m.emaSlow();
                macdCount++;
                if (macdCount <= 9) macdSignal += line / 9;
                else macdSignal += .2 * (line - macdSignal);
                out.put("macd",line);
            }
            if (prev != null) {
                obv += Math.signum(b.close()-prev.close()) * b.volume();
                double up = b.high()-prev.high(), down = prev.low()-b.low();
                double range = Math.max(b.high()-b.low(),Math.max(Math.abs(b.high()-prev.close()),Math.abs(b.low()-prev.close())));
                double decay = i <= 14 ? 1 : 13.0/14;
                tr = tr*decay + range;
                plus = plus*decay + (up > 0 && up > down ? up : 0);
                minus = minus*decay + (down > 0 && down > up ? down : 0);
                if (i >= 14) {
                    double dx = plus+minus == 0 ? 0 : 100*Math.abs(plus-minus)/(plus+minus);
                    if (++dxCount <= 14) adx += dx/14;
                    else adx += (dx-adx)/14;
                }
            }
            if (i == bars.size()-6) out.put("ema20FiveAgo",a.emaSlow());
            if (i == bars.size()-1) {
                out.put("close",b.close());
                out.put("ema9",a.emaFast()); out.put("ema20",a.emaSlow()); out.put("ema50",slow.emaSlow());
                out.put("rsi14",a.rsi()); out.put("sma200",sum200/200); out.put("macdSignal",macdSignal);
                out.put("atr14",tr/14); out.put("adx14",adx); out.put("obv",obv);
                out.put("roc20",100*(b.close()/bars.get(i-20).close()-1));
                if (volume20 > 0) out.put("relativeVolume",20*b.volume()/volume20);
            }
            prev = b;
        }
        return out;
    }
}
