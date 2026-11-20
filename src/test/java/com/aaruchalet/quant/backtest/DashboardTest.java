package com.aaruchalet.quant.backtest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Dashboard wiring and CSV readiness; no broker credentials or network access required. */
class DashboardTest {

    @Test
    void localHistoryStatusReportsReadinessWithoutRevealingAbsolutePath() throws IOException {
        Path csv = Files.createTempFile("quant-dashboard-candles", ".csv");
        try {
            Files.writeString(csv, "timestamp,open,high,low,close,volume\n");
            BacktestController controller = new BacktestController(csv.toString(), "NIFTYBEES");
            Map<String, Object> status = controller.historyStatus();
            assertEquals("NIFTYBEES", status.get("symbol"));
            assertEquals(Boolean.TRUE, status.get("available"));
            assertEquals(csv.getFileName().toString(), status.get("fileName"));
            assertEquals(Files.size(csv), status.get("sizeBytes"));
            assertFalse(status.toString().contains(csv.getParent().toString()));

            Files.delete(csv);
            assertEquals(Boolean.FALSE, controller.historyStatus().get("available"));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    void packagedDashboardUsesExistingLocalApiWithoutBrokerCredentials() throws IOException {
        String html = resource("static/index.html");
        String js = resource("static/dashboard.js");
        String css = resource("static/dashboard.css");
        assertTrue(html.contains("Backtest setup"));
        assertTrue(html.contains("id=\"backtestForm\""));
        assertTrue(html.contains("src=\"/dashboard.js\""));
        assertTrue(html.contains("href=\"/dashboard.css\""));
        assertTrue(js.contains("/api/backtests/history-status"));
        assertTrue(js.contains("/api/backtests/ema-vwap-rsi"));
        assertTrue(js.contains("closedTradeEquityCurve"));
        assertTrue(js.contains("downloadTrades"));
        assertFalse(js.contains("ANGEL_ONE_JWT_TOKEN"));
        assertFalse(html.contains("cdn.jsdelivr.net"));
        assertTrue(css.contains("@media"));
    }

    @Test
    void numericInputsAcceptCommonCapitalAndRiskValues() throws IOException {
        String html = resource("static/index.html");
        assertHtmlNumberAccepted(html, "initialCapital", "20000");
        assertHtmlNumberAccepted(html, "initialCapital", "100000");
        assertHtmlNumberAccepted(html, "riskPercent", "1");
        assertHtmlNumberAccepted(html, "riskPercent", "9.90");
        assertHtmlNumberAccepted(html, "stopLossPercent", "0.5");
        assertHtmlNumberAccepted(html, "maxDailyLossPercent", "3");
        assertHtmlNumberAccepted(html, "targetRiskReward", "2");
        assertHtmlNumberAccepted(html, "slippageBps", "2");
    }

    /** Model the HTML number input's min + N * step rule. */
    private void assertHtmlNumberAccepted(String html, String id, String value) {
        String prefix = "<input id=\"" + id + "\"";
        int start = html.indexOf(prefix);
        assertTrue(start >= 0, "Input missing: " + id);
        String inputTag = html.substring(start, html.indexOf('>', start) + 1);
        BigDecimal candidate = new BigDecimal(value);
        BigDecimal minimum = new BigDecimal(attribute(inputTag, "min", "0"));
        BigDecimal maximum = new BigDecimal(attribute(inputTag, "max", "999999999"));
        assertTrue(candidate.compareTo(minimum) >= 0 && candidate.compareTo(maximum) <= 0,
                   id + " out of allowed range: " + value);
        String step = attribute(inputTag, "step", "1");
        if (!"any".equals(step)) {
            BigDecimal offset = candidate.subtract(minimum);
            assertEquals(0, offset.remainder(new BigDecimal(step)).compareTo(BigDecimal.ZERO),
                         id + " has invalid step for: " + value);
        }
    }

    private String attribute(String tag, String name, String fallback) {
        String prefix = name + "=\"";
        int pos = tag.indexOf(prefix);
        if (pos < 0) return fallback;
        int from = pos + prefix.length();
        return tag.substring(from, tag.indexOf('"', from));
    }

    private String resource(String path) throws IOException {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing static dashboard resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
