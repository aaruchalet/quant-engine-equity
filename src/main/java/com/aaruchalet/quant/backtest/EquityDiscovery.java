package com.aaruchalet.quant.backtest;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.*;
import org.jsoup.Jsoup;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/equity")
class EquityDiscovery {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final URI AMFI = URI.create("https://www.amfiindia.com/otherdata/categorisation-of-stocks");
    private static final int SESSIONS = 20;
    private static final int MAX_BYTES = 12_000_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final SequencedMap<URI,byte[]> archiveCache = new LinkedHashMap<>();

    record Quote(String exchange, String symbol, String isin, double close, double turnover,
                 String company, Double open, Double high, Double low, Double volume, String securityCode) {
        Quote(String exchange, String symbol, String isin, double close, double turnover) {
            this(exchange, symbol, isin, close, turnover, null, null, null, null, null, null);
        }
    }
    record Candidate(String exchange, String symbol, String isin, String company, double close, LocalDate session,
                     Double turnoverCr, String decision) {}
    record Archive(LocalDate date, URI source, Map<String,Quote> quotes) {}
    record Scan(String outcome, OffsetDateTime retrievedAt, URI amfiSource, int smallCaps, int mapped,
                int priceMatches, int liquidityPasses, Map<String,LocalDate> priceDates,
                List<URI> exchangeSources, List<String> limitations, List<Candidate> candidates) {}

    record Classification(URI source, Map<String,String> smallCaps, LocalDate checked) {}
    private Classification classification;

    synchronized Classification classification(LocalDate today) throws IOException, InterruptedException {
        if (classification != null && classification.checked.equals(today)) return classification;
        URI workbook = expectedWorkbook(today);
        byte[] bytes = download(workbook);
        if (bytes == null) {
            workbook = workbookLink(required(AMFI));
            bytes = required(workbook);
        }
        classification = new Classification(workbook, classifications(bytes), today);
        return classification;
    }

    @PostMapping("/discover")
    synchronized Scan scan(@RequestBody EquityScreen.Config config) throws InterruptedException {
        EquityScreen.validateConfig(config);
        var now = ZonedDateTime.now(IST);
        var limitations = new ArrayList<String>();
        URI workbook;
        Map<String,String> smallCaps;
        try {
            var current = classification(now.toLocalDate());
            workbook = current.source;
            smallCaps = current.smallCaps;
        } catch (IOException | RuntimeException e) {
            return new Scan("DATA UNAVAILABLE",now.toOffsetDateTime(),AMFI,0,0,0,0,Map.of(),List.of(),
                    List.of("The current AMFI classification could not be loaded."),List.of());
        }

        var best = new HashMap<String,Candidate>();
        var dates = new TreeMap<String,LocalDate>();
        var sources = new ArrayList<URI>();
        var mapped = new HashSet<String>();

        for (String exchange : List.of("NSE","BSE")) {
            List<Archive> days = new ArrayList<>();
            try {
                readSessions(exchange,now.toLocalDate(),days,Instant.now().plusSeconds(42));
            } catch (IOException | RuntimeException e) {
                limitations.add(exchange + " archive download stopped. Coverage may be incomplete.");
            }
            if (days.isEmpty()) {
                limitations.add(exchange + " closing prices unavailable.");
                continue;
            }
            Archive latest = days.getFirst();
            dates.put(exchange,latest.date);
            days.forEach(day -> sources.add(day.source));
            if (days.size() < SESSIONS)
                limitations.add(exchange + " traded value unavailable for the full 20 sessions.");
            if (latest.date.isBefore(now.toLocalDate().minusDays(4)))
                limitations.add(exchange + " closing prices are stale.");
            boolean usableTurnover = days.size() == SESSIONS && !latest.date.isBefore(now.toLocalDate().minusDays(4));
            for (Quote row : latest.quotes.values()) {
                if (!smallCaps.containsKey(row.isin)) continue;
                mapped.add(row.isin);
                if (row.close < config.minPrice() || row.close >= config.maxPrice()) continue;
                Double turnover = usableTurnover ? turnover(days,row.isin) : null;
                String decision = turnover == null ? "INSUFFICIENT DATA" : turnover < 5 ? "BELOW LIQUIDITY FLOOR" : "RESEARCH REQUIRED";
                var candidate = new Candidate(exchange,row.symbol,row.isin,smallCaps.get(row.isin),row.close,
                        latest.date,turnover,decision);
                best.merge(row.isin,candidate,(left,right) -> {
                    int order = left.session.compareTo(right.session);
                    if (order == 0) order = Double.compare(sortTurnover(left),sortTurnover(right));
                    return order >= 0 ? left : right;
                });
            }
        }

        var ranked = best.values().stream().sorted(Comparator.comparingDouble(EquityDiscovery::sortTurnover)
                .reversed().thenComparing(Candidate::isin)).toList();
        int passes = (int)ranked.stream().filter(c -> "RESEARCH REQUIRED".equals(c.decision)).count();
        limitations.add("This scan verifies closing prices, AMFI classification and traded value. Buy decisions require verified surveillance, filings, adjusted history and trade plans.");
        String outcome = dates.isEmpty() ? "DATA UNAVAILABLE" : limitations.size() > 1 ? "PARTIAL DISCOVERY" : "DISCOVERY COMPLETE";
        return new Scan(outcome,ZonedDateTime.now(IST).toOffsetDateTime(),
                workbook,smallCaps.size(),mapped.size(),ranked.size(),passes,dates,sources,limitations,ranked);
    }


