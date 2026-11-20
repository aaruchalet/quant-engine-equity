package com.aaruchalet.quant.backtest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.aaruchalet.quant.backtest.IndexOptionsReplay.*;

/** Official SmartAPI REST endpoints. No order transport retries. */
class SmartMarket {
    static final String ORDER = "order/v1/";
    static final String MASTER = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json";
    static final DateTimeFormatter EXPIRY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH);
    static final DateTimeFormatter FEED = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy HH:mm:ss").toFormatter(Locale.ENGLISH);
    record Instrument(String exchange, String token, String symbol) {}
    record Contract(Index index, Right right, Instrument instrument, LocalDate expiry, BigDecimal strike, BigDecimal tick) {}
    record Quote(BigDecimal ltp, BigDecimal bid, long bidSize, BigDecimal ask, long askSize, LocalDateTime time) {}
    record Signal(V2SignalState.Decision decision, double lastProxyClose) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    final AngelOneHistoricalClient history;
    final JsonMapper json;
    private final long maxAge;
    private final URI api;
    private final URI masterUrl;
    private final Clock clock;
    private LocalDate masterDate;
    private JsonNode master;
    private final Map<String,Long> lastRead = new HashMap<>();
    private final Map<Index,Signal> signals = new EnumMap<>(Index.class);
    private LocalDateTime signalSlot;

    SmartMarket(AngelOneHistoricalClient history, JsonMapper json, long maxAge) {
        this(history,json,maxAge,URI.create("https://apiconnect.angelone.in/rest/secure/angelbroking/"),
                URI.create(MASTER),Clock.system(AngelOneHistoricalClient.IST));
    }

    SmartMarket(AngelOneHistoricalClient history, JsonMapper json, long maxAge, URI api, URI masterUrl, Clock clock) {
        this.history=history; this.json=json; this.maxAge=maxAge;
        this.api=api; this.masterUrl=masterUrl; this.clock=clock;
    }

    JsonNode request(String path, Object body) throws Exception {
        if (body==null || path.equals("market/v1/quote/")) {
            long delay = 1_050_000_000L - (System.nanoTime() - lastRead.getOrDefault(path, System.nanoTime()-1_050_000_000L));
            if (delay>0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
            lastRead.put(path,System.nanoTime());
        }
        var builder = history.authenticated(api.resolve(path));
        var req = body == null ? builder.GET() : builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("SmartAPI " + path + " HTTP " + resp.statusCode());
        JsonNode root;
        try { root = json.readTree(resp.body()); } catch (RuntimeException e) { throw new IOException("Invalid SmartAPI response"); }
        if (!root.path("status").asBoolean(false))
            throw new IOException("SmartAPI declined " + path + " (" + root.path("errorcode").asText("").replaceAll("[^A-Za-z0-9_-]", "") + ")");
        return root.path("data");
    }

    void loadMaster(LocalDate date) throws Exception {
        if (date.equals(masterDate)) return;
        var req = HttpRequest.newBuilder(masterUrl).timeout(Duration.ofSeconds(45)).GET().build();
        var response = http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Angel One instrument master unavailable");
            var data=json.createArrayNode();
            try (var parser=json.createParser(stream)) {
                if (parser.nextToken()!=tools.jackson.core.JsonToken.START_ARRAY) throw new IOException("Invalid instrument master");
                while (parser.nextToken()==tools.jackson.core.JsonToken.START_OBJECT) {
                    JsonNode row=json.readTree(parser);
                    String venue=field(row,"exch_seg"), name=field(row,"name");
                    if (venue.equals("NSE") && (field(row,"symbol").endsWith("-EQ") || field(row,"instrumenttype").equals("AMXIDX"))
                            || venue.equals("BSE") && field(row,"instrumenttype").equals("AMXIDX")
                            || field(row,"instrumenttype").equals("OPTIDX") && (name.equals("NIFTY") || name.equals("SENSEX")))
                        data.add(row);
                }
                if (parser.currentToken()!=tools.jackson.core.JsonToken.END_ARRAY || data.isEmpty())
                    throw new IOException("Incomplete instrument master");
            }
            master=data; masterDate=date;
        }
    }

    static String field(JsonNode n,String key) { return n.path(key).asText("").strip(); }
    static BigDecimal decimal(JsonNode n,String key) { return new BigDecimal(field(n,key)); }
    static Instrument instrument(JsonNode row) {
        var token = field(row,"token"); var symbol = field(row,"symbol");
        if (!token.matches("[0-9]+") || symbol.isBlank()) throw new IllegalStateException("Invalid master instrument");
        return new Instrument(field(row,"exch_seg"),token,symbol);
    }

    Instrument underlying(Index index, IndexOptionsConfig cfg, boolean proxy) {
        boolean nifty = index == Index.NIFTY;
        String wanted = proxy ? (nifty ? cfg.niftyProxy() : cfg.sensexProxy()) : (nifty ? cfg.niftySignal() : cfg.sensexSignal());
        String exchange = proxy || nifty ? "NSE" : "BSE";
        Instrument found = null;
        for (var row : master) {
            if (!exchange.equals(field(row,"exch_seg"))) continue;
            String symbol = field(row,"symbol");
            boolean match = proxy ? symbol.equalsIgnoreCase(wanted + "-EQ")
                    : field(row,"instrumenttype").equals("AMXIDX") && (symbol.equalsIgnoreCase(wanted)
                    || index == Index.SENSEX && wanted.equalsIgnoreCase("BSE SENSEX") && symbol.equalsIgnoreCase("SENSEX"));
            if (match) {
                if (found != null) throw new IllegalStateException("Ambiguous instrument " + wanted);
                found = instrument(row);
            }
        }
        if (found == null) throw new IllegalStateException("Missing instrument " + wanted);
        return found;
    }

    Contract select(Index index, Right right, BigDecimal spot, LocalDate date, int offset) {
        return select(master,index,right,spot,date,offset);
    }
    static Contract select(JsonNode rows, Index index, Right right, BigDecimal spot, LocalDate date, int offset) {
        var expiries = new TreeMap<LocalDate,TreeMap<BigDecimal,EnumMap<Right,Contract>>>();
        for (var row : rows) {
            if (!field(row,"exch_seg").equals(index == Index.NIFTY ? "NFO" : "BFO")
                    || !field(row,"instrumenttype").equals("OPTIDX") || !field(row,"name").equalsIgnoreCase(index.name())) continue;
            var expiry = LocalDate.parse(field(row,"expiry"),EXPIRY);
            if (expiry.isBefore(date)) continue;
            String symbol = field(row,"symbol");
            Right leg = symbol.endsWith("CE") ? Right.CE : symbol.endsWith("PE") ? Right.PE : null;
            if (leg == null) continue;
            var strike = decimal(row,"strike").movePointLeft(2);
            var tick = decimal(row,"tick_size").movePointLeft(2);
            var books = expiries.computeIfAbsent(expiry,k -> new TreeMap<>())
                    .computeIfAbsent(strike,k -> new EnumMap<>(Right.class));
            if (decimal(row,"lotsize").intValueExact() != index.lotSize || tick.signum() <= 0 || strike.signum() <= 0) continue;
            if (books.put(leg,new Contract(index,leg,instrument(row),expiry,strike,tick)) != null)
                throw new IllegalStateException("Ambiguous option master contract");
        }
        if (expiries.isEmpty()) return null;
        var ladder = expiries.firstEntry().getValue();
        var chain = new Chain(index,date.atStartOfDay(),expiries.firstKey(),index.lotSize,spot,
                ladder.keySet().stream().map(k -> new Strike(k,null,null)).toList());
        var selected = chain.select(offset);
        return selected == null ? null : ladder.get(selected.strike()).get(right);
    }

    Map<Instrument,Quote> quotes(Instrument... requested) throws Exception {
        var tokens = new HashMap<String,List<String>>();
        for (var i : requested) tokens.computeIfAbsent(i.exchange,k -> new ArrayList<>()).add(i.token);
        var data = request("market/v1/quote/",Map.of("mode","FULL","exchangeTokens",tokens));
        var result = new HashMap<Instrument,Quote>();
        for (var row : data.path("fetched")) for (var i : requested) {
            if (!field(row,"exchange").equals(i.exchange) || !field(row,"symbolToken").equals(i.token)) continue;
            var time = LocalDateTime.parse(field(row,"exchFeedTime"),FEED);
            var age = Duration.between(time,LocalDateTime.now(clock)).toSeconds();
            if (age < -1 || age > maxAge) throw new IllegalStateException("Stale market quote: " + i.symbol);
            var buy = row.path("depth").path("buy").path(0);
            var sell = row.path("depth").path("sell").path(0);
            var q = new Quote(decimal(row,"ltp"), level(buy),buy.path("quantity").asLong(0),level(sell),
                    sell.path("quantity").asLong(0),time);
            if (q.ltp.signum() <= 0 || q.ask.signum() > 0 && q.bid.compareTo(q.ask)>0)
                throw new IllegalStateException("Invalid market quote: " + i.symbol);
            result.put(i,q);
        }
        if (result.size() != requested.length) throw new IllegalStateException("Missing requested market quote");
        return result;
    }
    private static BigDecimal level(JsonNode row) {
        return row.hasNonNull("price") ? decimal(row,"price") : BigDecimal.ZERO;
    }

    Signal signal(Index index, LocalDateTime slot, IndexOptionsConfig options, VwapEmaV2Config strategy) throws Exception {
        if (!slot.equals(signalSlot)) { signals.clear(); signalSlot=slot; }
        if (signals.containsKey(index)) return signals.get(index);
        var spot = underlying(index,options,false); var etf = underlying(index,options,true);
        var start = slot.toLocalDate().minusDays(7).atTime(strategy.marketOpen());
        var end = slot.minusMinutes(5);
        var spots = history.fetch(spot.exchange,spot.token,start,end);
        var proxies = history.fetch(etf.exchange,etf.token,start,end);
        var prices = new TreeMap<LocalDateTime,Candle>();
        for (var c : spots) if (prices.put(c.openTime(),c)!=null) throw new IllegalStateException("Duplicate index bar");
        var bars = new TreeMap<LocalDateTime,Candle>();
        for (var c : proxies) if (bars.put(c.openTime(),c)!=null) throw new IllegalStateException("Duplicate ETF bar");
        for (var t=slot.toLocalDate().atTime(strategy.marketOpen()); !t.isAfter(end); t=t.plusMinutes(5))
            if (!prices.containsKey(t) || !bars.containsKey(t)) throw new IllegalStateException("Incomplete current signal session");
        var state = new V2SignalState(strategy);
        Signal latest = null;
        for (var entry : bars.entrySet()) {
            var t = entry.getKey();
            if (t.toLocalTime().isBefore(strategy.marketOpen()) || t.toLocalTime().isAfter(strategy.squareOff())) continue;
            var price=prices.get(t);
            if (price != null) latest = new Signal(state.onClose(price,entry.getValue()),entry.getValue().close());
        }
        if (!bars.containsKey(end) || latest == null) throw new IllegalStateException("Latest closed candle unavailable");
        signals.put(index,latest);
        return latest;
    }
}
