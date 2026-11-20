package com.aaruchalet.quant.backtest;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Screens a dated analyst/provider evidence snapshot. No execution dependency. */
@RestController
@RequestMapping("/api/equity")
public class EquityScreen {
    public record Config(double minPrice, double maxPrice, int minSessions, int maxSessions, double capital) {}
    public record Evidence(String source, OffsetDateTime asOf, String rationale, Integer points) {}
    public record Plan(String setup, Boolean confirmed, Double entry, Double stop, Double target1, Double target2,
                       Double entryCost, Double stopExitCost, Double targetExitCost, Long executableUnits,
                       Integer sessions, String invalidation) {}
    public record Stock(String exchange, String symbol, String isin, String company, Double price,
                        Double tradedValue20Cr, Map<String,Boolean> gates, Map<String,Evidence> evidence,
                        List<Candle> adjustedDaily, Plan plan) {}
    public record Snapshot(OffsetDateTime asOf, String regime, List<Stock> stocks) {}
    public record Request(Config config, Snapshot snapshot) {}
    public record Result(String exchange, String symbol, String isin, String company, Double price, OffsetDateTime as_of,
                         String decision, int score, boolean hard_gate_passed, List<String> rejection_reasons,
                         List<String> missing_data, Map<String,Evidence> sources, Map<String,Double> indicator_values,
                         Double entry, Double stop_loss, List<Double> targets, Double net_rr, Integer holding_period,
                         long position_size, String confidence, String invalidation) {}
    public record Report(String outcome, String regime, OffsetDateTime as_of, List<Result> shortlist, List<Result> results) {}
    static final Map<String,Integer> WEIGHTS = Map.of("fundamentals",20,"technicals",25,"liquidity",15,
            "valuation",10,"catalysts",15,"governance",10,"market",5);
    static final List<String> GATES = List.of("amfiSmallCap","ordinaryMainboardEquity","deliveryTradable",
            "surveillanceClear","reportingClear","manipulationClear","financialMinimums","improvingTrend");
    static final List<String> FACTS = List.of("price","amfi","surveillance","financials","adjustedHistory","fees","depth");

    @PostMapping("/screen")
    public Report screen(@RequestBody Request request) {
        Config c = request.config;
        Snapshot s = request.snapshot;
        if (c == null || !positive(c.minPrice) || !positive(c.maxPrice) || c.minPrice >= c.maxPrice
                || c.minSessions < 1 || c.maxSessions < c.minSessions || !positive(c.capital))
            throw invalid("Check the price band, holding range and capital.");
        if (s == null || s.asOf == null || s.asOf.toInstant().isAfter(Instant.now()) || s.stocks == null)
            throw invalid("Supply a dated snapshot with a stocks array.");
        if (!Set.of("BULLISH","NEUTRAL","BEARISH","HIGH_VOLATILITY","UNKNOWN").contains(Objects.toString(s.regime,"")))
            throw invalid("Invalid market regime.");
        var seen = new HashSet<String>();
        var rows = new ArrayList<Result>();
        for (Stock stock : s.stocks) {
            if (stock == null || blank(stock.symbol) || blank(stock.exchange) || blank(stock.isin)
                    || !seen.add(stock.exchange + "/" + stock.symbol) || !seen.add(stock.isin))
                throw invalid("Supply one identified listing per ISIN.");
            rows.add(evaluate(stock, s, c));
        }
        rows.sort(Comparator.comparingInt(Result::score).reversed().thenComparing(Result::symbol));
        var buys = rows.stream().filter(row -> row.decision.startsWith("BUY_")).limit(10).toList();
        return new Report(buys.isEmpty() ? "NO BUY" : "QUALIFYING SETUPS", s.regime,s.asOf,buys,rows);
    }

