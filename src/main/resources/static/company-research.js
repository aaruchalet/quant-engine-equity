'use strict';

function renderCompanyResearch(out,r,candidate,ui) {
  const {node,paragraph,metricTiles,dataTable,rupees,numeric,badge}=ui;
  const market=r.market, p=r.tradePlan, config=r.inputs.config;
  const evidence=new Map(r.findings.map(f=>[f.key,f]));
  const val=k=>evidence.get(k)?.evidence.value||'N/A';
  const money=v=>v==null?'N/A':rupees(v);
  const number=v=>v==null?'N/A':numeric(v);
  const title=r.company||candidate.company||candidate.symbol;
  const section=(name,...children)=>{
    const box=node('section',null,'company-section');
    box.append(node('h3',name),...children);return box;
  };
  const list=values=>{
    const ul=node('ul');values.forEach(v=>ul.append(node('li',v)));return ul;
  };
  const source=(label,url)=>{
    try {
      if(new URL(url).protocol==='https:') {
        const a=node('a',label);a.href=url;a.target='_blank';a.rel='noopener noreferrer';return a;
      }
    } catch {}
    return node('span',label);
  };
  function evidenceTable(title,labels) {
    const rows=Object.entries(labels).map(([key,label])=>{
      const f=evidence.get(key),e=f?.evidence;
      return [label,e?.value||'N/A',e?.previous||'N/A',e?.trend||'N/A',e?.benchmark||'N/A',
        e?.period||e?.asOf||'N/A',e?(f.current?'':'Stale · ')+e.rationale:'Evidence unavailable',e?'Filing':'—'];
    });
    const table=dataTable(title,['Metric','Latest','Previous','YoY / 3Y','Peer / index','Period','Interpretation','Source'],rows,'');
    table.querySelectorAll('tbody tr').forEach((tr,i)=>{
      const e=evidence.get(Object.keys(labels)[i])?.evidence;
      if(e)tr.lastElementChild.replaceChildren(source('View source',e.source));
    });
    return table;
  }
  const top=node('div',null,'company-verdict');
  top.dataset.decision=r.verdict.decision;
  top.append(node('h2',title+' · '+(r.symbol||candidate.symbol)),node('strong',r.verdict.decision),
    paragraph(r.verdict.confidence+' confidence · Position '+r.inputs.positionStatus+' · Data as of '+(r.dataAsOf||'unavailable')),
    badge(r.dataStatus));
  const reasons=section('Reasons',list(r.verdict.reasons));
  const snapshot=section('Company snapshot',metricTiles([
    ['Listing',r.exchange+' · '+r.isin],['AMFI classification',r.classification],['Sector',val('sector')],
    ['Market capitalisation',val('marketCap')],['Free float',val('freeFloat')],['Official close',money(market?.latestClose)],
    ['Previous close',money(market?.previousClose)],['Day change',market?.priceChangePercent==null?'N/A':number(market.priceChangePercent)+'%'],
    ['52-week low / high',money(r.chart.levels['52-week low'])+' / '+money(r.chart.levels['52-week high'])],
    ['Market regime',r.regime]]));
  if(r.classificationSource)snapshot.append(source('AMFI classification workbook',r.classificationSource));
  const technical=section('Technical study',companyPriceChart(r,ui));
  const indicators=r.indicators;
  const adjusted=Object.keys(indicators).length>0;
  const ema9=adjusted?indicators.ema9:market?.ema9Indicative;
  const ema20=adjusted?indicators.ema20:market?.ema20Indicative;
  technical.append(paragraph(ema9==null||ema20==null?'Insufficient history for the EMA trend.':
    'EMA 9 is '+(ema9>ema20?'above':ema9<ema20?'below':'equal to')+' EMA 20 on '+(adjusted?'adjusted':'indicative unadjusted')+' prices.'),
    metricTiles([['EMA 9',money(ema9)],['EMA 20',money(ema20)],['EMA 50',money(indicators.ema50)],['SMA 200',money(indicators.sma200)],
      ['RSI (14)',number(adjusted?indicators.rsi14:market?.rsi14Indicative)],['MACD / signal',number(indicators.macd)+' / '+number(indicators.macdSignal)],
      ['ADX (14)',number(indicators.adx14)],['ATR (14)',money(indicators.atr14)],['Relative volume (20)',number(indicators.relativeVolume)],
      ['Avg traded value (20), ₹ cr',number(market?.averageTradedValueCr)],['Avg traded value (60), ₹ cr',val('tradedValue60Cr')],
      ['Relative strength vs Smallcap 250',val('outperformsSmallcap')],...Object.entries(r.chart.levels).map(([k,v])=>[k,number(v)])]));
  const financial=section('Financial health and valuation',evidenceTable('Results and peer comparisons',{
    revenueGrowth:'Revenue growth',recurringProfit:'Recurring PAT',quarterProfitGrowth:'Quarterly profit growth',ebitda:'EBITDA',
    eps:'EPS',margins:'Operating / net margins',roe:'ROE',roce:'ROCE',debtEquity:'Debt / equity',interestCoverage:'Interest coverage',
    cashFlowOrCapital:'Operating cash flow / regulatory capital',freeCashFlow:'Free cash flow',receivables:'Receivables',dilution:'Dilution',
    peerPE:'P/E',peerPB:'P/B',evEbitda:'EV/EBITDA',priceSales:'Price / sales'}));
  const ownership=section('Shareholding, governance and restrictions',evidenceTable('Ownership and trading checks',{
    promoterHolding:'Promoter holding',zeroPledge:'Promoter pledge',fii:'FII / FPI',dii:'DII',cleanAudit:'Auditor opinion',
    relatedParty:'Related-party disclosures',surveillance:'ASM / GSM / trade-to-trade / suspension',circuits:'Circuit limits',depth:'Depth / spread'}));
  const news=section('News and upcoming catalysts',evidenceTable('Filed developments',{
    scheduledEvent:'Scheduled event',recentDevelopment:'Recent development',quantifiedDevelopment:'Financial impact',materialAdverseEvent:'Material adverse event'}));
  if(!r.announcements.length)news.append(paragraph('No matching dated announcements retrieved. Coverage may be incomplete.'));
  for(const item of r.announcements) {
    const row=node('p');row.append(source(item.headline,item.url),node('small',item.publishedAt+' · '+item.assessment+' · '+item.rationale));news.append(row);
  }
  const score=section('Risk and opportunity score',dataTable(r.score==null?'Overall score unavailable':r.score+' / 100 · '+
      (r.score>=85?'High-conviction candidate':r.score>=75?'Candidate':r.score>=60?'Watchlist':'Avoid'),
    ['Category','Points','Available','Tests'],r.categories.map(c=>[c.name,c.points??'N/A',c.maximum,c.tests.join(', ')]),''),
    paragraph(r.exclusions.length?'Hard exclusions':'No confirmed hard exclusion reported. Unverified checks appear below.'));
  if(r.exclusions.length)score.append(list(r.exclusions));
  const pct=v=>v==null||!p.entry?'N/A':number(100*v/p.entry)+'%';
  const plan=section('Action plan',paragraph(r.verdict.instruction),metricTiles([
    ['Holding window',config.minSessions+'–'+config.maxSessions+' trading sessions'],[r.inputs.positionStatus==='HELD'?'Current plan reference':'Entry zone',r.inputs.positionStatus==='HELD'?money(p.entry):money(p.entryLow)+' – '+money(p.entry)],
    ['Stop',money(p.stop)],['Target 1 / Target 2',money(p.target1)+' / '+money(p.target2)],
    ['Gross upside / share',money(p.grossGain)+' · '+pct(p.grossGain)],['Net upside / share',money(p.netGain)+' · '+pct(p.netGain)],
    ['Gross downside / share',money(p.grossLoss)+' · '+pct(p.grossLoss)],['Net downside / share',money(p.netLoss)+' · '+pct(p.netLoss)],
    ['Net reward:risk',p.netRewardRisk==null?'N/A':number(p.netRewardRisk)+':1'],['Shares',number(p.units)],['Next review (IST)',r.nextReview]
  ]),paragraph([p.trigger,p.invalidation,p.timeExit].filter(Boolean).join(' · ')),
    paragraph('Price gaps, circuits and illiquidity can prevent a stop from filling at the displayed price.'));
  const sources=section('Sources and limitations',paragraph('Evaluated '+r.evaluatedAt+' · '+r.chart.basis),
    list([...r.limitations,...r.missingFields]));
  if(market) sources.append(paragraph('Exchange archives retrieved '+market.retrievedAt+' · '+market.observedSessions+' sessions.'));
  const details=node('details');details.append(node('summary','Dated sources and observed prices'));
  const citations=node('ul');
  for(const f of r.findings) {
    const li=node('li');
    li.append(source(f.key+' · '+f.evidence.asOf,f.evidence.source),node('span',' · '+(f.current?'Current':'Stale')+' · '+f.evidence.rationale));citations.append(li);
  }
  for(const d of [...(market?.dailyPrices||[])].reverse()) {
    const li=node('li');li.append(source(d.session+' · '+money(d.close),d.source));citations.append(li);
  }
  details.append(citations);sources.append(details);
  const final=section('Final decision · '+r.verdict.decision,paragraph(r.verdict.reasons.join(' ')),paragraph(r.verdict.instruction),
    paragraph('Entry / Stop / Target 1 / Target 2 · '+[p.entry,p.stop,p.target1,p.target2].map(money).join(' / ')),
    paragraph('Data confidence '+r.verdict.confidence+' · As of '+(r.dataAsOf||'unavailable')));
  const download=node('button','Export research JSON','secondary');download.type='button';
  download.onclick=()=>{
    const url=URL.createObjectURL(new Blob([JSON.stringify(r,null,2)],{type:'application/json'}));
    const a=node('a');a.href=url;a.download=r.exchange+'-'+r.isin+'-'+r.id+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  };
  final.append(download);
  out.replaceChildren(top,reasons,snapshot,technical,financial,ownership,news,score,plan,sources,final);
}

