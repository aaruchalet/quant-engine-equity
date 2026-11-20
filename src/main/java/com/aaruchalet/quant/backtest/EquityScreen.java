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
    public record Config(double minPrice, double maxPrice, int minSessions, int maxSessions, Double capital, Double availableCash) {}
    public record Evidence(String source, OffsetDateTime asOf, String rationale, Integer points, Boolean passed,
                           String value, String previous, String trend, String benchmark, String period) {
        public Evidence(String source, OffsetDateTime asOf, String rationale, Integer points, Boolean passed) {
            this(source, asOf, rationale, points, passed, null, null, null, null, null);
        }
    }
    public record Plan(String setup, Boolean confirmed, Double entry, Double stop, Double target1, Double target2,
                       Double entryCost, Double stopExitCost, Double targetExitCost, Long executableUnits,
                       Integer sessions, String invalidation, Double entryLow, Double target2ExitCost,
                       Double entryFixed, Double stopFixed, Double target1Fixed, Double target2Fixed,
                       Long referenceUnits, String triggerCondition, OffsetDateTime triggerExpiresAt,
                       OffsetDateTime triggerObservedAt, Boolean liveQuote, Boolean marketOpen) {}
    public record Stock(String exchange, String symbol, String isin, String company, Double price,
                        Double tradedValue20Cr, Map<String,Boolean> gates, Map<String,Evidence> evidence,
                        List<Candle> adjustedDaily, Plan plan) {}
    public record Snapshot(OffsetDateTime asOf, String regime, List<Stock> stocks) {}
    public record Request(Config config, Snapshot snapshot) {}
    public record Result(String exchange, String symbol, String isin, String company, Double price, OffsetDateTime as_of,
                         String decision, int score, boolean hard_gate_passed, List<String> rejection_reasons,
                         List<String> missing_data, Map<String,Evidence> sources, Map<String,Double> indicator_values,
                         Double entry, Double stop_loss, List<Double> targets, Double net_rr, Integer holding_period,
                         Long position_size, String confidence, String invalidation, Double net_rr_t2,
                         Map<String,Integer> score_breakdown, String trigger_condition, String trigger_status,
                         OffsetDateTime trigger_expires_at, Double tradedValue20Cr, String time_exit) {}
    public record Report(String outcome, String regime, OffsetDateTime as_of, List<Result> shortlist, List<Result> results) {}
    static final List<String> FILED_TESTS = List.of("revenueGrowth","recurringProfit","quarterProfitGrowth",
            "cashFlowOrCapital","outperformsSmallcap","peerPE","peerPB","scheduledEvent","recentDevelopment",
            "quantifiedDevelopment","zeroPledge","cleanAudit","marketAndSectorTrend");
    private final Clock clock;
    public EquityScreen() { this(Clock.systemUTC()); }
    EquityScreen(Clock clock) { this.clock = clock; }
    static final List<String> GATES = List.of("amfiSmallCap","ordinaryMainboardEquity","deliveryTradable",
            "surveillanceClear","reportingClear","manipulationClear","financialMinimums","improvingTrend");
    static final List<String> FACTS = List.of("price","amfi","surveillance","financials","adjustedHistory","fees","depth");

    @PostMapping("/screen")
    public Report screen(@RequestBody Request request) {
        Config c = request.config;
        Snapshot s = request.snapshot;
        validateConfig(c);
        if (s == null || s.asOf == null || s.asOf.toInstant().isAfter(clock.instant()) || s.stocks == null)
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
        rows.sort(Comparator.comparingInt(Result::score).reversed().thenComparing(Result::net_rr, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Result::tradedValue20Cr, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Result::isin));
        var buys = rows.stream().filter(row -> row.decision.startsWith("BUY_")).limit(10).toList();
        return new Report(buys.isEmpty() ? "NO BUY" : rows.stream().anyMatch(r -> r.decision.equals("BUY_NOW")) ? "QUALIFYING SETUPS" : "NO BUY NOW", s.regime,s.asOf,buys,rows);
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
        var breakdown = new TreeMap<String,Integer>();
        for (String test : FILED_TESTS) {
            Evidence evidence = sources.get(test);
            boolean known = verified(evidence,snap.asOf) && evidence.passed != null;
            if (!known) missing.add(test);
            breakdown.put(test, known && evidence.passed ? 5 : 0);
        }
        double close = indicators.getOrDefault("close",Double.NaN);
        double ema20 = indicators.getOrDefault("ema20",Double.NaN);
        breakdown.put("aboveEMA20",close > ema20 ? 5 : 0);
        breakdown.put("ema9Above20",indicators.getOrDefault("ema9",Double.NaN) > ema20 ? 5 : 0);
        breakdown.put("ema20Rising",ema20 > indicators.getOrDefault("ema20FiveAgo",Double.NaN) ? 5 : 0);
        breakdown.put("aboveSMA200",close > indicators.getOrDefault("sma200",Double.NaN) ? 5 : 0);
        breakdown.put("minimumTurnover",positive(s.tradedValue20Cr) && s.tradedValue20Cr >= 5 ? 5 : 0);
        breakdown.put("preferredTurnover",positive(s.tradedValue20Cr) && s.tradedValue20Cr >= 10 ? 5 : 0);
        breakdown.put("volumeConfirmation",indicators.getOrDefault("relativeVolume",0d) >= 1.5 ? 5 : 0);
        int score = breakdown.values().stream().mapToInt(Integer::intValue).sum();
        Plan p = s.plan;
        Double rr = null, rr2 = null;
        Long qty = null;
        String action = "WATCHLIST";
        String triggerStatus = "UNVERIFIED";
        boolean incomplete = !hardPassed;
        if (!complete(p)) {
            missing.add("complete trade plan with entry zone, scenario costs and reference quantity");
            incomplete = true;
        } else if (p.entryLow > p.entry || p.stop >= p.entryLow || p.target1 <= p.entry || p.target2 <= p.target1) {
            reasons.add("Invalid trade levels");
        } else {
            double loss = p.entry - p.stop + p.entryCost + p.stopExitCost;
            double fixedLoss = p.entryFixed + p.stopFixed;
            if (c.capital != null) {
                double funds = Math.min(c.capital * .10, c.availableCash == null ? c.capital : c.availableCash);
                long riskUnits = (long)Math.floor((c.capital * .005 - fixedLoss) / loss);
                long cashUnits = (long)Math.floor((funds - p.entryFixed) / (p.entry + p.entryCost));
                qty = Math.max(0L, Math.min(p.executableUnits, Math.min(riskUnits,cashUnits)));
            }
            long units = qty == null || qty == 0 ? p.referenceUnits : qty;
            double risk = loss + fixedLoss / units;
            rr = (p.target1 - p.entry - p.entryCost - p.targetExitCost - (p.entryFixed + p.target1Fixed) / units) / risk;
            rr2 = (p.target2 - p.entry - p.entryCost - p.target2ExitCost - (p.entryFixed + p.target2Fixed) / units) / risk;
            if (rr < 2) reasons.add("Target 1 net reward/risk below 2");
            boolean validWindow = p.triggerExpiresAt != null && p.triggerExpiresAt.toInstant().isAfter(clock.instant());
            boolean eligible = p.sessions >= c.minSessions && p.sessions <= c.maxSessions && p.executableUnits > 0
                    && (qty == null || qty > 0) && p.entry >= c.minPrice && p.entry < c.maxPrice;
            if (eligible && validWindow && !blank(p.triggerCondition)) {
                if (p.confirmed && Boolean.TRUE.equals(p.marketOpen) && Boolean.TRUE.equals(p.liveQuote)
                        && hardPassed && p.triggerObservedAt != null && !p.triggerObservedAt.isAfter(snap.asOf)
                        && !p.triggerObservedAt.isAfter(sources.get("price").asOf)
                        && sources.get("price").asOf.toInstant().isAfter(clock.instant().minusSeconds(60))
                        && s.price >= p.entryLow && s.price <= p.entry
                        && (!"BREAKOUT".equals(p.setup) || breakdown.get("volumeConfirmation") == 5)) {
                    action = "BUY_NOW";
                    triggerStatus = "CONFIRMED";
                } else if (!p.confirmed && !"NOW".equals(p.setup)) {
                    action = "BUY_ON_" + p.setup;
                    triggerStatus = "PENDING";
                }
            } else if (!validWindow && p.triggerExpiresAt != null) triggerStatus = "EXPIRED";
        }
        String decision = !reasons.isEmpty() ? "REJECTED" : incomplete ? "INSUFFICIENT_DATA"
                : score < 60 ? "AVOID" : score < 75 ? "WATCHLIST" : action;
        return new Result(s.exchange,s.symbol,s.isin,s.company,s.price,snap.asOf,decision,score,hardPassed,reasons,missing,sources,
                indicators,p == null ? null : p.entry,p == null ? null : p.stop,
                p == null ? List.of() : java.util.stream.Stream.of(p.target1,p.target2).filter(Objects::nonNull).toList(),
                rr,p == null ? null : p.sessions,c.capital == null ? null : decision.startsWith("BUY_") ? qty : Long.valueOf(0),
                missing.isEmpty() ? "Evidence supplied" : "Incomplete",p == null ? null : p.invalidation,rr2,breakdown,
                p == null ? null : p.triggerCondition,triggerStatus,p == null ? null : p.triggerExpiresAt,s.tradedValue20Cr,
                "Count entry as session 1. Exit by close of session " + c.maxSessions + " or earlier on stop, target or invalidation.");
    }

    static void validateConfig(Config c) {
        if (c == null || !positive(c.minPrice) || !positive(c.maxPrice) || c.minPrice >= c.maxPrice
                || c.minSessions < 1 || c.maxSessions < c.minSessions || c.capital != null && !positive(c.capital) || c.availableCash != null && !nonnegative(c.availableCash))
            throw invalid("Check the price band, holding range and capital.");
    }

    private static boolean complete(Plan p) {
        return p != null && positive(p.entryLow) && positive(p.entry) && positive(p.stop)
                && positive(p.target1) && positive(p.target2) && nonnegative(p.entryCost) && nonnegative(p.stopExitCost)
                && nonnegative(p.targetExitCost) && nonnegative(p.target2ExitCost) && nonnegative(p.entryFixed)
                && nonnegative(p.stopFixed) && nonnegative(p.target1Fixed) && nonnegative(p.target2Fixed)
                && p.referenceUnits != null && p.referenceUnits > 0 && p.executableUnits != null && p.executableUnits >= 0
                && p.sessions != null && !blank(p.invalidation) && p.confirmed != null
                && Set.of("NOW","BREAKOUT","PULLBACK").contains(Objects.toString(p.setup,""));
    }

    static boolean verified(Evidence e, OffsetDateTime asOf) {
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
