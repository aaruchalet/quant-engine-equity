package com.aaruchalet.quant.backtest;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceBrowserTest {
    @Autowired Environment environment;
    @TempDir Path temporary;

    @Test void navigatesPackagedWorkspaceInChrome() throws Exception {
        Path chrome = Path.of("/usr/bin/google-chrome");
        assumeTrue(Files.isExecutable(chrome),"Chrome browser smoke runs on the hosted Ubuntu runner");
        String url = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var titles = Map.of("nifty","NIFTY option trading","sensex","SENSEX option trading",
                "equity","Equity research");
        for (var tab : titles.entrySet()) {
            Path dom = temporary.resolve(tab.getKey()+".html");
            var process = new ProcessBuilder(chrome.toString(),"--headless","--no-sandbox","--disable-dev-shm-usage",
                    "--disable-gpu","--no-first-run","--user-data-dir="+temporary.resolve(tab.getKey()),
                    "--virtual-time-budget=3000","--dump-dom",url+"/#"+tab.getKey())
                    .redirectError(temporary.resolve(tab.getKey()+".log").toFile()).redirectOutput(dom.toFile()).start();
            try {
                assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Chrome timed out");
                assertEquals(0,process.exitValue());
            } finally { process.destroyForcibly(); }
            String html = Files.readString(dom);
            assertTrue(html.contains("href=\"#"+tab.getKey()+"\" aria-current=\"page\""),tab.getKey());
            assertTrue(Pattern.compile("<section id=\""+tab.getKey()+"\">.*?<h1>"+tab.getValue()+"</h1>",Pattern.DOTALL)
                    .matcher(html).find(),tab.getKey());
            assertFalse(html.contains("href=\"#trading\""),"Standalone Trading tab must not exist");
            if (tab.getKey().equals("equity")) {
                assertTrue(html.contains("id=\"equityQuery\""),"Equity symbol search is present");
                assertTrue(html.contains("id=\"equityBacktest\""),"Equity-only backtester exists");
            } else {
                assertTrue(html.contains("INDEX OPTIONS"),"Trading-only index page");
                assertFalse(html.contains("Run backtest"),"Index pages cannot launch historical replay");
            }
        }
    }
}
