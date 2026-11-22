package com.aaruchalet.quant.backtest;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cache source documents separately so one unavailable publisher cannot discard other evidence. */
@Component
class ResearchDocuments {
    private final Path directory;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Object[] locks=new Object[32];
    ResearchDocuments(@Value("${trading.equity.research-directory:data/equity/research}") String directory) {
        this.directory=Path.of(directory,"sources");
        java.util.Arrays.setAll(locks,i->new Object());
    }
    String read(String url,Duration ttl) throws IOException,InterruptedException {
        var uri=URI.create(url);
        if(!"https".equals(uri.getScheme()) || !java.util.Set.of("theannexure.com","raw.githubusercontent.com",
                "nsearchives.nseindia.com","api.tejhq.dev").contains(uri.getHost())) throw new IOException("Unknown source");
        String key;
        try { key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
        synchronized(locks[Math.floorMod(key.hashCode(),locks.length)]) {
            Files.createDirectories(directory);
            var path=directory.resolve(key);
            if(Files.exists(path) && Files.getLastModifiedTime(path).toInstant().plus(ttl).isAfter(Instant.now()))
                return Files.readString(path);
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            try {
                var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
                try(var body=response.body()) {
                    if(response.statusCode()!=200) throw new IOException("Publisher returned "+response.statusCode());
                    var bytes=body.readNBytes(8_000_001);
                    if(bytes.length==0 || bytes.length>8_000_000) throw new IOException("Invalid document size");
                    var temp=Files.createTempFile(directory,key,".tmp");
                    try {
                        Files.write(temp,bytes);
                        Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temp); }
                    return new String(bytes,StandardCharsets.UTF_8);
                }
            } catch(IOException ex) {
                if(Files.exists(path)) return Files.readString(path);
                throw ex;
            }
        }
    }
}
