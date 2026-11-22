package com.aaruchalet.quant.backtest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static com.aaruchalet.quant.backtest.EquityScreen.*;

@Component
class PublicCompanyResearch {
    static final String BASE="https://theannexure.com/data/";
    static final String INDEX="https://raw.githubusercontent.com/aditya-jha/nse-historical-membership/main/shareholding_history/data/filings_index/";
    static final Duration DAY=Duration.ofDays(1), FILING=Duration.ofDays(30);
    private static final DateTimeFormatter MONTH=new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("MMM-uu").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter INDEX_DATE=new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-uuuu").toFormatter(Locale.ENGLISH);
    private final ResearchDocuments documents;
    private final ObjectMapper json;
    private record Dataset(Instant loaded,Map<String,List<JsonNode>> rows) {}
    private final Map<String,Dataset> datasets=new HashMap<>();
    PublicCompanyResearch(ResearchDocuments documents,ObjectMapper json) {this.documents=documents;this.json=json;}

    record Data(Map<String,Evidence> facts,List<Candle> bars,List<ExchangeNews.Item> news,List<String> warnings) {}
    private interface Load {void run() throws IOException,InterruptedException;}
    private static void collect(String label,List<String> warnings,Load load) throws InterruptedException {
        try {load.run();} catch(IOException | RuntimeException ex) {warnings.add(label+" unavailable for this listing.");}
    }

    Data load(EquityResearchController.Research market,OffsetDateTime now) throws InterruptedException {
        var facts=new LinkedHashMap<String,Evidence>();
        var warnings=new ArrayList<String>();
        var news=new ArrayList<ExchangeNews.Item>();
        var bars=new ArrayList<Candle>();
        String isin=market.isin(),symbol=market.symbol();
        collect("Published financial results",warnings,()->results(facts,rows("fundamentals",isin),now));
        collect("BSE valuation snapshot",warnings,()->valuation(facts,rows("snapshot",isin),now));
        collect("NSE consolidated filing",warnings,()->primary(isin,facts,news,now));
        if(symbol.matches("[A-Z0-9&._-]{1,30}")) {
            collect("Quarterly shareholding filing",warnings,()->holdings(isin,symbol,facts,news,now));
            collect("Reconciled daily price history",warnings,()->bars.addAll(
                    PublicPriceHistory.fetch(market,documents,json,facts)));
        }
        return new Data(Map.copyOf(facts),List.copyOf(bars),List.copyOf(news),List.copyOf(warnings));
    }

