package com.aaruchalet.quant.backtest;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompanyResearchBrowserTest {
    @TempDir Path temp;

    @Test void discoveredShareChartPositionRefreshAndExportUseSameReport() throws Exception {
        Path chrome=Path.of("/usr/bin/google-chrome");
        assumeTrue(Files.isExecutable(chrome));
        var root=Path.of("src/main/resources/static");
        for(String file:new String[]{"company-research.js","workspace.js","workspace.css"})
            Files.copy(root.resolve(file),temp.resolve(file));
        String html=Files.readString(root.resolve("index.html")).replace("src=\"/","src=\"").replace("href=\"/workspace.css","href=\"workspace.css");
        String fixture="""
            <script>
            const share={company:'Fixture Ltd',symbol:'FIX',exchange:'NSE',isin:'INE000A01012',close:45};
            let lastReport, exported;
            URL.createObjectURL=blob=>{exported=blob.text();return 'blob:fixture'};
            HTMLAnchorElement.prototype.click=function(){};
            window.fetch=async (path,options)=>{
              let data;
              if(path.includes('/discover')) data={candidates:[share],limitations:[]};
              else if(path.includes('/research?')) {
                lastReport={...share,id:'test',inputs:JSON.parse(options.body),dataStatus:'PRELIMINARY',dataAsOf:'2026-11-20',
                  verdict:{confidence:'LOW',decision:'WAIT',reasons:['A','B','C'],instruction:'Verify evidence'},
                  tradePlan:{},findings:[],categories:[],indicators:{},exclusions:[],announcements:[],limitations:[],missingFields:[],
                  chart:{basis:'Unadjusted',levels:{},daily:[{date:'2026-11-20',close:45,volume:1200}]}};
                data=lastReport;
              } else data={};
              return {ok:true,text:async()=>JSON.stringify(data)};
            };
            window.addEventListener('load',async()=>{
              const tick=()=>new Promise(resolve=>setTimeout(resolve,25));
              try {
                document.querySelector('#screenAll').click();await tick();
                document.querySelector('.candidate-row').click();await tick();
                if(document.querySelector('.company-verdict strong').textContent!=='WAIT')throw Error('Verdict');
                document.querySelectorAll('.chart-controls button')[2].click();
                const sel=document.querySelector('[name=positionStatus]');sel.value='HELD';sel.dispatchEvent(new Event('change'));
                await tick();
                document.querySelector('[name=heldStop]').value=42;
                document.querySelector('#researchRefresh').click();await tick();
                document.querySelector('.company-section:last-child button').click();
                const copy=JSON.parse(await exported);
                if(JSON.stringify(copy)!==JSON.stringify(lastReport)||copy.inputs.heldStop!==42
                      ||copy.inputs.positionStatus!=='HELD'||copy.inputs.config.maxSessions!==45)throw Error('Export mismatch');
                document.body.dataset.researchTest='passed';
              } catch(e){document.body.dataset.researchTest=String(e);}
            });
            </script>
            """;
        Path page=temp.resolve("index.html"),output=temp.resolve("output.html");
        Files.writeString(page,html.replace("</head>",fixture+"</head>"));
        var process=new ProcessBuilder(chrome.toString(),"--headless","--no-sandbox","--disable-dev-shm-usage","--disable-gpu",
                "--user-data-dir="+temp.resolve("profile"),"--virtual-time-budget=5000","--dump-dom",page.toUri()+"#equity")
                .redirectOutput(output.toFile()).redirectError(temp.resolve("chrome.log").toFile()).start();
        try {assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}
        finally {process.destroyForcibly();}
        var dom=Files.readString(output);
        assertTrue(dom.contains("data-research-test=\"passed\""),()->dom.substring(dom.indexOf("<body"),dom.indexOf("<header>")));
    }
}
