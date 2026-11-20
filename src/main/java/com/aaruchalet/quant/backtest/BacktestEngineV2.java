package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.BacktestReport.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Independent read-only V2 backtester.
 *
 * Decision order: EMA crossover -> RSI -> time/risk/position -> VWAP FINAL candle
 * touch-and-rebound. A single qualifying candle suffices; NO bounce counters.
 * All signals use completed candles; fills happen at the next bar OPEN.
 */
public final class BacktestEngineV2 {
    private record PendingSignal(Side side, double confirmedVwap) {}
    private record Position(Side side, LocalDateTime entryAt, double entryPrice,
                            double stop, double target, double entryFee, long qty) {}
    private record Closed(Trade trade) {}

    public BacktestReport run(CsvHistoryRepository.History history, StrategyConfig request,
                              VwapEmaV2Config cfg) {
        IndicatorState indicators = new IndicatorState(cfg.fastEma(), cfg.slowEma(), cfg.rsiPeriod());
        List<Trade> trades = new ArrayList<>();
        List<EquityPoint> curve = new ArrayList<>();
        Set<LocalDate> tested = new HashSet<>();
        double equity = request.initialCapital(), peak = equity, maxDrawdown = 0;
        curve.add(new EquityPoint(request.from().atStartOfDay(), equity));
        Position position = null;
        PendingSignal pending = null;
        boolean pendingExit = false, bullishCrossActive = false, bearishCrossActive = false;
        Double lastFast = null, lastSlow = null;
        LocalDate priorDate = null;
        double dailyPnl = 0;
        int entriesToday = 0, processed = 0;
        double slip = request.slippageBps() / 10_000.0;
        if (!Double.isFinite(slip) || slip < 0 || slip > 0.1)
            throw new IllegalArgumentException("Invalid V2 slippage");
        for (Candle bar : history.candles()) {
            LocalDate date = bar.openTime().toLocalDate();
            LocalTime time = bar.openTime().toLocalTime();
            if (!date.equals(priorDate)) {
                if (position != null) throw new IllegalStateException("V2 position carried overnight; check square-off data");
                priorDate = date;
                pending = null;
                pendingExit = false;
                bullishCrossActive = false;
                bearishCrossActive = false;
                lastFast = null;
                lastSlow = null;
                dailyPnl = 0;
                entriesToday = 0;
            }
            boolean inRange = !date.isBefore(request.from()) && !date.isAfter(request.through());
            if (inRange) { tested.add(date); processed++; }

            // Exits at a known bar OPEN execute before reading this candle's future high/low/close.
            if (position != null && (time.equals(cfg.squareOff()) || pendingExit)) {
                ExitReason reason = time.equals(cfg.squareOff()) ? ExitReason.SQUARE_OFF : ExitReason.TREND_REVERSAL;
                Trade t = close(position, bar.openTime(), bar.open(), reason, request);
                trades.add(t);
                dailyPnl += t.netProfit();
                equity += t.netProfit();
                curve.add(new EquityPoint(t.exitAt(), equity));
                peak = Math.max(peak, equity);
                maxDrawdown = Math.max(maxDrawdown, 100*(peak-equity)/peak);
                position = null;
                pendingExit = false;
            }
            if (pending != null && inRange && position == null
                    && !time.isBefore(cfg.entryStart()) && !time.isAfter(cfg.lastEntry())
                    && entriesToday < cfg.maxTradesPerDay()
                    && dailyPnl > -cfg.maxDailyLossInr() && dailyPnl < cfg.maxDailyProfitInr()) {
                // Revalidate the final VWAP side at NEXT candle OPEN, without seeing its close.
                if ((pending.side()==Side.LONG && bar.open()>pending.confirmedVwap())
                        || (pending.side()==Side.SHORT && bar.open()<pending.confirmedVwap())) {
                    position = enter(pending.side(), bar, equity, request, cfg);
                    if (position != null) entriesToday++;
                }
            }
            pending = null;

            if (position != null && time.isBefore(cfg.squareOff())) {
                ExitReason reason = null;
                double rawExit = 0;
                boolean atOpen = false;
                if (position.side()==Side.LONG) {
                    if (bar.open()<=position.stop()) {
                        reason=ExitReason.STOP_LOSS; rawExit=bar.open(); atOpen=true;
                    } else if (bar.open()>=position.target()) {
                        reason=ExitReason.TARGET; rawExit=bar.open(); atOpen=true;
                    } else if (bar.low()<=position.stop()) {
                        reason=ExitReason.STOP_LOSS; rawExit=position.stop();
                    } else if (bar.high()>=position.target()) {
                        reason=ExitReason.TARGET; rawExit=position.target();
                    }
                } else {
                    if (bar.open()>=position.stop()) {
                        reason=ExitReason.STOP_LOSS; rawExit=bar.open(); atOpen=true;
                    } else if (bar.open()<=position.target()) {
                        reason=ExitReason.TARGET; rawExit=bar.open(); atOpen=true;
                    } else if (bar.high()>=position.stop()) {
                        reason=ExitReason.STOP_LOSS; rawExit=position.stop();
                    } else if (bar.low()<=position.target()) {
                        reason=ExitReason.TARGET; rawExit=position.target();
                    }
                }
                if (reason!=null) {
                    Trade t=close(position, atOpen?bar.openTime():bar.closeTime(),rawExit,reason,request);
                    trades.add(t);
                    dailyPnl += t.netProfit();
                    equity += t.netProfit();
                    curve.add(new EquityPoint(t.exitAt(),equity));
                    peak=Math.max(peak,equity);
                    maxDrawdown=Math.max(maxDrawdown,100*(peak-equity)/peak);
                    position=null;
                    pendingExit=false;
                }
            }

            // Only after the candle is COMPLETE do EMA/RSI/VWAP snapshots become known.
            IndicatorState.Snapshot s=indicators.add(bar);
            if (!s.ready()) continue;
            if (lastFast!=null && lastSlow!=null) {
                if (lastFast<=lastSlow && s.emaFast()>s.emaSlow()) {
                    bullishCrossActive=true; bearishCrossActive=false;
                } else if (lastFast>=lastSlow && s.emaFast()<s.emaSlow()) {
                    bearishCrossActive=true; bullishCrossActive=false;
                }
            }
            lastFast=s.emaFast(); lastSlow=s.emaSlow();
            // Cross-session or no-crossover positive EMA alignment alone is insufficient.
            if (s.emaFast()<=s.emaSlow()) bullishCrossActive=false;
            if (s.emaFast()>=s.emaSlow()) bearishCrossActive=false;
            if (!inRange || time.equals(cfg.squareOff())) continue;

            LocalTime decisionAt=bar.closeTime().toLocalTime();
            boolean eligible=!decisionAt.isBefore(cfg.entryStart())
                    && !decisionAt.isAfter(cfg.lastEntry()) &&
                    !decisionAt.isBefore(cfg.strategyStart())
                    && entriesToday<cfg.maxTradesPerDay()
                    && dailyPnl>-cfg.maxDailyLossInr() && dailyPnl<cfg.maxDailyProfitInr();
            if (position!=null) {
                // Optional *independent* exit reasons; never exit on loss of composite entry condition.
                if (decisionAt.isBefore(cfg.squareOff())) {
                    if (cfg.exitOnVwapInvalidation() &&
                            ((position.side()==Side.LONG && bar.close()<s.vwap()) ||
                             (position.side()==Side.SHORT && bar.close()>s.vwap()))) pendingExit=true;
                    if (cfg.exitOnEmaReversal() &&
                            ((position.side()==Side.LONG && bearishCrossActive) ||
                             (position.side()==Side.SHORT && bullishCrossActive))) pendingExit=true;
                }
                continue;
            }
            if (!eligible || pendingExit) continue;
            // FIRST: confirmed current-session EMA crossover and continued alignment.
            boolean longEma=bullishCrossActive && s.emaFast()>s.emaSlow();
            boolean shortEma=cfg.shortsEnabled() && bearishCrossActive && s.emaFast()<s.emaSlow();
            // SECOND: strict RSI entry threshold (not an RSI-based exit).
            boolean longRsi=cfg.rsiEnabled() && s.rsi()>cfg.longRsiAbove();
            boolean shortRsi=cfg.rsiEnabled() && s.rsi()<cfg.shortRsiBelow();
            if (!((longEma && longRsi)||(shortEma && shortRsi))) continue;
            // LAST: VWAP touch/rebound or rejection on THIS completed candle; count NOTHING.
            if (longEma && longRsi &&
                    confirmsFinalVwapBounce(bar, s.vwap(), Side.LONG, cfg.touchToleranceBps())) {
                pending=new PendingSignal(Side.LONG,s.vwap());
            } else if (shortEma && shortRsi &&
                    confirmsFinalVwapBounce(bar, s.vwap(), Side.SHORT, cfg.touchToleranceBps())) {
                pending=new PendingSignal(Side.SHORT,s.vwap());
            }
        }
        if (position!=null)
            throw new IllegalStateException("V2 missing authentic square-off opening-price candle");
        double gains=0, losses=0;
        int wins=0;
        for (Trade trade:trades) {
            if (trade.netProfit()>0) { wins++; gains+=trade.netProfit(); }
            else losses-=trade.netProfit();
        }
        return new BacktestReport(request.symbol(),"Asia/Kolkata",request.from().toString(),
                request.through().toString(),processed,tested.size(),history.skippedSessions(),
                round(request.initialCapital()),round(equity),round(equity-request.initialCapital()),
                round(100*(equity/request.initialCapital()-1)),trades.size(),wins,
                trades.size()-wins,trades.isEmpty()?0:round(100.0*wins/trades.size()),
                losses==0?null:round(gains/losses),round(maxDrawdown),
                List.copyOf(trades),List.copyOf(curve));
    }