    private synchronized List<JsonNode> rows(String dataset,String isin) throws IOException,InterruptedException {
        var cached=datasets.get(dataset);
        if(cached==null || cached.loaded.plus(DAY).isBefore(Instant.now())) {
            String data=documents.read(BASE+dataset+".jsonl",DAY);
            var indexed=new HashMap<String,List<JsonNode>>();
            for(var line:data.lines().filter(s->!s.isBlank()).toList()) {
                var row=json.readTree(line);
                if(row.path("schema_version").asInt()==1)
                    indexed.computeIfAbsent(row.path("isin").asString(),k->new ArrayList<>()).add(row);
            }
            cached=new Dataset(Instant.now(),indexed);
            datasets.put(dataset,cached);
        }
        return cached.rows.getOrDefault(isin,List.of());
    }
    static String fmt(double n,String suffix) {return String.format(Locale.ROOT,"%,.2f%s",n,suffix);}
    static OffsetDateTime stamp(LocalDate d) {return d.atStartOfDay(CompanyResearch.IST).toOffsetDateTime();}
    static Evidence fact(String url,LocalDate date,String reason,String value,String previous,String period) {
        return new Evidence(url,stamp(date),reason,null,null,value,previous,null,null,period);
    }
    private static String number(JsonNode row,String key,String suffix) {
        return row!=null && row.path(key).isNumber()?fmt(row.path(key).asDouble(),suffix):null;
    }
    private static YearMonth month(JsonNode row) {return YearMonth.parse(row.path("period").asString(),MONTH);}
    private void results(Map<String,Evidence> facts,List<JsonNode> rows,OffsetDateTime now) {
        var quarters=new TreeMap<YearMonth,JsonNode>();
        for(var row:rows) {
            if(!"in Cr.".equals(row.path("currency_unit").asString()) || !"quarter".equals(row.path("period_type").asString())) continue;
            if(!LocalDate.parse(row.path("captured_at").asString()).isAfter(now.toLocalDate())) quarters.put(month(row),row);
        }
        if(quarters.isEmpty()) return;
        var m=quarters.lastKey();var q=quarters.get(m);var prev=quarters.get(m.minusMonths(3));
        var date=LocalDate.parse(q.path("captured_at").asString());
        for(var spec:List.of(List.of("revenue","revenue"," cr"),List.of("netProfit","net_profit"," cr"),List.of("eps","eps"," ₹"),
                List.of("operatingMargin","opm_pct","%"),List.of("netMargin","npm_pct","%"))) {
            String value=number(q,spec.get(1),spec.get(2));
            if(value!=null) facts.put(spec.getFirst(),fact(BASE+"fundamentals.jsonl",date,"BSE results snapshot via The Annexure.",
                    value,number(prev,spec.get(1),spec.get(2)),m.atEndOfMonth().toString()));
        }
    }
    private void valuation(Map<String,Evidence> facts,List<JsonNode> rows,OffsetDateTime now) {
        for(var row:rows) {
            var d=LocalDate.parse(row.path("capture_date").asString());
            if(d.isAfter(now.toLocalDate())) continue;
            for(var spec:List.of(List.of("marketCap","mktcap_full"," cr"),List.of("freeFloat","mktcap_ff"," cr"),
                    List.of("roe","roe","%"),List.of("pe","pe","×"),List.of("pb","pb","×"))) {
                var v=number(row,spec.get(1),spec.get(2));
                if(v!=null) facts.put(spec.getFirst(),fact(BASE+"snapshot.jsonl",d,"Historical BSE snapshot via The Annexure.",
                        v,null,d.toString()));
            }
        }
    }

