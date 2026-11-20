package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Public NSE website reader (not an AI, broker or paid-data adapter).
 * Every call requests current source data. No stale cache and no synthetic BUY decisions.
 * NSE may refuse automated requests: return 503, never bypass or fabricate.
 */
@RestController
@RequestMapping("/api/equity/market")
class EquityPublicMarket {
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9&_.-]{1,25}");
    private static final Pattern QUERY = Pattern.compile("[\\p{L}\\p{N} .&_-]{2,50}");
    private static final String INDEX = "NIFTY SMALLCAP 250";
    private static final URI OFFICIAL = URI.create("https://www.nseindia.com");
    private final URI base;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private boolean sessionInitialized;

    @Autowired
    EquityPublicMarket(ObjectMapper mapper) {
        this(mapper, OFFICIAL);
    }
    /** Fixed localhost test origin only. Production always uses the official HTTPS host. */
    EquityPublicMarket(ObjectMapper mapper, URI origin) {
        this.mapper=mapper;
        this.base=origin;
        var cookies=new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).cookieHandler(cookies).build();
    }

    private synchronized JsonNode get(String path) {
        try {
            if (!sessionInitialized) {
                // A normal site entry visit establishes a standard web session; no CAPTCHA or
                // authentication bypass. A 403/429 remains a hard error.
                response("/");
                sessionInitialized=true;
            }
            HttpResponse<String> res=response(path);
            if (res.statusCode()!=200) throw unavailable("NSE returned HTTP "+res.statusCode()+". Automated access may be restricted.");
            if (res.body().length()>4_000_000) throw unavailable("NSE response exceeded the allowed size");
            JsonNode value=mapper.readTree(res.body());
            if (value==null || value.isNull() || value.isMissingNode())
                throw unavailable("NSE returned empty market data");
            return value;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("NSE market-data request interrupted");
        } catch (IOException ex) {
            throw unavailable("Official NSE market data unavailable: "+ex.getClass().getSimpleName());
        }
    }

    private HttpResponse<String> response(String path) throws IOException, InterruptedException {
        var request=HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept","application/json,text/html;q=0.9")
                .header("User-Agent","QuantEngineResearch/1.0 (local informational dashboard)")
                .GET().build();
        var res=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode()==403 || res.statusCode()==429 || res.statusCode()==401)
            throw unavailable("Official NSE blocked the request (HTTP "+res.statusCode()+"); an authorized data feed is required.");
        return res;
    }
    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,message);
    }
    private static String cleanSymbol(String value) {
        if(value==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"NSE symbol is required");
        String symbol=value.strip().toUpperCase(Locale.ROOT);
        if(!SYMBOL.matcher(symbol).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid NSE ticker symbol (e.g. SBIN)");
        return symbol;
    }
    private static String text(JsonNode obj, String path) {
        return obj.path(path).asText("");
    }
    private static Double positive(JsonNode n) {
        if(!n.isNumber() && !n.isTextual()) return null;
        try {
            double d=Double.parseDouble(n.asText().replace(",", ""));
            return Double.isFinite(d) && d>0 ? d : null;
        } catch (NumberFormatException ex) { return null; }
    }
    private static Double finite(JsonNode n) {
        if(!n.isNumber() && !n.isTextual()) return null;
        try {
            double d=Double.parseDouble(n.asText().replace(",", ""));
            return Double.isFinite(d)?d:null;
        } catch (NumberFormatException ex) {return null;}
    }
    private static Map<String,Object> record(Object... pairs) {
        var m=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i],pairs[i+1]);
        return m;
    }
    private static String encode(String v) {
        return URLEncoder.encode(v,StandardCharsets.UTF_8);
    }

    @GetMapping("/search")
    Map<String,Object> search(@RequestParam String q) {
        if (q==null || !QUERY.matcher(q.strip()).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter at least two letters of a company or NSE symbol");
        JsonNode root=get("/api/search/autocomplete?q="+encode(q.strip()));
        var hits=new ArrayList<Map<String,Object>>();
        JsonNode results=root.path("symbols");
        if(!results.isArray()) results=root.path("data");
        if(results.isArray()) for (var entry:results) {
            String symbol=text(entry,"symbol").toUpperCase(Locale.ROOT);
            if(!SYMBOL.matcher(symbol).matches()) continue;
            hits.add(record("symbol",symbol,"company",text(entry,"symbol_info"),
                    "exchange","NSE"));
            if(hits.size()==15) break;
        }
        return record("query",q.strip(),"source","https://www.nseindia.com/",
                "fetchedAt",Instant.now().toString(),"suggestions",hits);
    }

    @GetMapping("/quote/{symbol}")
    Map<String,Object> quote(@PathVariable String symbol) {
        String selected=cleanSymbol(symbol);
        JsonNode root=get("/api/quote-equity?symbol="+encode(selected));
        return parseQuote(selected,root,Instant.now());
    }

    static Map<String,Object> parseQuote(String selected,JsonNode root,Instant now) {
        JsonNode info=root.path("info"), price=root.path("priceInfo"),
                security=root.path("securityInfo"), metadata=root.path("metadata");
        String confirmed=text(info,"symbol");
        if(!selected.equalsIgnoreCase(confirmed))
            throw unavailable("Official NSE quote did not identify the requested equity");
        Double last=positive(price.path("lastPrice"));
        if(last==null) throw unavailable("NSE quote has no verified positive last price");
        return record("symbol",selected,"company",text(info,"companyName"),
                "exchange","NSE","isin",text(info,"isin"),"price",last,
                "previousClose",positive(price.path("previousClose")),
                "changePercent",finite(price.path("pChange")),
                "dayHigh",positive(price.path("intraDayHighLow").path("max")),
                "dayLow",positive(price.path("intraDayHighLow").path("min")),
                "week52High",positive(price.path("weekHighLow").path("max")),
                "week52Low",positive(price.path("weekHighLow").path("min")),
                "sector",text(root.path("industryInfo"),"sector"),
                "industry",text(root.path("industryInfo"),"industry"),
                "surveillance",security.path("surveillance").isNull()?"":security.path("surveillance").toString(),
                "marketTimestamp",text(metadata,"lastUpdateTime"),
                "fetchedAt",now.toString(),"source","https://www.nseindia.com/get-quotes/equity?symbol="+encode(selected),
                "amfiClassification","UNVERIFIED","fundamentalAnalysis","NOT_AVAILABLE_FROM_THIS_QUOTE",
                "decision","INSUFFICIENT_DATA","liveVerified",false);
    }

    @GetMapping("/screen")
    Map<String,Object> screen(@RequestParam(defaultValue="10") double minPrice,
                              @RequestParam(defaultValue="100") double maxPrice) {
        if(!Double.isFinite(minPrice) || !Double.isFinite(maxPrice)
                || minPrice<=0 || maxPrice<=minPrice)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Price range must satisfy 0 < minimum < maximum");
        JsonNode root=get("/api/equity-stockIndices?index="+encode(INDEX));
        return parseIndex(root,minPrice,maxPrice,Instant.now());
    }

    static Map<String,Object> parseIndex(JsonNode root,double minPrice,double maxPrice,Instant now) {
        JsonNode rows=root.path("data");
        if(!rows.isArray() || rows.size()==0) throw unavailable("NSE Smallcap index response did not include constituents");
        var matches=new ArrayList<Map<String,Object>>();
        int verified=0;
        for(var row:rows) {
            String symbol=text(row,"symbol");
            if(!SYMBOL.matcher(symbol).matches() || symbol.equalsIgnoreCase(INDEX)) continue;
            Double last=positive(row.path("lastPrice"));
            if(last==null) continue;
            verified++;
            if(last<minPrice || last>=maxPrice) continue;
            matches.add(record("symbol",symbol,"price",last,"changePercent",finite(row.path("pChange")),
                    "volume",finite(row.path("totalTradedVolume")),
                    "tradedValue",finite(row.path("totalTradedValue")),
                    "decision","INSUFFICIENT_DATA"));
        }
        if(verified==0) throw unavailable("NSE Smallcap index source contained no validated equity quotes");
        matches.sort(Comparator.comparing(m->String.valueOf(m.get("symbol"))));
        return record("universe","NIFTY SMALLCAP 250 CONSTITUENTS (NOT COMPLETE AMFI SMALL-CAP UNIVERSE)",
                "completeAmfiCoverage",false,"fundamentalsVerified",false,
                "marketTimestamp",text(root,"timestamp"),"fetchedAt",now.toString(),
                "source","https://www.nseindia.com/market-data/live-equity-market?symbol=NIFTY%20SMALLCAP%20250",
                "constituentsWithPrices",verified,"matched",matches.size(),
                "minimumPrice",minPrice,"maximumPriceExclusive",maxPrice,
                "decision","PRELIMINARY_RESEARCH_ONLY","stocks",matches);
    }
}
