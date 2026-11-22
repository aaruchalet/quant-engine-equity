package com.aaruchalet.quant.backtest;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicCompanyResearchTest {
    private String table(String... rows) {
        StringBuilder html=new StringBuilder("<table>");
        for(String row:rows) {
            html.append("<tr>");
            String[] cells=row.split("[|]");
            html.append("<th>").append(cells[0]).append("</th>");
            for(int i=1;i<cells.length;i++) html.append("<td>").append(cells[i]).append("</td>");
            html.append("</tr>");
        }
        return html.append("</table>").toString();
    }
    @Test void identifiesIssuerAndReadsTheQuarterInCroreWithoutTreatingYearToDateAsPrevious() {
        var html=table("ISIN|INE785M01021","Description of presentation currency|INR",
                "Nature of report standalone or consolidated|Consolidated","Level of rounding used in financial results|Lakhs",
                "Date of start of reporting period|01-07-2026","Date of end of reporting period|30-09-2026",
                "Date of board meeting when results were approved|21-10-2026","Revenue from operations|1,05,447.00|1,93,151.00",
                "Total profit (loss) for period|33,066.00|55,254.00","Other income|0");
        var filing=FiledResults.parse("INE785M01021","https://nsearchives.nseindia.com/filing",html);
        var facts=new HashMap<String,EquityScreen.Evidence>();
        filing.addTo(facts,null);
        assertEquals("1,054.47 cr",facts.get("revenue").value());
        assertEquals("330.66 cr",facts.get("netProfit").value());
        assertNull(facts.get("revenue").previous());
        assertEquals(LocalDate.of(2026,10,21),facts.get("netProfit").asOf().toLocalDate());
        assertThrows(IllegalArgumentException.class,()->FiledResults.parse("INE040H01021","https://example.com",html));
    }
}