    private void primary(String isin,Map<String,Evidence> facts,List<ExchangeNews.Item> news,OffsetDateTime now)
            throws IOException,InterruptedException {
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/research-filings.csv"));
            var lines=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
            var reports=new TreeMap<LocalDate,FiledResults>();
            for(String line:lines.lines().filter(s->s.startsWith(isin+",")).toList()) {
                String url=line.split(",",2)[1];
                try {
                    var filing=FiledResults.parse(isin,url,documents.read(url,FILING));
                    if(!filing.published().isAfter(now.toLocalDate())) reports.put(filing.period(),filing);
                } catch(IOException | IllegalArgumentException ex) { /* An earlier quarter can be used independently. */ }
            }
            if(reports.isEmpty()) return;
            var latest=reports.lastEntry().getValue();
            var prior=reports.get(YearMonth.from(latest.period()).minusMonths(3).atEndOfMonth());
            var fallback=facts.get("revenue");
            if(fallback!=null && LocalDate.parse(fallback.period()).isAfter(latest.period())) return;
            // Keep each basis together. Never compare a consolidated quarter to the fallback's standalone numbers.
            for(String key:List.of("revenue","netProfit","eps","operatingMargin","netMargin")) facts.remove(key);
            latest.addTo(facts,prior);
            news.add(item("Consolidated quarterly results for "+latest.period(),latest.url(),latest.published(),now));
        }
    }
    static ExchangeNews.Item item(String title,String url,LocalDate published,OffsetDateTime now) {
        return new ExchangeNews.Item(title,url,stamp(published),"NEUTRAL","Exchange filing · publication date",url,now);
    }
    private record Holding(LocalDate period,LocalDate published,String url,Map<String,String> values) {}
    private void holdings(String isin,String symbol,Map<String,Evidence> facts,List<ExchangeNews.Item> news,OffsetDateTime now)
            throws IOException,InterruptedException {
        var index=json.readTree(documents.read(INDEX+symbol.replace("&","%26")+".json",DAY));
        if(!symbol.equals(index.path("ticker").asString())) return;
        var selected=new TreeMap<LocalDate,JsonNode>(Comparator.reverseOrder());
        for(var row:index.path("filings")) {
            try {
                var date=LocalDate.parse(row.path("date").asString(),INDEX_DATE);
                var published=LocalDate.parse(row.path("submission_date").asString(),INDEX_DATE);
                if(date.getMonthValue()%3!=0 || !date.equals(YearMonth.from(date).atEndOfMonth()) ||
                        date.isAfter(published) || published.isAfter(now.toLocalDate())) continue;
                var existing=selected.get(date);
                if(existing==null || published.isAfter(LocalDate.parse(existing.path("submission_date").asString(),INDEX_DATE)))
                    selected.put(date,row);
            } catch(DateTimeException ignored) { }
        }
        Holding current=null,prev=null;
        for(var e:selected.entrySet().stream().limit(2).toList()) {
            var row=e.getValue();String url=row.path("xbrl").asString();
            if(!url.startsWith("https://nsearchives.nseindia.com/corporate/xbrl/SHP_")) continue;
            try {
                var xml=Jsoup.parse(documents.read(url,FILING),"",Parser.xmlParser());
                var values=new HashMap<String,String>();
                for(var tag:xml.getAllElements()) if(tag.hasAttr("contextRef")) {
                    String name=tag.tagName();values.put(name.substring(name.lastIndexOf(':')+1)+"/"+tag.attr("contextRef"),tag.text());
                }
                if(!isin.equals(values.get("ISIN/MainD")) || !e.getKey().toString().equals(values.get("DateOfReport/MainI"))) continue;
                var h=new Holding(e.getKey(),LocalDate.parse(row.path("submission_date").asString(),INDEX_DATE),url,values);
                if(current==null) current=h; else prev=h;
            } catch(IOException | RuntimeException ex) {if(current==null) throw ex;}
        }
        if(current==null) return;
        for(var entry:Map.of("promoterHolding","ShareholdingOfPromoterAndPromoterGroup","dii","InstitutionsDomestic","fii","InstitutionsForeign").entrySet()) {
            String value=percentage(current,entry.getValue());
            if(value!=null) facts.put(entry.getKey(),fact(current.url,current.published,"Shares held as a percentage of issued shares.",
                    value,prev==null?null:percentage(prev,entry.getValue()),current.period.toString()));
        }
        String sharesKey="NumberOfShares/ShareholdingPattern_ContextI";
        if(prev!=null && current.values.containsKey(sharesKey) && prev.values.containsKey(sharesKey)) {
            double before=Double.parseDouble(prev.values.get(sharesKey)),after=Double.parseDouble(current.values.get(sharesKey));
            if(before>0) facts.put("dilution",fact(current.url,current.published,
                    "Change in issued shares since "+prev.period+"; includes corporate actions.",
                    fmt(100*(after/before-1),"%"),null,current.period.toString()));
        }
        var pledged=current.values.get("WhetherAnySharesHeldByPromotersAreEncumberedUnderPledged/MainI");
        if(Set.of("true","false").contains(Objects.toString(pledged,""))) {
            boolean clear=pledged.equals("false");
            facts.put("zeroPledge",new Evidence(current.url,stamp(current.published),"Issuer's quarterly pledge disclosure.",
                    null,clear,clear?"No pledged shares":"Pledge disclosed",null,null,null,current.period.toString()));
        }
        news.add(item("Shareholding pattern for "+current.period,current.url,current.published,now));
    }
    private String percentage(Holding h,String group) {
        var total=h.values.get("NumberOfShares/ShareholdingPattern_ContextI");
        var shares=h.values.get("NumberOfShares/"+group+"_ContextI");
        if(total==null || shares==null || Double.parseDouble(total)<=0) return null;
        return fmt(100*Double.parseDouble(shares)/Double.parseDouble(total),"%");
    }
}
