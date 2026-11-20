package com.aaruchalet.quant.backtest;

import com.aaruchalet.quant.backtest.BacktestReport.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Independent read-only V2 backtester.
 *
 * Decision order: EMA crossover -> RSI -> time/risk/position -> VWAP side.
 * A touch or rebound is not required.
 * All signals use completed candles; fills happen at the next bar OPEN.
 */
public final class BacktestEngineV2 {
    private record PendingSignal(Side side, double confirmedVwap) {}
    private record Position(Side side, LocalDateTime entryAt, double entryPrice,
                            double stop, double target, double entryFee, long qty) {}
    private record Closed(Trade trade) {}

    public BacktestReport run(CsvHistoryRepository.History history, StrategyConfig request,
                              VwapEmaV2Config cfg) {
        V2SignalState signals = new V2SignalState(cfg);
        List<Trade> trades = new ArrayList<>();
        List<EquityPoint> curve = new ArrayList<>();
        Set<LocalDate> tested = new HashSet<>();
        double equity = request.initialCapital(), peak = equity, maxDrawdown = 0;
        curve.add(new EquityPoint(request.from().atStartOfDay(), equity));
        Position position = null;
        PendingSignal pending = null;
        ExitReason pendingExit = null;
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
                pendingExit = null;
                dailyPnl = 0;
                entriesToday = 0;
            }
            boolean inRange = !date.isBefore(request.from()) && !date.isAfter(request.through());
            if (inRange) { tested.add(date); processed++; }

            // Exits at a known bar OPEN execute before reading this candle's future high/low/close.
            if (position != null && (time.equals(cfg.squareOff()) || pendingExit != null)) {
                ExitReason reason = time.equals(cfg.squareOff()) ? ExitReason.SQUARE_OFF : pendingExit;
                Trade t = close(position, bar.openTime(), bar.open(), reason, request, cfg);
                trades.add(t);
                dailyPnl += t.netProfit();
                equity += t.netProfit();
                curve.add(new EquityPoint(t.exitAt(), equity));
                peak = Math.max(peak, equity);
                maxDrawdown = Math.max(maxDrawdown, 100*(peak-equity)/peak);
                position = null;
                pendingExit = null;
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
                    Trade t=close(position, atOpen?bar.openTime():bar.closeTime(),rawExit,reason,request,cfg);
                    trades.add(t);
                    dailyPnl += t.netProfit();
                    equity += t.netProfit();
                    curve.add(new EquityPoint(t.exitAt(),equity));
                    peak=Math.max(peak,equity);
                    maxDrawdown=Math.max(maxDrawdown,100*(peak-equity)/peak);
                    position=null;
                    pendingExit=null;
                }
            }

            // Only after the candle is COMPLETE do EMA/RSI/VWAP snapshots become known.
            var s = signals.onClose(bar);
            if (!s.ready()) continue;
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
                             (position.side()==Side.SHORT && bar.close()>s.vwap()))) pendingExit=ExitReason.VWAP_INVALIDATION;
                    if (pendingExit==null && cfg.exitOnEmaReversal() &&
                            ((position.side()==Side.LONG && s.cross()==Side.SHORT) ||
                             (position.side()==Side.SHORT && s.cross()==Side.LONG))) pendingExit=ExitReason.EMA_REVERSAL;
                }
                continue;
            }
            if (!eligible || pendingExit!=null) continue;
            if (s.entry()!=null) pending=new PendingSignal(s.entry(),s.vwap());
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

    static boolean confirmsVwapSide(Candle bar, double vwap, Side side) {
        if (!Double.isFinite(vwap) || vwap <= 0) return false;
        return side == Side.LONG ? bar.close() > vwap : bar.close() < vwap;
    }

    private Position enter(Side side,Candle bar,double equity,StrategyConfig request,VwapEmaV2Config c) {
        double entry=bar.open()*(side==Side.LONG?1+request.slippageBps()/10000.0:
                                              1-request.slippageBps()/10000.0);
        long qty=Math.min(c.quantity(),c.maximumQuantity());
        double entryFee=AngelOneIntradayFees.orderFee(entry*qty,side==Side.LONG,c.fees());
        if (entry*qty+entryFee>equity) return null;
        double stop=side==Side.LONG?entry*(1-c.stopPercent()/100):entry*(1+c.stopPercent()/100);
        double target=side==Side.LONG?entry*(1+c.targetPercent()/100):entry*(1-c.targetPercent()/100);
        return new Position(side,bar.openTime(),entry,stop,target,entryFee,qty);
    }
    private Trade close(Position p,LocalDateTime at,double raw,ExitReason reason,StrategyConfig c,VwapEmaV2Config config) {
        double price=raw*(p.side()==Side.LONG?1-c.slippageBps()/10000.0:1+c.slippageBps()/10000.0);
        double gross=p.qty()*(p.side()==Side.LONG?price-p.entryPrice():p.entryPrice()-price);
        double fees=p.entryFee()+AngelOneIntradayFees.orderFee(price*p.qty(),p.side()==Side.SHORT,config.fees());
        return new Trade(p.side(),p.entryAt(),at,round(p.entryPrice()),round(price),
                p.qty(),round(p.stop()),round(p.target()),reason,round(gross),round(fees),round(gross-fees));
    }
    private static double round(double n) {return Math.round(n*100.0)/100.0;}
}
