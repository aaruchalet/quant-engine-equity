package com.aaruchalet.quant.backtest;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** The stored envelope captures precisely the listing inputs and output behind an exported verdict. */
@RestController
@RequestMapping("/api/equity/research")
class CompanyResearchController {
    record Listing(String exchange,String isin) {
        Listing {
            exchange=exchange.strip().toUpperCase(Locale.ROOT);
            isin=isin.strip().toUpperCase(Locale.ROOT);
            if (!Set.of("NSE","BSE").contains(exchange) || !isin.matches("INE[A-Z0-9]{9}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid exchange or ISIN");
        }
    }
    record Audit(CompanyResearch.Report report,EquityScreen.Snapshot providerInputs,Instant providerRetrievedAt) {}
    record Cached(EquityResearchController.Research market,List<ExchangeNews.Item> news) {}

    private final EquityResearchController archive;
    private final EquityDiscovery discovery;
    private final CompanyEvidenceFeed feed;
    private final ExchangeNews news;
    private final ObjectMapper json;
    private final Path directory;
    private final Set<Listing> watched=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService updates=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("equity-research-updates").factory());

    CompanyResearchController(EquityResearchController archive,EquityDiscovery discovery,CompanyEvidenceFeed feed,
                              ExchangeNews news,ObjectMapper json,
                              @Value("${trading.equity.research-directory:data/equity/research}") String directory) {
        this.archive=archive;
        this.discovery=discovery;
        this.feed=feed;
        this.news=news;
        this.json=json;
        this.directory=Path.of(directory);
        updates.scheduleWithFixedDelay(this::refreshWatched,30,30,TimeUnit.MINUTES);
    }
    @PreDestroy void close() { updates.shutdownNow(); }

    private void refreshWatched() {
        if (!watched.isEmpty()) {
            try { feed.read(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            catch (IOException | RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Equity provider update unavailable"); }
        }
        for (var key:watched) {
            if (Thread.currentThread().isInterrupted()) return;
            try { marketData(key,new ArrayList<>()); }
            catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Research refresh incomplete for {} {}",key.exchange,key.isin); }
        }
    }

    @PostMapping
    CompanyResearch.Report analysis(@RequestParam String exchange,@RequestParam String isin,
                                     @RequestBody CompanyResearch.Inputs inputs) throws IOException,InterruptedException {
        var key=new Listing(exchange,isin);
        var warnings=new ArrayList<String>();
        var market=marketData(key,warnings);
        if(market.market!=null) watched.add(key);
        CompanyEvidenceFeed.Read evidence=null;
        try { evidence=feed.read(); }
        catch(IOException | RuntimeException ex) {warnings.add("Evidence feed unavailable.");}
        boolean providerFallback=evidence!=null && evidence.stale();
        if(providerFallback) warnings.add("Provider update failed. Previous evidence is stale.");
        var today=LocalDate.now(CompanyResearch.IST);
        String classification="UNVERIFIED",classificationSource=null;
        try {
            var amfi=discovery.classification(today);
            classification=amfi.smallCaps().containsKey(key.isin) ? "SMALL CAP" : "OTHER";
            classificationSource=amfi.source().toString();
        } catch(IOException | RuntimeException ex) { warnings.add("AMFI workbook unavailable for this evaluation."); }
        var now=ZonedDateTime.now(CompanyResearch.IST).toOffsetDateTime();
        var selected=listingSnapshot(key,evidence==null ? null : evidence.snapshot(),now,warnings);
        boolean fallback=warnings.contains(CACHE_WARNING);
        CompanyResearch.Report report;
        try {
            report=CompanyResearch.evaluate(key.exchange,key.isin,inputs,market.market,selected,classification,
                    classificationSource,market.news,now,fallback || providerFallback,warnings);
        } catch(ResponseStatusException | IllegalArgumentException ex) {
            if (selected==null) throw ex;
            warnings.add("Invalid company evidence in the provider feed. Archive evidence only.");
            selected=null;
            report=CompanyResearch.evaluate(key.exchange,key.isin,inputs,market.market,null,classification,
                    classificationSource,market.news,now,fallback || providerFallback,warnings);
        }
        write(directory.resolve(key.exchange+"-"+key.isin).resolve(report.id()+".json"),new Audit(report,selected,evidence==null ? null : evidence.retrievedAt()));
        return report;
    }
    private EquityScreen.Snapshot listingSnapshot(Listing key, EquityScreen.Snapshot snapshot, OffsetDateTime now,List<String> warnings) {
        try {
            if(snapshot==null) return null;
            if(snapshot.asOf()==null || snapshot.asOf().isAfter(now) || snapshot.stocks()==null)
                throw new IllegalArgumentException();
            var matches=snapshot.stocks().stream().filter(Objects::nonNull)
                    .filter(s->key.exchange.equals(s.exchange()) && key.isin.equals(s.isin())).toList();
            if(matches.size()>1) throw new IllegalArgumentException();
            if(matches.isEmpty()) return null;
            return new EquityScreen.Snapshot(snapshot.asOf(),snapshot.regime(),matches);
        } catch(RuntimeException ex) {
            warnings.add("Company feed unreadable, ambiguous or future-dated.");
            return null;
        }
    }

    private static final String CACHE_WARNING="Exchange refresh failed. Showing last retrieved archive with its original date.";
    private synchronized Cached marketData(Listing key,List<String> warnings) throws IOException,InterruptedException {
        Path path=directory.resolve(key.exchange+"-"+key.isin).resolve("market.json");
        var cached=Files.exists(path)?json.readValue(path.toFile(),Cached.class):new Cached(null,List.of());
        var now=ZonedDateTime.now(CompanyResearch.IST).toOffsetDateTime();
        EquityResearchController.Research market;
        try {
            market=archive.research(key.isin,key.exchange);
            if(cached.market!=null && market.asOf().isBefore(cached.market.asOf())) {
                market=cached.market;
                warnings.add(CACHE_WARNING);
            }
        } catch(ResponseStatusException ex) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
            market=cached.market;
            warnings.add(market==null ? "No recent official archive available for this listing." : CACHE_WARNING);
        }
        var items=new LinkedHashMap<String,ExchangeNews.Item>();
        for(var old:cached.news)
            if(!old.publishedAt().isBefore(now.minusDays(90))) items.put(old.url()+old.headline(),old);
        if(market!=null) {
            try {
                for(var item:news.company(key.exchange,market.company(),market.securityCode(),now))
                    items.put(item.url()+item.headline(),item);
            } catch(IOException | RuntimeException e) { warnings.add("Official announcement feed unavailable. Previously retrieved items retain their dates."); }
        }
        var result=new Cached(market,items.values().stream().sorted(Comparator.comparing(ExchangeNews.Item::publishedAt).reversed()).toList());
        if(market!=null) write(path,result);
        return result;
    }

    private void write(Path target,Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".research-",".tmp");
        try {
            json.writeValue(temp.toFile(),value);
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
