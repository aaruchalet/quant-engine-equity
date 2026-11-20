package com.aaruchalet.quant.backtest;

import java.time.*;
import java.util.*;

final class ResearchChart {
    record Point(LocalDate date, Double open, Double high, Double low, double close, Double volume,
                 Double ema9, Double ema20, Double ema50, Double sma200) {}
    record Week(LocalDate lastSession,double open,double high,double low,double close,double volume) {}
    record Series(String basis,List<Point> daily,List<Week> weekly,Map<String,Double> levels) {}

    static Series series(List<Candle> bars, EquityResearchController.Research archive){
        var daily=new ArrayList<Point>();
        if (bars.isEmpty()) {
            if (archive!=null) for (var p:archive.dailyPrices())
                daily.add(new Point(p.session(),p.open(),p.high(),p.low(),p.close(),p.volume(),null,null,null,null));
            return new Series("Unadjusted exchange archive",daily,List.of(),Map.of());
        }
        var weeks=new ArrayList<Week>();
        var levels=new TreeMap<String,Double>();
        var fast=new IndicatorState(9,20,14);
        var medium=new IndicatorState(20,50,14);
        double sum=0, accumulation=0;
        for(int i=0;i<bars.size();i++) {
            var b=bars.get(i);
            var a=fast.add(b);
            var m=medium.add(b);
            sum+=b.close();
            if (i>=200) sum-=bars.get(i-200).close();
            if (b.high()>b.low()) accumulation+=(2*b.close()-b.low()-b.high())/(b.high()-b.low())*b.volume();
            var date=b.openTime().toLocalDate();
            daily.add(new Point(date,b.open(),b.high(),b.low(),b.close(),b.volume(),a.emaFast(),a.emaSlow(),
                    m.emaSlow(),i>=199 ? sum/200 : null));
            if (!weeks.isEmpty() && weeks.getLast().lastSession().with(DayOfWeek.MONDAY).equals(date.with(DayOfWeek.MONDAY))) {
                var w=weeks.removeLast();
                weeks.add(new Week(date,w.open,Math.max(w.high,b.high()),Math.min(w.low,b.low()),b.close(),w.volume+b.volume()));
            } else weeks.add(new Week(date,b.open(),b.high(),b.low(),b.close(),b.volume()));
        }
        levels.put("Accumulation/distribution",accumulation);
        for(int window:List.of(20,60)) {
            // Prior completed sessions exclude the current close when testing a breakout.
            var prior=bars.subList(bars.size()-window-1,bars.size()-1);
            levels.put("Prior "+window+"-session resistance",prior.stream().mapToDouble(Candle::high).max().orElseThrow());
            levels.put("Prior "+window+"-session support",prior.stream().mapToDouble(Candle::low).min().orElseThrow());
        }
        var last=daily.getLast();
        LocalDate start=last.date.minusWeeks(52);
        if (!daily.getFirst().date.isAfter(start)) {
            levels.put("52-week low",daily.stream().filter(p->p.date.isAfter(start)).mapToDouble(Point::low).min().orElseThrow());
            levels.put("52-week high",daily.stream().filter(p->p.date.isAfter(start)).mapToDouble(Point::high).max().orElseThrow());
        }
        var prev=daily.get(daily.size()-6);
        levels.put("EMA 9 change over 5 sessions",last.ema9-prev.ema9);
        levels.put("EMA 20 change over 5 sessions",last.ema20-prev.ema20);
        levels.put("EMA 50 change over 5 sessions",last.ema50-prev.ema50);
        levels.put("SMA 200 change over 5 sessions",last.sma200-prev.sma200);
        return new Series("Corporate-action-adjusted provider history",daily,weeks,levels);
    }
}