    /**
     * A fresh, read-only EOD lookup from the official downloadable exchange archives.
     * Not a live quote and never an order recommendation.
     */
    @GetMapping("/lookup")
    synchronized Map<String,Object> lookup(@RequestParam String q) {
        String query=q==null?"":q.strip().toUpperCase(Locale.ROOT);
        if(!query.matches("[A-Z0-9&_. -]{2,50}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a company/ticker search of 2–50 characters");
        var found=new LinkedHashMap<String,Map<String,Object>>();
        var errors=new ArrayList<String>();
        for(String exchange:List.of("NSE","BSE")) {
            try {
                var archive=latestArchive(exchange);
                for(var row:archive.quotes().values()) {
                    if(!row.symbol().toUpperCase(Locale.ROOT).contains(query.replace(" ",""))) continue;
                    var match=new LinkedHashMap<String,Object>();
                    match.put("symbol",row.symbol());
                    match.put("company",row.symbol());
                    match.put("exchange",exchange);
                    match.put("asOf",archive.date().toString());
                    match.put("isin",row.isin());
                    found.putIfAbsent(row.isin(),match);
                    if(found.size()>=15) break;
                }
            } catch (IOException | InterruptedException | RuntimeException ex) {
                if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                errors.add(exchange+" EOD archive unavailable");
            }
        }
        if(found.isEmpty() && errors.size()==2)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Official exchange closing-price archives are unavailable.");
        return Map.of("query",query,"fetchedAt",Instant.now().toString(),"dataKind","EOD_NOT_LIVE",
                "sources",List.of("https://nsearchives.nseindia.com","https://www.bseindia.com"),
                "limitations",errors,"suggestions",new ArrayList<>(found.values()));
    }

    @GetMapping("/eod/{symbol}")
    synchronized Map<String,Object> eod(@PathVariable String symbol) {
        String requested=symbol==null?"":symbol.strip().toUpperCase(Locale.ROOT);
        if(!requested.matches("[A-Z0-9&_.-]{1,25}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid NSE/BSE equity ticker");
        Quote matched=null;
        Archive source=null;
        var errors=new ArrayList<String>();
        for(String exchange:List.of("NSE","BSE")) {
            try {
                var archive=latestArchive(exchange);
                for (var candidate:archive.quotes().values()) {
                    if(!candidate.symbol().equalsIgnoreCase(requested)) continue;
                    if(source==null || archive.date().isAfter(source.date())
                            || archive.date().equals(source.date()) && candidate.turnover()>matched.turnover()) {
                        source=archive;matched=candidate;
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException ex) {
                if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
                errors.add(exchange+" EOD archive unavailable");
            }
        }
        if(matched==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                errors.size()==2?"Official exchange archives unavailable":"Symbol not present in available recent EOD reports.");
        var result=new LinkedHashMap<String,Object>();
        result.put("symbol",matched.symbol());
        result.put("company",matched.symbol());
        result.put("exchange",matched.exchange());
        result.put("isin",matched.isin());
        result.put("price",matched.close());
        result.put("tradedValueInr",matched.turnover());
        result.put("fetchedAt",Instant.now().toString());
        result.put("marketTimestamp",source.date().toString());
        result.put("dataKind","OFFICIAL_CLOSING_PRICE_NOT_LIVE");
        result.put("source",source.source().toString());
        result.put("sourceGaps",errors);
        result.put("amfiClassification","UNVERIFIED");
        result.put("decision","INSUFFICIENT_DATA");
        result.put("liveVerified",false);
        return result;
    }

    private Archive latestArchive(String exchange) throws IOException, InterruptedException {
        var today=LocalDate.now(IST);
        for(int i=0;i<12;i++) {
            LocalDate date=today.minusDays(i);
            String stamp=DateTimeFormatter.BASIC_ISO_DATE.format(date);
            String file="BhavCopy_"+exchange+"_CM_0_0_0_"+stamp+"_F_0000";
            URI uri=URI.create(exchange.equals("NSE")
                    ? "https://nsearchives.nseindia.com/content/cm/"+file+".csv.zip"
                    : "https://www.bseindia.com/download/BhavCopy/Equity/"+file+".CSV");
            byte[] data=download(uri);
            if(data==null) continue;
            return new Archive(date,uri,readQuotes(data,exchange,date,exchange.equals("NSE")));
        }
        throw new IOException("No recent official EOD archive available");
    }

    synchronized void readSessions(String exchange, LocalDate start, List<Archive> days, Instant deadline)
            throws IOException, InterruptedException {
        for (int i = 0; i < 45 && days.size() < SESSIONS; i++) {
            if (Instant.now().isAfter(deadline)) throw new IOException("Discovery time limit reached");
            LocalDate date = start.minusDays(i);
            String stamp = DateTimeFormatter.BASIC_ISO_DATE.format(date);
            boolean nse = exchange.equals("NSE");
            String file = "BhavCopy_" + exchange + "_CM_0_0_0_" + stamp + "_F_0000";
            URI uri = URI.create(nse ? "https://nsearchives.nseindia.com/content/cm/" + file + ".csv.zip"
                    : "https://www.bseindia.com/download/BhavCopy/Equity/" + file + ".CSV");
            byte[] bytes = archiveCache.get(uri);
            if (bytes == null) bytes = download(uri);
            if (bytes == null) continue;
            Map<String,Quote> quotes = readQuotes(bytes,exchange,date,nse);
            days.add(new Archive(date,uri,quotes));
            if (!date.equals(start)) {
                archiveCache.put(uri,bytes);
                if (archiveCache.size() > 80) archiveCache.pollFirstEntry();
            }
        }
    }

    private static Double turnover(List<Archive> days, String isin) {
        double total = 0;
        for (Archive day : days) {
            Quote quote = day.quotes.get(isin);
            if (quote == null) return null;
            total += quote.turnover;
        }
        return total / SESSIONS / 10_000_000;
    }

    private static double sortTurnover(Candidate candidate) {
        return candidate.turnoverCr == null ? -1 : candidate.turnoverCr;
    }

    static Map<String,Quote> readQuotes(byte[] bytes, String exchange, LocalDate date, boolean zipped) throws IOException {
        InputStream input = new ByteArrayInputStream(bytes);
        if (zipped) {
            var zip = new ZipInputStream(input);
            var entry = zip.getNextEntry();
            if (entry == null || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv"))
                throw new IOException("Exchange archive lacks its CSV");
            input = new ByteArrayInputStream(readBounded(zip));
        }
        var quotes = new HashMap<String,Quote>();
        var groups = exchange.equals("NSE") ? Set.of("EQ") : Set.of("A","B","X");
        try (var csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get()
                .parse(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            if (!csv.getHeaderMap().keySet().containsAll(List.of("TradDt","ISIN","SctySrs","FinInstrmTp",
                    "TckrSymb","ClsPric","TtlTrfVal")))
                throw new IOException("Unexpected exchange archive format");
            for (var row : csv) {
                if (!date.toString().equals(row.get("TradDt"))) throw new IOException("Wrong archive date");
                if (!groups.contains(row.get("SctySrs")) || !"STK".equals(row.get("FinInstrmTp"))) continue;
                String isin = row.get("ISIN");
                String symbol = row.get("TckrSymb");
                if (!isin.matches("INE[A-Z0-9]{9}") || symbol.isBlank()) continue;
                String price = row.get("ClsPric"), value = row.get("TtlTrfVal");
                if (price.isBlank() || value.isBlank()) continue;
                double close = Double.parseDouble(price), turnover = Double.parseDouble(value);
                if (Double.isFinite(close) && close > 0 && Double.isFinite(turnover) && turnover > 0)
                    quotes.put(isin,new Quote(exchange,symbol,isin,close,turnover,field(row,"FinInstrmNm"),
                            number(row,"OpnPric"),number(row,"HghPric"),number(row,"LwPric"),
                            number(row,"TtlTradgVol"),field(row,"FinInstrmId")));
            }
        }
        if (quotes.isEmpty()) throw new IOException("Archive has no supported equity quotes");
        return quotes;
    }

    private static String field(org.apache.commons.csv.CSVRecord row, String key) {
        return row.isSet(key) && !row.get(key).isBlank() ? row.get(key) : null;
    }

    private static Double number(org.apache.commons.csv.CSVRecord row, String key) {
        try {
            String value = field(row,key);
            if (value == null) return null;
            double n = Double.parseDouble(value);
            return Double.isFinite(n) && n >= 0 ? n : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    static URI expectedWorkbook(LocalDate today) {
        // AMFI publishes after each six-month period ends. Its public index covers publication delays.
        LocalDate periodEnd = today.getMonthValue() >= 7 ? LocalDate.of(today.getYear(),6,30)
                : LocalDate.of(today.getYear() - 1,12,31);
        String stamp = DateTimeFormatter.ofPattern("ddMMMyyyy",Locale.ENGLISH).format(periodEnd);
        return URI.create("https://portal.amfiindia.com/spages/AverageMarketCapitalization" + stamp + ".xlsx");
    }

    static URI workbookLink(byte[] page) throws IOException {
        var links = Jsoup.parse(new String(page,StandardCharsets.UTF_8), AMFI.toString()).select("a[href]");
        for (var link : links) {
            URI uri;
            try { uri = URI.create(link.absUrl("href").replace(" ","%20")); }
            catch (IllegalArgumentException e) { continue; }
            if ("https".equals(uri.getScheme()) && Set.of("www.amfiindia.com","amfiindia.com","portal.amfiindia.com").contains(
                    Objects.toString(uri.getHost(),"")) && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                return uri;
        }
        throw new IOException("AMFI page lacks a current Excel link");
    }

    static Map<String,String> classifications(byte[] bytes) throws IOException {
        var result = new HashMap<String,String>();
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var format = new DataFormatter(Locale.ROOT);
            for (Sheet sheet : book) {
                int nameColumn = -1;
                for (Row row : sheet) {
                    String isin = "";
                    boolean small = false;
                    for (Cell cell : row) {
                        String value = format.formatCellValue(cell).strip();
                        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]","");
                        if (normalized.equals("nameofcompany") || normalized.equals("nameofthecompany")
                                || normalized.equals("companyname")) nameColumn = cell.getColumnIndex();
                        if (value.matches("INE[A-Z0-9]{9}")) isin = value;
                        if (normalized.equals("smallcap")) small = true;
                    }
                    if (nameColumn >= 0 && !isin.isEmpty() && small) {
                        String name = format.formatCellValue(row.getCell(nameColumn)).strip();
                        if (!name.isEmpty()) result.put(isin,name);
                    }
                }
            }
        }
        if (result.isEmpty()) throw new IOException("AMFI workbook has no recognized small caps");
        return result;
    }

    private byte[] required(URI uri) throws IOException, InterruptedException {
        byte[] bytes = download(uri);
        if (bytes == null) throw new IOException("Source missing");
        return bytes;
    }

    private byte[] download(URI uri) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
        var response = http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) throw new IOException("Source returned HTTP " + response.statusCode());
            return readBounded(body);
        }
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Source exceeds size limit");
        return bytes;
    }
}
