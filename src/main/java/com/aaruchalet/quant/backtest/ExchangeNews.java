package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** Shared official RSS cache. Only exact issuer names or exchange security codes can match. */
@Component
class ExchangeNews {
    record Item(String headline,String url,OffsetDateTime publishedAt,String assessment,String rationale,
                String source,OffsetDateTime retrievedAt) {}
    record Entry(String issuer, String summary, String link, OffsetDateTime date) {}
    private record Feed(Instant fetched,List<Entry> entries) {}
    private final Map<String,Feed> feeds=new HashMap<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    synchronized List<Item> company(String exchange,String company,String securityCode,OffsetDateTime now)
            throws IOException,InterruptedException {
        String url=exchange.equals("NSE") ? "https://nsearchives.nseindia.com/content/RSS/Online_announcements.xml"
                : "https://www.bseindia.com/data/xml/announcements.xml";
        var feed=feeds.get(exchange);
        if (feed==null || feed.fetched.isBefore(now.minusMinutes(15).toInstant())) {
            var req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
            var res=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try (var body=res.body()) {
                if (res.statusCode()!=200) throw new IOException("Announcement feed unavailable");
                byte[] bytes=body.readNBytes(4_000_001);
                if(bytes.length>4_000_000) throw new IOException("Announcement feed exceeded limit");
                var xml=Jsoup.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),url,Parser.xmlParser());
                if (xml.selectFirst("channel")==null) throw new IOException("Invalid announcement feed");
                var entries=new ArrayList<Entry>();
                for (var item:xml.select("item")) {
                    var date=parseDate(item.select("pubDate").text());
                    var link=item.select("link").text();
                    if (date!=null && official(link,exchange))
                        entries.add(new Entry(item.select("title").text(),Jsoup.parse(item.select("description").text()).text(),link,date));
                }
                feed=new Feed(now.toInstant(),List.copyOf(entries));
                feeds.put(exchange,feed);
            }
        }
        var found=new ArrayList<Item>();
        for (var e:feed.entries) {
            boolean match=company!=null && e.issuer.strip().equalsIgnoreCase(company.strip())
                    || exchange.equals("BSE") && securityCode!=null && e.issuer.endsWith("("+securityCode+")");
            if (match && !e.date.isAfter(now) && !e.date.isBefore(now.minusDays(90)))
                found.add(new Item(e.summary.isBlank()?e.issuer:e.summary,e.link,e.date,"NEUTRAL",
                        "Exchange publication verified. Financial impact has not been assessed.",url,feed.fetched.atZone(CompanyResearch.IST).toOffsetDateTime()));
        }
        return found;
    }
    static OffsetDateTime parseDate(String value) {
        try {return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();}
        catch (DateTimeException ignored) {}
        try {return OffsetDateTime.parse(value);}
        catch (DateTimeException ignored) {}
        for(String pattern:List.of("dd-MMM-yyyy HH:mm:ss","dd MMM yyyy HH:mm:ss","dd-MM-yyyy HH:mm:ss",
                "EEE, dd MMM yyyy HH:mm:ss","dd MMM yyyy HH:mm:ss Z","EEE, dd MMM yyyy HH:mm:ss z")) {
            try {
                var format=DateTimeFormatter.ofPattern(pattern,Locale.ENGLISH).withZone(CompanyResearch.IST);
                return ZonedDateTime.parse(value,format).toOffsetDateTime();
            } catch (DateTimeException ignored) {}
        }
        return null;
    }
    private static boolean official(String url,String exchange) {
        try {
            var uri=URI.create(url); String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
            String domain=exchange.equals("NSE")?"nseindia.com":"bseindia.com";
            return "https".equals(uri.getScheme()) && (host.equals(domain)||host.endsWith("."+domain));
        } catch(IllegalArgumentException e) {return false;}
    }
}
