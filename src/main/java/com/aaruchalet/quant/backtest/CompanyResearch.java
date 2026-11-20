package com.aaruchalet.quant.backtest;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static com.aaruchalet.quant.backtest.EquityScreen.*;

/** One decision for one listing. All amounts are INR and all time checks use the evaluation instant. */
final class CompanyResearch {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    enum Position { NONE, HELD, UNKNOWN }
    enum Decision { BUY, SELL, HOLD, WAIT }
    enum Quality { VERIFIED, PRELIMINARY, STALE, INSUFFICIENT }
    record Inputs(Config config, Position positionStatus, Long quantity, Double costBasis,
                  Double heldStop, LocalDate entryDate) {
        Inputs {
            validateConfig(config);
            if (positionStatus == null) positionStatus = Position.NONE;
            if (quantity != null && quantity < 1 || invalidPrice(costBasis) || invalidPrice(heldStop)
                    || entryDate != null && entryDate.isAfter(LocalDate.now(IST))
                    || positionStatus != Position.HELD && (quantity != null || costBasis != null
                    || heldStop != null || entryDate != null))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid held-position inputs");
        }
        private static boolean invalidPrice(Double n) { return n != null && (!Double.isFinite(n) || n <= 0); }
    }
    record Category(String name, Integer points, int maximum, List<String> tests) {}
    record Finding(String key, Evidence evidence, boolean current) {}
    record Action(Decision decision, String confidence, List<String> reasons, String instruction) {}
    record PlanView(Double entryLow, Double entry, Double stop, Double target1, Double target2,
                    Double grossGain, Double netGain, Double grossLoss, Double netLoss,
                    Double netRewardRisk, Long units, String trigger, String invalidation, String timeExit) {}
    record Report(String id, OffsetDateTime evaluatedAt, String isin, String exchange, String symbol, String company,
                  LocalDate dataAsOf, Quality dataStatus, Inputs inputs, Action verdict, Integer score,
                  List<Category> categories, List<String> exclusions, List<String> missingFields,
                  List<Finding> findings, Map<String,Double> indicators, PlanView tradePlan,
                  EquityResearchController.Research market, ResearchChart.Series chart, String regime,
                  String classification, String classificationSource, List<ExchangeNews.Item> announcements,
                  List<String> limitations, LocalDate nextReview) {}
    static final List<Category> RUBRIC = List.of(
        category("Fundamentals","revenueGrowth","recurringProfit","quarterProfitGrowth","cashFlowOrCapital"),
        category("Technical strength","aboveEMA20","ema9Above20","ema20Rising","aboveSMA200","outperformsSmallcap"),
        category("Volume and liquidity","minimumTurnover","preferredTurnover","volumeConfirmation"),
        category("Valuation","peerPE","peerPB"),
        category("News and catalysts","scheduledEvent","recentDevelopment","quantifiedDevelopment"),
        category("Governance and risk","zeroPledge","cleanAudit"),
        category("Market and sector","marketAndSectorTrend"));
    private static Category category(String name, String... keys) {
        return new Category(name,null,keys.length*5,List.of(keys));
    }