function companyPriceChart(report,{node,paragraph,numeric}) {
  const series=report.chart.daily,box=node('div',null,'price-study');
  if(!series.length) {box.append(paragraph('Price chart unavailable.')); return box;}
  const controls=node('div',null,'chart-controls'),canvas=node('canvas'),detail=node('output');
  const slider=node('input');slider.type='range';slider.min='0';slider.step='1';slider.setAttribute('aria-label','Inspect price by session');
  canvas.width=960;canvas.height=300;canvas.setAttribute('aria-label','Closing prices, moving averages, volume and identified trade levels');
  const legend=node('p',null,'chart-legend'),colors={close:'#165969',ema9:'#a34c10',ema20:'#74529b',ema50:'#24805c',sma200:'#5c6276'};
  let visible=[],active;
  function inspect(index) {
    const d=visible[index];slider.value=index;
    detail.textContent=d.date+' · O '+numeric(d.open)+' · H '+numeric(d.high)+' · L '+numeric(d.low)+' · C ₹'+numeric(d.close)+' · Volume '+numeric(d.volume);
  }
  function draw(months,button) {
    active?.setAttribute('aria-pressed','false');active=button;button.setAttribute('aria-pressed','true');
    const start=new Date(series.at(-1).date+'T00:00:00Z');start.setUTCMonth(start.getUTCMonth()-months);
    visible=series.filter(d=>d.date>start.toISOString().slice(0,10));slider.max=visible.length-1;
    const ctx=canvas.getContext('2d');if(!ctx)return;
    ctx.clearRect(0,0,960,300);
    const named=Object.keys(colors).filter(k=>visible.some(d=>Number.isFinite(d[k])));
    const levels={'Support':report.chart.levels['Prior 20-session support'],'Resistance':report.chart.levels['Prior 20-session resistance'],
      'Entry':report.tradePlan.entry,'Stop':report.tradePlan.stop,'T1':report.tradePlan.target1,'T2':report.tradePlan.target2};
    const marks=Object.entries(levels).filter(([,v])=>Number.isFinite(v));
    const values=[...visible.flatMap(d=>named.map(k=>d[k])).filter(Number.isFinite),...marks.map(([,v])=>v)];
    const low=Math.min(...values),high=Math.max(...values),span=high-low||Math.max(1,high*.01);
    const x=i=>60+805*i/Math.max(1,visible.length-1),y=v=>200-(v-low)/span*170;
    ctx.font='16px sans-serif';ctx.fillStyle='#385458';ctx.fillText(numeric(high),1,30);ctx.fillText(numeric(low),1,200);
    for(const [label,v] of marks) {
      ctx.setLineDash([4,5]);ctx.strokeStyle='#97aaa5';ctx.beginPath();ctx.moveTo(60,y(v));ctx.lineTo(875,y(v));ctx.stroke();
      ctx.fillText(label,878,y(v));ctx.setLineDash([]);
    }
    for(const k of named) {
      ctx.strokeStyle=colors[k];ctx.lineWidth=k==='close'?3:1.7;ctx.beginPath();let started=false;
      visible.forEach((d,i)=>{if(d[k]!=null){if(started)ctx.lineTo(x(i),y(d[k]));else ctx.moveTo(x(i),y(d[k]));started=true;}});
      ctx.stroke();
    }
    const maxVol=Math.max(1,...visible.map(d=>d.volume||0));ctx.fillStyle='#96b5ad';
    visible.forEach((d,i)=>{if(d.volume!=null)ctx.fillRect(x(i),275-50*d.volume/maxVol,Math.max(1,600/visible.length),50*d.volume/maxVol);});
    legend.replaceChildren(...named.map(k=>{const label=node('span',k.toUpperCase());label.style.color=colors[k];return label;}));
    inspect(visible.length-1);
  }
  for(const [months,label] of [[1,'1M'],[3,'3M'],[6,'6M'],[12,'1Y']]) {
    const b=node('button',label,'secondary');b.type='button';
    b.onclick=()=>draw(months,b);controls.append(b);if(months===1)draw(months,b);
  }
  slider.oninput=()=>inspect(Number(slider.value));
  canvas.onpointermove=e=>{
    const rect=canvas.getBoundingClientRect(),relative=((e.clientX-rect.left)*960/rect.width-60)/805;
    inspect(Math.min(visible.length-1,Math.max(0,Math.round(relative*(visible.length-1)))));
  };
  box.append(controls,legend,canvas,slider,detail,paragraph(report.chart.basis+' · '+series.length+' available sessions'));
  return box;
}
