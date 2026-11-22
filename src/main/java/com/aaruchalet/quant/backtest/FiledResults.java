package com.aaruchalet.quant.backtest;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.jsoup.Jsoup;
import static com.aaruchalet.quant.backtest.PublicCompanyResearch.*;

/** Read named columns of the exchange's rendered Ind AS filing. The second amount can be year-to-date. */
record FiledResults(String url,LocalDate period,LocalDate published,Map<String,String> cells,double croreFactor) {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd-MM-uuuu");
    static FiledResults parse(String isin,String url,String html) {
        var cells=new HashMap<String,String>();
        for(var row:Jsoup.parse(html).select("tr")) {
            var columns=row.children();
            for(int i=0;i+1<columns.size();i++) {
                var label=columns.get(i);
                if(label.normalName().equals("th") && !label.text().isBlank() && !columns.get(i+1).text().isBlank())
                    cells.putIfAbsent(label.text(),columns.get(i+1).text());
            }
        }
        if(!isin.equals(cells.get("ISIN")) || !"Consolidated".equals(cells.get("Nature of report standalone or consolidated"))
                || !"INR".equals(cells.get("Description of presentation currency"))) throw new IllegalArgumentException("Filing identity/basis mismatch");
        double factor=switch(Objects.toString(cells.get("Level of rounding used in financial results"),"").toLowerCase(Locale.ROOT)) {
            case "lakhs","lakh" -> .01;case "crores","crore" -> 1; default -> throw new IllegalArgumentException("Unknown filing unit");
        };
        LocalDate start=LocalDate.parse(cells.get("Date of start of reporting period"),DATE);
        LocalDate end=LocalDate.parse(cells.get("Date of end of reporting period"),DATE);
        if(!start.plusMonths(3).minusDays(1).equals(end)) throw new IllegalArgumentException("Expected quarterly column");
        return new FiledResults(url,end,LocalDate.parse(cells.get("Date of board meeting when results were approved"),DATE),cells,factor);
    }
    private Double value(String label) {
        String raw=cells.get(label);
        if(raw==null || !raw.matches("[(+-]?[0-9,]+(?:[.][0-9]+)?[)]?")) return null;
        return Double.parseDouble(raw.replace(",","").replace("(","-").replace(")",""));
    }
    private Double amount(String label) {var n=value(label);return n==null?null:n*croreFactor;}
    private Double sum(String a,String b) {var x=amount(a);var y=amount(b);return x==null||y==null?null:x+y;}
    private static Double ratio(Double numerator,Double denominator,double scale) {
        return numerator==null||denominator==null||denominator<=0?null:scale*numerator/denominator;
    }
    private Map<String,String> metrics() {
        var m=new LinkedHashMap<String,String>();
        var sales=amount("Revenue from operations");var pat=amount("Total profit (loss) for period");
        var ebit=sum("Total profit before exceptional items and tax","Finance costs");
        var depreciation=amount("Depreciation, depletion and amortisation expense");var other=amount("Other income");
        Double ebitda=ebit==null||depreciation==null||other==null?null:ebit+depreciation-other;
        var cash=amount("Net cash flows from (used in) operating activities");var capex=amount("Purchase of property, plant and equipment");
        put(m,"revenue",sales," cr");put(m,"netProfit",pat," cr");put(m,"ebitda",ebitda," cr");
        put(m,"eps",value("Basic earnings (loss) per share from continuing and discontinued operations")," ₹");
        put(m,"netMargin",ratio(pat,sales,100),"%");put(m,"operatingMargin",ratio(ebitda,sales,100),"%");
        put(m,"debtEquity",ratio(sum("Borrowings, non-current","Borrowings, current"),amount("Total equity"),1),"×");
        put(m,"interestCoverage",ratio(ebit,amount("Finance costs"),1),"×");
        put(m,"receivables",sum("Trade receivables, current","Trade receivables, non-current")," cr");
        put(m,"operatingCash",cash," cr");put(m,"freeCashFlow",cash==null||capex==null?null:cash-Math.abs(capex)," cr");
        if(cells.containsKey("Description of single segment")) m.put("businessSegment",cells.get("Description of single segment"));
        return m;
    }
    private static void put(Map<String,String> m,String key,Double n,String suffix) {if(n!=null) m.put(key,fmt(n,suffix));}
    void addTo(Map<String,EquityScreen.Evidence> facts,FiledResults prior) {
        var previous=prior==null?Map.<String,String>of():prior.metrics();
        metrics().forEach((key,v)->{
            boolean cash=key.equals("operatingCash")||key.equals("freeCashFlow");
            String periodLabel=(cash?"FY to ":"Quarter to ")+period;
            String reason=switch(key) {
                case "ebitda","operatingMargin" -> "Operating EBITDA excludes other income and exceptional items.";
                case "freeCashFlow" -> "Operating cash flow less cash purchases of property, plant and equipment.";
                case "interestCoverage" -> "Profit before exceptional items and tax plus finance costs, divided by finance costs.";
                default -> "Consolidated NSE filing.";
            };
            if(prior!=null && previous.containsKey(key) && !cash) reason+=" Previous quarter ended "+prior.period+".";
            facts.put(key,fact(url,published,reason,v,cash?null:previous.get(key),periodLabel));
        });
        facts.put("financials",fact(url,published,"Dated consolidated quarterly results.",period.toString(),null,period.toString()));
    }
}