    static Report evaluate(String exchange, String isin, Inputs inputs, EquityResearchController.Research market,
                           Snapshot snapshot, String classification, String classificationSource,
                           List<ExchangeNews.Item> news, OffsetDateTime now, boolean archiveFallback, List<String> warnings) {
        Stock stock = snapshot == null ? null : snapshot.stocks().getFirst();
        var missing = new LinkedHashSet<String>();
        var exclusions = new ArrayList<String>();
        var findings = new ArrayList<Finding>();
        Result result = null;
        if (stock != null) {
            result = new EquityScreen(Clock.fixed(now.toInstant(),IST)).screen(new Request(inputs.config,snapshot)).results().getFirst();
            missing.addAll(result.missing_data());
            exclusions.addAll(result.rejection_reasons());
            for (var entry : new TreeMap<>(result.sources()).entrySet()) {
                if (!EquityScreen.verified(entry.getValue(),snapshot.asOf())) continue;
                boolean current = fresh(entry.getValue().asOf(), now, maxAge(entry.getKey()));
                findings.add(new Finding(entry.getKey(), entry.getValue(), current));
                if (!current && (FACTS.contains(entry.getKey()) || FILED_TESTS.contains(entry.getKey())))
                    missing.add("Refresh " + entry.getKey());
            }
        } else {
            missing.addAll(List.of("Dated financial results and governance filings","Current exchange surveillance clearance",
                    "250 adjusted daily candles","Verified trade levels, depth and fees","Market regime and sector trend"));
        }

        boolean snapshotCurrent = snapshot != null && fresh(snapshot.asOf(),now,1);
        boolean adjusted = stock != null && !result.indicator_values().isEmpty()
                && verifiedFinding(findings,"adjustedHistory",false);
        List<Candle> bars = adjusted ? stock.adjustedDaily() : List.of();
        LocalDate today = now.atZoneSameInstant(IST).toLocalDate();
        LocalDate lastBar = bars.isEmpty() ? null : bars.getLast().openTime().toLocalDate();
        boolean archiveCurrent = market != null && !archiveFallback && !market.asOf().isBefore(today.minusDays(4));
        boolean barsCurrent = lastBar != null && !lastBar.isBefore(today.minusDays(4))
                && (market == null || !lastBar.isBefore(market.asOf()));
        if (market!=null && !archiveCurrent) missing.add("Refresh stale exchange observations");
        if (archiveFallback) missing.add("Refresh the unavailable data source");
        if (!barsCurrent) missing.add("Current adjusted price history");
        if (snapshot != null && !snapshotCurrent) missing.add("Refresh the dated company feed");
        if (stock != null && market != null && !market.symbol().equalsIgnoreCase(stock.symbol()))
            missing.add("Reconcile exchange symbol with the provider");
        if ("OTHER".equals(classification)) exclusions.add("Outside AMFI small-cap universe");
        if (market != null && market.averageTradedValueCr()!=null && market.averageTradedValueCr()<5)
            exclusions.add("Official 20-session turnover below ₹5 crore");
        boolean priceCurrent = !archiveFallback && snapshotCurrent && verifiedFinding(findings,"price",true)
                && stock.price()!=null && Double.isFinite(stock.price()) && stock.price()>0
                && (market == null || !stock.evidence().get("price").asOf().atZoneSameInstant(IST).toLocalDate().isBefore(market.asOf()));
        if (stock != null && !priceCurrent) missing.add("Current price evidence");
        Double price = priceCurrent ? stock.price() : archiveCurrent ? market.latestClose() : null;
        boolean complete = result != null && missing.isEmpty() && exclusions.isEmpty() && result.hard_gate_passed()
                && snapshotCurrent && priceCurrent && barsCurrent && !archiveFallback;
        var indicators = adjusted ? new TreeMap<>(result.indicator_values()) : new TreeMap<String,Double>();
        var categories = new ArrayList<Category>();
        for (var category : RUBRIC) {
            Integer points = null;
            if (result != null) {
                points = 0;
                for (String test : category.tests) {
                    boolean supported = FILED_TESTS.contains(test) ? verifiedFinding(findings,test,true)
                            : snapshotCurrent && (test.endsWith("Turnover") || barsCurrent && adjusted);
                    if (supported) points += result.score_breakdown().getOrDefault(test,0);
                }
            }
            else if (category.name.equals("Volume and liquidity") && archiveCurrent && market.averageTradedValueCr()!=null)
                points = turnoverPoints(market.averageTradedValueCr());
            categories.add(new Category(category.name,points,category.maximum,category.tests));
        }
        Integer score = stock == null || !adjusted || !verifiedFinding(findings,"financials",true) ? null : categories.stream().mapToInt(Category::points).sum();
        var plan = plan(inputs, stock, result, price, priceCurrent && barsCurrent && !archiveFallback
                && verifiedFinding(findings,"fees",true) && verifiedFinding(findings,"depth",true));
        var flags = stock == null || stock.gates()==null ? Map.<String,Boolean>of() : stock.gates();
        boolean restricted = snapshotCurrent && !archiveFallback && Boolean.FALSE.equals(flags.get("surveillanceClear")) && verifiedFinding(findings,"surveillance",true);
        boolean adverse = snapshotCurrent && !archiveFallback && (Boolean.FALSE.equals(flags.get("reportingClear")) && verifiedFinding(findings,"financials",true)
                || findings.stream().anyMatch(f -> f.current && f.key.equals("materialAdverseEvent") && Boolean.TRUE.equals(f.evidence.passed())));
        LocalDate entryDate = inputs.entryDate;
        boolean timeExit = !archiveFallback && snapshotCurrent && entryDate != null && barsCurrent && !bars.getFirst().openTime().toLocalDate().isAfter(entryDate)
                && bars.stream().filter(b -> !b.openTime().toLocalDate().isBefore(entryDate)).count() >= inputs.config.maxSessions();
        boolean aligned = snapshot != null && Set.of("NEUTRAL","BULLISH").contains(snapshot.regime())
                && verifiedFinding(findings,"marketAndSectorTrend",true) && Boolean.TRUE.equals(stock.evidence().get("marketAndSectorTrend").passed());
        boolean positive = indicators.getOrDefault("ema9",0d)>indicators.getOrDefault("ema20",0d)
                && indicators.getOrDefault("close",0d)>indicators.getOrDefault("ema20",0d);
        Action verdict;
        if (inputs.positionStatus == Position.UNKNOWN)
            verdict = waitFor("Position status is unknown.","Confirm whether you hold this share.",missing,exclusions,market);
        else if (inputs.positionStatus == Position.HELD && (restricted || adverse || timeExit
                || price!=null && inputs.heldStop!=null && price<=inputs.heldStop)) {
            String trigger = restricted ? "The cited surveillance check failed." : adverse ? "A material adverse filing was verified."
                    : timeExit ? "The configured final holding session has been reached." : "Price reached the recorded position stop of ₹"+inputs.heldStop+".";
            verdict = new Action(Decision.SELL,"HIGH",List.of("An existing position was confirmed.",trigger,
                    "Liquidity and price circuits determine whether an exit can fill."),"Review an exit now at the next tradable opportunity.");
        } else if (complete && aligned && positive && score!=null && score>=75 && plan.netRewardRisk!=null && plan.netRewardRisk>=2
                && !adverse && result.decision().equals("BUY_NOW")
                && !stock.plan().triggerObservedAt().isBefore(now.minusSeconds(60)) && inputs.positionStatus==Position.NONE)
            verdict = new Action(Decision.BUY,"HIGH",List.of("Current evidence passes the hard exclusions and scores "+score+"/100.",
                    "The confirmed "+stock.plan().setup()+" trigger is inside its entry zone.",
                    "Target 1 meets the 2:1 net reward/risk floor."),result.trigger_condition());
        else if (inputs.positionStatus==Position.HELD && complete && aligned && positive && !adverse
                && inputs.heldStop!=null && price>inputs.heldStop && plan.netRewardRisk!=null && plan.netRewardRisk>=2)
            verdict = new Action(Decision.HOLD,"HIGH",List.of("The observed price remains above the recorded stop.",
                    "Current filings, liquidity and market checks support the thesis.","Remaining target 1 reward covers at least twice the modeled risk."),
                    "Retain while the recorded stop and filing-based thesis remain intact.");
        else {
            String reason = !exclusions.isEmpty() ? exclusions.getFirst() : !missing.isEmpty() ? missing.getFirst()
                    : !positive ? "The adjusted price and EMA trend do not confirm the setup." : !aligned ? "Market or sector alignment is unconfirmed."
                    : adverse ? "A verified adverse event affects this company." : "The entry or retention conditions are unconfirmed.";
            String instruction = !missing.isEmpty() ? "Reassess after verifying "+String.join(", ",missing.stream().limit(3).toList())+"."
                    : !exclusions.isEmpty() ? "Reassess only after the exclusion clears."
                    : inputs.positionStatus==Position.HELD ? "Review the held stop and remaining target risk before acting."
                    : result!=null && result.trigger_condition()!=null ? "Await confirmation of "+result.trigger_condition()+" and all entry checks."
                    : "Await a confirmed entry with net reward/risk of at least 2:1.";
            verdict = waitFor(reason,instruction,missing,exclusions,market);
        }
        Quality quality = archiveFallback || market!=null && !archiveCurrent || snapshot!=null && !snapshotCurrent
                ? Quality.STALE : result!=null && missing.isEmpty() ? Quality.VERIFIED : market!=null && market.observedSessions()>=20 ? Quality.PRELIMINARY : Quality.INSUFFICIENT;
        String symbol = market!=null ? market.symbol() : stock!=null ? stock.symbol() : null;
        String company = stock!=null && stock.company()!=null ? stock.company() : market==null ? null : market.company();
        return new Report(UUID.randomUUID().toString(),now,isin,exchange,symbol,company,
                priceCurrent ? stock.evidence().get("price").asOf().atZoneSameInstant(IST).toLocalDate() : market==null ? null : market.asOf(),
                quality,inputs,verdict,score,categories,List.copyOf(exclusions),List.copyOf(missing),findings,indicators,plan,
                market,ResearchChart.series(bars,market),snapshot==null ? "UNKNOWN" : snapshot.regime(),classification,
                classificationSource,news,warnings,today.plusDays(1));
    }

