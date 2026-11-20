package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only, on-demand research based on official dated exchange close/turnover archives.
 * A short unadjusted series cannot establish exchange surveillance clearance or a BUY signal.
 */
@RestController
@RequestMapping("/api/equity")
class EquityResearchController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int REQUIRED_SESSIONS = 20;
    private final EquityDiscovery discovery;

    EquityResearchController(EquityDiscovery discovery) {
        this.discovery = discovery;
    }

    record DailyPrice(LocalDate session, double close, double tradedValueCr, String source) {}
    record Research(String exchange, String symbol, String isin, String status,
                    OffsetDateTime retrievedAt, LocalDate asOf, int observedSessions,
                    int requiredSessions, Double latestClose, Double previousClose,
                    Double priceChangePercent, Double averageTradedValueCr,
                    Double ema9Indicative, Double ema20Indicative, Double rsi14Indicative,
                    Double periodLow, Double periodHigh, List<DailyPrice> dailyPrices,
                    List<String> missingChecks, List<String> limitations) {}

    @GetMapping("/research")
    Research research(@RequestParam String isin, @RequestParam String exchange) {
        String normalizedIsin = isin == null ? "" : isin.strip().toUpperCase(Locale.ROOT);
        String normalizedExchange = exchange == null ? "" : exchange.strip().toUpperCase(Locale.ROOT);
        if (!normalizedIsin.matches("INE[A-Z0-9]{9}")
                || !Set.of("NSE", "BSE").contains(normalizedExchange))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply a valid equity ISIN and NSE/BSE exchange");

        var days = new ArrayList<EquityDiscovery.Archive>();
        var limitations = new ArrayList<String>();
        try {
            discovery.readSessions(normalizedExchange, LocalDate.now(IST), days, Instant.now().plusSeconds(42));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Exchange research request interrupted");
        } catch (IOException | RuntimeException ex) {
            if (days.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Official exchange archives unavailable for research. No substitute prices were created.");
            limitations.add("Exchange history download was interrupted; research coverage is partial.");
        }
        return summarize(normalizedIsin, normalizedExchange, days,
                ZonedDateTime.now(IST).toOffsetDateTime(), limitations);
    }

    static Research summarize(String isin, String exchange, List<EquityDiscovery.Archive> archives,
                              OffsetDateTime retrievedAt, List<String> sourceLimitations) {
        var ordered = new ArrayList<>(archives);
        ordered.sort(Comparator.comparing(EquityDiscovery.Archive::date));
        var data = new ArrayList<DailyPrice>();
        String symbol = null;
        for (var archive : ordered) {
            var quote = archive.quotes().get(isin);
            if (quote == null) continue;
            symbol = quote.symbol();
            data.add(new DailyPrice(archive.date(), quote.close(),
                    quote.turnover() / 10_000_000d, archive.source().toString()));
        }
        if (data.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Security was not found in the available verified " + exchange + " archives.");
        var limits = new ArrayList<>(sourceLimitations);
        LocalDate last = data.getLast().session();
        boolean full = ordered.size() >= REQUIRED_SESSIONS && data.size() >= REQUIRED_SESSIONS;
        if (!full) limits.add("Fewer than 20 complete matching exchange sessions; 20-day liquidity not verified.");
        if (last.isBefore(retrievedAt.atZoneSameInstant(IST).toLocalDate().minusDays(4)))
            limits.add("Latest verified close is stale; do not use as an actionable quote.");
        limits.add("Closing prices are unadjusted; indicator values are indicative and may be distorted by corporate actions.");
        limits.add("These archives cannot verify company filings, fundamentals, ownership, surveillance, market depth or trade feasibility.");
        List<String> missing = List.of(
                "Current ASM/GSM, trading series and exchange surveillance clearance",
                "Current quarterly/annual statements, revenue, EPS, debt and operating cash flow",
                "Promoter pledging, institutional ownership, auditor and governance disclosures",
                "250 corporate-action-adjusted daily candles and weekly trend validation",
                "Bid-ask spread, order-book depth, delivery eligibility and practical exit liquidity",
                "Verified recent news and dated catalysts within the holding period",
                "Net-of-cost entry, stop, target, risk/reward and position-sizing plan"
        );
        var values = data.stream().map(DailyPrice::close).toList();
        Double average = full ? data.stream().mapToDouble(DailyPrice::tradedValueCr).average().orElseThrow() : null;
        Double prev = data.size() < 2 ? null : data.get(data.size()-2).close();
        Double change = prev == null ? null : (data.getLast().close() / prev - 1) * 100;
        Double ema9 = full ? ema(values, 9) : null;
        Double ema20 = full ? ema(values, 20) : null;
        Double rsi = full ? rsi14(values) : null;
        String status = full && limits.stream().noneMatch(s -> s.startsWith("Latest verified close is stale"))
                ? "PRELIMINARY_RESEARCH" : "PARTIAL_DATA";
        return new Research(exchange,symbol,isin,status,retrievedAt,last,data.size(),REQUIRED_SESSIONS,
                data.getLast().close(),prev,change,average,ema9,ema20,rsi,
                data.stream().mapToDouble(DailyPrice::close).min().orElseThrow(),
                data.stream().mapToDouble(DailyPrice::close).max().orElseThrow(),
                List.copyOf(data),missing,List.copyOf(limits));
    }

    private static double ema(List<Double> closes, int period) {
        double coefficient = 2d / (period + 1);
        double value = closes.getFirst();
        for (int i=1; i<closes.size(); i++)
            value = coefficient * closes.get(i) + (1 - coefficient) * value;
        return value;
    }

    private static Double rsi14(List<Double> closes) {
        if (closes.size() < 15) return null;
        double gains=0,losses=0;
        for (int i=1; i<=14; i++) {
            double delta=closes.get(i)-closes.get(i-1);
            gains += Math.max(delta,0);
            losses += Math.max(-delta,0);
        }
        gains /= 14;
        losses /= 14;
        for (int i=15; i<closes.size(); i++) {
            double delta=closes.get(i)-closes.get(i-1);
            gains=(gains*13+Math.max(delta,0))/14;
            losses=(losses*13+Math.max(-delta,0))/14;
        }
        if (losses==0) return gains==0 ? 50d : 100d;
        return 100d - 100d / (1d + gains/losses);
    }
}
