package com.aaruchalet.quant.backtest;

import java.time.LocalDate;
import com.aaruchalet.quant.backtest.BacktestReport.Side;

/** One continuous indicator series per signal instrument. Crosses expire at the session boundary. */
final class V2SignalState {
    private final IndicatorState indicators;
    private final VwapEmaV2Config config;
    private LocalDate session;
    private IndicatorState.Snapshot previous;
    private Side cross;

    record Decision(Double vwap, Side cross, Side entry, boolean ready,
                    Double emaFast, Double emaSlow, Double rsi, String reason) {
        // Keep test fixtures and older integrations source-compatible.
        Decision(Double vwap, Side cross, Side entry, boolean ready) {
            this(vwap, cross, entry, ready, null, null, null, "UNSPECIFIED");
        }
        boolean permitsOpen(double price) {
            return entry != null && (entry == Side.LONG ? price > vwap : price < vwap);
        }
    }

    V2SignalState(VwapEmaV2Config config) {
        this.config = config;
        indicators = new IndicatorState(config.fastEma(), config.slowEma(), config.rsiPeriod());
    }

    Decision onClose(Candle bar) {
        return onClose(bar, bar);
    }

    Decision onClose(Candle priceBar, Candle bar) {
        if (!bar.openTime().toLocalDate().equals(session)) {
            session = bar.openTime().toLocalDate();
            cross = null;
            previous = null;
        }
        var now = indicators.add(priceBar, bar);
        if (!now.ready()) return new Decision(null, null, null, false, null, null, null, "INDICATORS_WARMING");
        if (previous != null) {
            if (previous.emaFast() <= previous.emaSlow() && now.emaFast() > now.emaSlow()) cross = Side.LONG;
            if (previous.emaFast() >= previous.emaSlow() && now.emaFast() < now.emaSlow()) cross = Side.SHORT;
        }
        previous = now;
        if (now.emaFast().doubleValue() == now.emaSlow().doubleValue()) cross = null;
        boolean qualifies = cross == Side.LONG && now.rsi() > config.longRsiAbove()
                || cross == Side.SHORT && config.shortsEnabled() && now.rsi() < config.shortRsiBelow();
        Side entry = qualifies && BacktestEngineV2.confirmsVwapSide(bar, now.vwap(), cross) ? cross : null;
        String reason = cross == null ? "WAIT_EMA_CROSSOVER"
                : !qualifies ? "WAIT_RSI_CONFIRMATION"
                : entry == null ? "WAIT_VWAP_SIDE" : "ENTRY_SETUP_CONFIRMED";
        return new Decision(now.vwap(), cross, entry, true,
                now.emaFast(), now.emaSlow(), now.rsi(), reason);
    }
}