    private Result evaluate(Stock s, Snapshot snap, Config c) {
        var reasons = new ArrayList<String>();
        var missing = new ArrayList<String>();
        var sources = s.evidence == null ? Map.<String,Evidence>of() : s.evidence;
        if (!Set.of("NSE","BSE").contains(s.exchange)) reasons.add("Exchange outside universe");
        if (!positive(s.price)) missing.add("price");
        else if (s.price < c.minPrice || s.price >= c.maxPrice) reasons.add("Outside price band");
        if (!nonnegative(s.tradedValue20Cr)) missing.add("20-session traded value");
        else if (s.tradedValue20Cr < 5) reasons.add("Traded value below ₹5 crore");
        for (String key : GATES) {
            Boolean value = s.gates == null ? null : s.gates.get(key);
            if (value == null) missing.add(key);
            else if (!value) reasons.add(key);
        }
        for (String key : FACTS) {
            if (!verified(sources.get(key), snap.asOf)) missing.add(key + " evidence");
        }
        if ("UNKNOWN".equals(snap.regime)) missing.add("market regime");
        Map<String,Double> indicators;
        try {
            indicators = EquityHistory.indicators(s.adjustedDaily, snap.asOf.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate());
        } catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
        if (indicators.isEmpty()) missing.add("250 adjusted daily candles");
        boolean hardPassed = reasons.isEmpty() && missing.isEmpty();
        int score = 0;
        for (var category : WEIGHTS.entrySet()) {
            Evidence e = sources.get(category.getKey());
            if (!verified(e,snap.asOf) || e.points == null) missing.add(category.getKey());
            else if (e.points < 0 || e.points > category.getValue()) throw invalid("Invalid points for " + category.getKey());
            else score += e.points;
        }
        Plan p = s.plan;
        Double rr = null;
        long qty = 0;
        boolean ready = false;
        if (p == null || !positive(p.entry) || !positive(p.stop) || !positive(p.target1) || !positive(p.target2)
                || !nonnegative(p.entryCost) || !nonnegative(p.stopExitCost) || !nonnegative(p.targetExitCost)
                || p.executableUnits == null || p.executableUnits < 0 || p.sessions == null || blank(p.invalidation)
                || p.confirmed == null || !Set.of("NOW","BREAKOUT","PULLBACK").contains(Objects.toString(p.setup,"")))
            missing.add("complete trade plan including costs, depth and confirmation");
        else if (p.stop >= p.entry || p.target1 <= p.entry || p.target2 < p.target1) reasons.add("Invalid trade levels");
        else {
            double loss = p.entry - p.stop + p.entryCost + p.stopExitCost;
            rr = (p.target1 - p.entry - p.entryCost - p.targetExitCost) / loss;
            qty = Math.min(p.executableUnits, (long)Math.min(c.capital * .01 / loss, c.capital * .10 / (p.entry + p.entryCost)));
            if (rr < 2) reasons.add("Net reward/risk below 2");
            ready = p.confirmed && (!"BREAKOUT".equals(p.setup) || indicators.getOrDefault("relativeVolume",0.0) >= 1.5) && qty > 0 && p.sessions >= c.minSessions && p.sessions <= c.maxSessions;
        }
        String decision = !reasons.isEmpty() ? "REJECTED" : !missing.isEmpty() ? "INSUFFICIENT_DATA"
                : score < 60 ? "AVOID" : score < 75 || !ready ? "WATCHLIST" : "BUY_" + (p.setup.equals("NOW") ? "NOW" : "ON_" + p.setup);
        return new Result(s.exchange,s.symbol,s.isin,s.company,s.price,snap.asOf,decision,score,hardPassed,reasons,missing,sources,
                indicators,p == null ? null : p.entry,p == null ? null : p.stop,
                p == null ? List.of() : java.util.stream.Stream.of(p.target1,p.target2).filter(Objects::nonNull).toList(),
                rr,p == null ? null : p.sessions,decision.startsWith("BUY_") ? qty : 0,
                missing.isEmpty() ? "Evidence supplied" : "Incomplete",p == null ? null : p.invalidation);
    }

    private static boolean verified(Evidence e, OffsetDateTime asOf) {
        if (e == null || e.asOf == null || e.asOf.isAfter(asOf) || blank(e.source) || blank(e.rationale)) return false;
        try {
            URI uri = URI.create(e.source);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) { return false; }
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean nonnegative(Double n) { return n != null && Double.isFinite(n) && n >= 0; }
    private static boolean positive(Double n) { return nonnegative(n) && n > 0; }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> error(ResponseStatusException e) { return Map.of("error",e.getReason()); }
}