    private static Action waitFor(String reason,String action,Set<String> missing,List<String> exclusions,EquityResearchController.Research market) {
        var reasons=new LinkedHashSet<String>();
        reasons.add(reason);
        reasons.addAll(exclusions);
        if (market!=null) reasons.add(market.observedSessions()+" dated exchange closes through "+market.asOf()+".");
        reasons.addAll(missing);
        reasons.add("A qualified entry or retention plan has not been established.");
        reasons.add("A confirmed trigger and sufficient current evidence are required.");
        return new Action(Decision.WAIT,"LOW",reasons.stream().limit(3).toList(),action);
    }

    private static int turnoverPoints(double cr) { return cr>=10 ? 10 : cr>=5 ? 5 : 0; }
    private static boolean verifiedFinding(List<Finding> list,String key,boolean current) {
        return list.stream().anyMatch(f -> f.key.equals(key) && (!current || f.current));
    }
    private static int maxAge(String k) {
        return switch (k) {
            case "price","surveillance","depth","fees","adjustedHistory","marketAndSectorTrend","outperformsSmallcap" -> 4;
            case "amfi" -> 200;
            case "cleanAudit","cashFlowOrCapital" -> 460;
            case "financials","revenueGrowth","recurringProfit","quarterProfitGrowth","zeroPledge" -> 150;
            case "recentDevelopment","quantifiedDevelopment","scheduledEvent" -> 90;
            default -> 30;
        };
    }
    private static boolean fresh(OffsetDateTime asOf,OffsetDateTime now,int days) {
        return asOf!=null && !asOf.isAfter(now) && !asOf.isBefore(now.minusDays(days));
    }
    private static PlanView plan(Inputs in,Stock stock,Result result,Double price,boolean complete) {
        String time = "Exit by close of session "+in.config.maxSessions()+" counting entry as session 1.";
        Plan p = stock==null ? null : stock.plan();
        if (!complete || p==null || result.net_rr()==null)
            return new PlanView(null,null,in.heldStop,null,null,null,null,null,null,null,null,null,null,time);
        boolean held=in.positionStatus==Position.HELD;
        Double stop=held ? in.heldStop : p.stop();
        Double base=held ? price : p.entry();
        Long units=held ? in.quantity : result.position_size();
        long reference=units!=null && units>0 ? units : p.referenceUnits();
        Double upside=base==null ? null : p.target1()-base;
        Double loss=base==null || stop==null ? null : base-stop;
        double entryCost=held ? 0 : p.entryCost()+p.entryFixed()/reference;
        Double gain=upside==null ? null : upside-entryCost-p.targetExitCost()-p.target1Fixed()/reference;
        Double risk=loss==null ? null : loss+entryCost+p.stopExitCost()+p.stopFixed()/reference;
        return new PlanView(p.entryLow(),base,stop,p.target1(),p.target2(),upside,gain,loss,risk,
                risk==null || risk<=0 || gain==null ? null : gain/risk,units,p.triggerCondition(),p.invalidation(),time);
    }
}
