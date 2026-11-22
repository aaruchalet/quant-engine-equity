package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.time.*;
import java.util.*;
import tools.jackson.databind.*;
import static com.aaruchalet.quant.backtest.PublicCompanyResearch.*;

final class PublicPriceHistory {
    private record Adjustment(LocalDate date,double factor) {}
    static List<Candle> fetch(EquityResearchController.Research market,ResearchDocuments docs,ObjectMapper json,
                              Map<String,EquityScreen.Evidence> facts) throws IOException,InterruptedException {
        var end=market.asOf();var start=end.minusMonths(15);String symbol=market.symbol().replace("&","%26");
        String url="https://api.tejhq.dev/v1/ohlcv/"+market.exchange().toLowerCase(Locale.ROOT)+"/"+symbol+"?from="+start+"&to="+end;
        var data=json.readTree(docs.read(url,DAY));
        if(!market.symbol().equals(data.at("/meta/symbol").asString()) || !market.exchange().equals(data.at("/meta/exchange").asString()))
            throw new IOException("Price history listing mismatch");
        var bars=new TreeMap<LocalDate,Candle>();
        var turnovers=new TreeMap<LocalDate,Double>();
        for(var row:data.path("data")) {
            var date=LocalDate.parse(row.path("date").asString());
            if(date.isAfter(end)||date.isBefore(start)) continue;
            var candle=new Candle(date.atTime(9,15),row.path("open").asDouble(),row.path("high").asDouble(),row.path("low").asDouble(),
                    row.path("close").asDouble(),row.path("volume").asDouble());
            if(bars.put(date,candle)!=null) throw new IOException("Duplicate session");
            if(row.path("turnover").isNumber()) turnovers.put(date,row.path("turnover").asDouble()/1e7);
        }
        int overlap=0;
        for(var day:market.dailyPrices()) {
            var old=bars.get(day.session());
            if(old!=null) {
                if(Math.abs(old.close()-day.close())>.011) throw new IOException("Price histories disagree");
                overlap++;
            }
            if(day.open()!=null && day.high()!=null && day.low()!=null && day.volume()!=null)
                bars.put(day.session(),new Candle(day.session().atTime(9,15),day.open(),day.high(),day.low(),day.close(),day.volume()));
            turnovers.put(day.session(),day.tradedValueCr());
        }
        if(overlap<2 || bars.size()<250 || !end.equals(bars.lastKey())) throw new IOException("Insufficient matching sessions");
        var last60=bars.descendingKeySet().stream().limit(60).toList();
        if(last60.stream().allMatch(turnovers::containsKey)) facts.put("tradedValue60Cr",fact(url,end,
                "Mean daily traded value over 60 completed sessions.",fmt(last60.stream().mapToDouble(turnovers::get).average().orElseThrow()," cr"),null,end.toString()));
        String actions="https://api.tejhq.dev/v1/actions/"+symbol;
        var events=json.readTree(docs.read(actions,Duration.ofHours(4)));
        if(!market.symbol().equals(events.at("/meta/symbol").asString()) || !events.path("data").isArray()
                || events.at("/meta/count").asInt(-1)!=events.path("data").size()) throw new IOException("Corporate action coverage unavailable");
        var changes=new ArrayList<Adjustment>();
        var unique=new HashSet<String>();
        for(var item:events.path("data")) {
            var date=LocalDate.parse(item.path("ex_date").asString());
            if(date.isBefore(bars.firstKey()) || date.isAfter(end) || !market.exchange().equals(item.path("exchange").asString())) continue;
            var type=item.path("type").asString();
            double factor=switch(type) {
                case "dividend" -> 1; // price series, excluding cash dividends
                case "split" -> item.path("face_value_from").asDouble()/item.path("face_value_to").asDouble();
                case "bonus" -> 1+item.path("ratio_num").asDouble()/item.path("ratio_den").asDouble();
                default -> throw new IOException("Unresolved corporate action: "+type);
            };
            if(!Double.isFinite(factor)||factor<=0 || !unique.add(date+type)) throw new IOException("Ambiguous action");
            if(factor!=1) changes.add(new Adjustment(date,factor));
        }
        var adjusted=new ArrayList<Candle>();
        for(var e:bars.entrySet()) {
            double factor=1;
            for(var action:changes) if(e.getKey().isBefore(action.date)) factor*=action.factor;
            var b=e.getValue();
            adjusted.add(new Candle(b.openTime(),b.open()/factor,b.high()/factor,b.low()/factor,b.close()/factor,b.volume()*factor));
        }
        facts.put("adjustedHistory",fact(url,end,"Exchange closes reconciled. Historical split/bonus adjustments use "+actions+
                ". Cash dividends excluded.",adjusted.size()+" sessions",null,start+" to "+end));
        return adjusted;
    }
}
