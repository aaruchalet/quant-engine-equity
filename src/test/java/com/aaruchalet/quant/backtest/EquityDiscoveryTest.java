package com.aaruchalet.quant.backtest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EquityDiscoveryTest {
    @Test void discoversTheCurrentClassificationAndExcludesOtherCategories() throws Exception {
        assertTrue(EquityDiscovery.expectedWorkbook(LocalDate.of(2026,11,20)).toString().endsWith("30Jun2026.xlsx"));
        byte[] page = """
                <a href="https://example.org/unrelated.xlsx">Untrusted</a>
                <a href="https://portal.amfiindia.com/spages/AverageMarketCapitalization30Jun2026.xlsx">Excel</a>
                """.getBytes(StandardCharsets.UTF_8);
        assertEquals(EquityDiscovery.expectedWorkbook(LocalDate.of(2026,11,20)),EquityDiscovery.workbookLink(page));
        try (var book = new XSSFWorkbook(); var buffer = new ByteArrayOutputStream()) {
            var sheet = book.createSheet();
            sheet.createRow(2).createCell(1).setCellValue("Company name");
            var small = sheet.createRow(3);
            small.createCell(1).setCellValue("Sample Small");
            small.createCell(2).setCellValue("INE000A01012");
            small.createCell(10).setCellValue("Small Cap");
            var large = sheet.createRow(4);
            large.createCell(1).setCellValue("Sample Large");
            large.createCell(2).setCellValue("INE000A01013");
            large.createCell(10).setCellValue("Large Cap");
            book.write(buffer);
            assertEquals(Map.of("INE000A01012","Sample Small"),EquityDiscovery.classifications(buffer.toByteArray()));
        }
    }

    @Test void readsTradeValueAndExcludesRestrictedSeries() throws Exception {
        byte[] archive = """
                TradDt,ISIN,SctySrs,FinInstrmTp,TckrSymb,ClsPric,TtlTrfVal
                2026-11-19,INE000A01012,EQ,STK,EXAMPLE,56.25,100000000
                2026-11-19,INE000A01013,BE,STK,RESTRICTED,19,70000000
                """.getBytes(StandardCharsets.UTF_8);
        var quotes = EquityDiscovery.readQuotes(archive,"NSE",LocalDate.of(2026,11,19),false);
        assertEquals(1,quotes.size());
        assertEquals(56.25,quotes.get("INE000A01012").close());
        assertEquals(100000000,quotes.get("INE000A01012").turnover());
    }
}