    /**
     * ONE confirmed VWAP touch-and-close is the final signal gate. This function
     * intentionally has no counter, history or dependency on prior VWAP bounces.
     */
    static boolean confirmsFinalVwapBounce(Candle bar, double vwap, Side side,
                                            double toleranceBps) {
        if (!Double.isFinite(vwap) || vwap <= 0 || !Double.isFinite(toleranceBps)
                || toleranceBps < 0) return false;
        double tolerance = vwap * toleranceBps / 10000.0;
        return side == Side.LONG
                ? bar.low() <= vwap + tolerance && bar.close() > vwap
                : bar.high() >= vwap - tolerance && bar.close() < vwap;
    }

    private Position enter(Side side,Candle bar,double equity,StrategyConfig request,VwapEmaV2Config c) {
        double entry=bar.open()*(side==Side.LONG?1+request.slippageBps()/10000.0:
                                              1-request.slippageBps()/10000.0);
        long qty=Math.min(c.quantity(),c.maximumQuantity());
        double entryFee=fee(entry*qty,request);
        if (entry*qty+entryFee>equity) return null;
        double stop=side==Side.LONG?entry*(1-c.stopPercent()/100):entry*(1+c.stopPercent()/100);
        double target=side==Side.LONG?entry*(1+c.targetPercent()/100):entry*(1-c.targetPercent()/100);
        return new Position(side,bar.openTime(),entry,stop,target,entryFee,qty);
    }
    private Trade close(Position p,LocalDateTime at,double raw,ExitReason reason,StrategyConfig c) {
        double price=raw*(p.side()==Side.LONG?1-c.slippageBps()/10000.0:1+c.slippageBps()/10000.0);
        double gross=p.qty()*(p.side()==Side.LONG?price-p.entryPrice():p.entryPrice()-price);
        double fees=p.entryFee()+fee(price*p.qty(),c);
        return new Trade(p.side(),p.entryAt(),at,round(p.entryPrice()),round(price),
                p.qty(),round(p.stop()),round(p.target()),reason,round(gross),round(fees),round(gross-fees));
    }
    private double fee(double notional,StrategyConfig c) {
        return notional*c.feeBps()/10000.0+c.feePerOrder();
    }
    private static double round(double n) {return Math.round(n*100.0)/100.0;}
}
