'use strict';
(() => {
  const byId = id => document.getElementById(id);
  const markets = ['NIFTY', 'SENSEX'];
  const tabs = [...document.querySelectorAll('#navigation a')];
  const rupees = value => value == null ? '—' : new Intl.NumberFormat('en-IN',
    {style:'currency',currency:'INR',maximumFractionDigits:2}).format(Number(value));
  const numeric = value => value == null ? '—' : new Intl.NumberFormat('en-IN',
    {maximumFractionDigits:3}).format(Number(value));
  const asText = value => value == null || value === '' ? '—' : String(value);
  const node = (tag,content=null,className='') => {
    const element=document.createElement(tag);
    if (content!=null) element.textContent=content;
    if (className) element.className=className;
    return element;
  };
  const paragraph = (message,kind='muted') => node('p',message,kind);
  function card(title,content) {
    const article=node('article',null,'panel');
    article.append(node('h2',title),content);
    return article;
  }
  function metricTiles(pairs) {
    const root=node('div',null,'metrics');
    for (const [label,value] of pairs) {
      const tile=node('div',label,'metric'); tile.append(node('b',asText(value))); root.append(tile);
    }
    return root;
  }
  function dataTable(title,headings,rows,emptyMessage) {
    const root=node('div',null,'table-wrap');
    root.append(node('h3',title));
    if (!rows.length) {root.append(paragraph(emptyMessage));return root;}
    const table=node('table'), head=table.createTHead().insertRow(),body=table.createTBody();
    headings.forEach(h=>head.append(node('th',h)));
    rows.forEach(row=>{
      const tr=body.insertRow();
      row.forEach(c=>tr.append(node('td',asText(c))));
    });
    root.append(table);return root;
  }
  function badge(text) {
    const v=node('span',asText(text),'state-badge');
    if (/LIVE|FAILED|BLOCKED|UNAVAILABLE|HALTED/.test(String(text))) v.classList.add('warn');
    if (/PAPER|CONFIRMED|PASSED|ACTIVE/.test(String(text))) v.classList.add('okay');
    return v;
  }
  async function fetchJSON(path,options) {
    const response=await fetch(path,options||{cache:'no-store',headers:{'Cache-Control':'no-cache'}});
    const raw=await response.text();
    let data={};
    try {data=raw ? JSON.parse(raw) : {};} catch {throw Error('Invalid JSON from service');}
    if (!response.ok) throw Error(data.detail || data.error || data.message ||
      'Data source unavailable (HTTP '+response.status+')');
    return data;
  }
  const strategyKeys = [
    ['Strategy','identifier'],['Timeframe','timeframe'],['EMA fast','emaFast'],['EMA slow','emaSlow'],
    ['RSI period','rsiPeriod'],['Call RSI >','longRsiAbove'],['Put RSI <','shortRsiBelow'],
    ['Premium stop %','stopPercent'],['Premium target %','targetPercent'],
    ['First entry','firstEntry'],['Last entry','lastEntry'],['Square-off','squareOff'],
    ['Max trades/day','maximumTradesPerDay'],['Daily loss cutoff','dailyLossCutoff'],
    ['Daily profit cutoff','dailyProfitCutoff']
  ];
  function renderIndex(market,result) {
    const marketId=market.toLowerCase(), section=byId(marketId),view=byId('indexResult-'+marketId);
    const row=node('div',null,'mode-row');
    row.append(node('strong','Configured execution mode: '),badge(result.enabled?result.mode:'INACTIVE'));
    row.append(node('span','Market data: '+asText(result.signalInstrument)+' · VWAP: '+asText(result.vwapInstrument)));
    row.append(node('span','Updated: '+asText(result.updated)));
    const summary=metricTiles([
      ['Session status',result.status],['Today\'s net P&L',rupees(result.dailyPnl)],
      ['Shared daily budget',rupees(result.dailyBudget)],
      ['Budget remaining',rupees(result.remainingBudget)],['Position units',result.quantity ?? 0],
      ['Executed entries',result.entries ?? 0],['Lot size',result.lotSize],
      ['Maximum lots',result.maximumLots],['Trading mode',result.enabled?result.mode:'INACTIVE']
    ]);
    const position=result.position;
    const positionPanel=dataTable('Current '+market+' position',
      ['Contract','Side','Expiry','Strike','Quantity','Average premium','Stop','Target','Order pending'],
      position?[[
        position.instrument?.symbol,position.right,position.expiry,numeric(position.strike),
        result.quantity,rupees(result.averagePremium),rupees(result.stop),
        rupees(result.target),result.pending||'NONE'
      ]]:[], 'No open position for '+market+'. Shared risk budget also applies to the other index.');
    const checkMap=result.riskChecks||{};
    const risk=dataTable('Pre-entry and risk gates',
      ['Check','Current state','Explanation'],
      Object.entries(checkMap).map(([k,v])=>[k,v?'PASS':'BLOCK',
        k==='sharedPositionAvailable'?'Only one NIFTY/SENSEX option position at a time':
        k==='entriesNotPaused'?'Pause file, rejection and halt controls':'Startup YAML and current session state']),
      'Risk checks are inactive until PAPER or LIVE is configured.');
    const settings=dataTable('YAML strategy settings',
      ['Parameter','Configured value'],strategyKeys.map(([label,key])=>[
        label, result.strategy?.[key]
      ]),'Strategy settings unavailable.');
    const traces=result.evaluations||[];
    const decisions=dataTable('Live strategy evaluation log · latest first',
      ['Recorded','Candle (IST)','Stage','EMA9','EMA20','RSI14','VWAP','Cross','Candidate','EMA','RSI','VWAP last','Decision / reason'],
      traces.map(e=>[
        e.recorded,e.candleOpen,e.stage,numeric(e.ema9),numeric(e.ema20),numeric(e.rsi14),
        numeric(e.sessionVwap),e.confirmedCrossover,e.candidate,
        e.emaGate===undefined?'—':e.emaGate?'PASS':'WAIT',
        e.rsiGate===undefined?'—':e.rsiGate?'PASS':'WAIT',
        e.vwapFinalGate===undefined?'—':e.vwapFinalGate?'PASS':'WAIT',
        e.detail
      ]),'No signal evaluations yet. They are recorded during market hours when the runner successfully receives completed candles.');
    const statusText=result.enabled ? 'PAPER uses simulated orders; LIVE uses the broker path. Both use the same EMA → RSI → VWAP sequence and risk checks.'
      : 'Trading is inactive. In the application YAML, select PAPER or LIVE, restart the server, and complete broker preflight. A web click never enables LIVE.';
    view.replaceChildren(row,paragraph(statusText),summary,positionPanel,risk,settings,decisions);
  }
  const loading={};
  async function refreshIndex(market) {
    if(loading[market]) return;
    loading[market]=true;
    const out=byId('indexResult-'+market.toLowerCase());
    const button=byId('refresh-'+market.toLowerCase());
    button.disabled=true;
    try {
      const data=await fetchJSON('/api/trading/indices/'+market);
      renderIndex(market,data);
    } catch(error) {
      out.replaceChildren(paragraph('Unable to load '+market+' trading runtime: '+error.message,'error'));
    } finally {loading[market]=false;button.disabled=false;}
  }
  for(const market of markets) {
    const id=market.toLowerCase(),section=byId(id);
    section.append(node('small','INDEX OPTIONS · NO HISTORICAL REPLAY'));
    const header=node('div',null,'heading'), title=node('div');
    title.append(node('h1',market+' option trading'),
      paragraph('Same strategy and indicator checks in PAPER and LIVE. Only the execution venue differs.'));
    const button=node('button','Refresh '+market+' status');button.id='refresh-'+id;
    button.type='button';button.onclick=()=>refreshIndex(market);
    header.append(title,button);
    const out=node('div',null,'index-view');out.id='indexResult-'+id;
    out.append(paragraph('Reading configured strategy and per-index runtime status…'));
    section.append(header,out);
  }

  const sources=[
    ['NSE India','https://www.nseindia.com/','Official quotes, volume, exchange notices and surveillance'],
    ['BSE India','https://www.bseindia.com/','Corporate results, filings and actions'],
    ['Screener.in','https://www.screener.in/','Financial statements, ROE, ROCE and debt'],
    ['Tickertape','https://www.tickertape.in/screener/equity','Valuations and peer comparison'],
    ['Trendlyne','https://trendlyne.com/','Ownership, pledges and valuation'],
    ['TradingView','https://in.tradingview.com/','EMA, RSI, volume and chart structure'],
    ['Chartink','https://chartink.com/','Technical and breakout scans'],
    ['Moneycontrol','https://www.moneycontrol.com/','Announcements, news and market context'],
    ['SEBI','https://www.sebi.gov.in/','Regulatory and investor protection notices']
  ];
  const listing=node('div',null,'source-links');
  for (const [name,url,purpose] of sources) {
    const a=node('a',name);a.href=url;a.target='_blank';a.rel='noopener noreferrer';
    a.title=purpose;listing.append(a);
  }
  byId('researchSources').append(node('h3','Research sources (external verification)'),listing,
    paragraph('The automated feed currently requests official NSE data only. These other websites are reference links, not automatically scraped or treated as verified.'));

  const quoteState={symbol:null};
  function externalSymbolLinks(symbol) {
    const strip=encodeURIComponent(symbol),panel=node('div',null,'source-links');
    const links=[
      ['NSE quote','https://www.nseindia.com/get-quotes/equity?symbol='+strip],
      ['Screener','https://www.screener.in/company/'+strip+'/'],
      ['TradingView','https://in.tradingview.com/symbols/NSE-'+strip+'/']
    ];
    for(const [label,url] of links) {
      const a=node('a',label);a.href=url;a.target='_blank';a.rel='noopener noreferrer';panel.append(a);
    }
    return panel;
  }
  function renderQuote(quote) {
    const root=byId('equityDetail');
    const title=node('h3',quote.company+' · '+quote.symbol);
    root.replaceChildren(title,metricTiles([
      ['Official NSE quote',rupees(quote.price)],['Change %',quote.changePercent == null?'—':numeric(quote.changePercent)+'%'],
      ['Previous close',rupees(quote.previousClose)],['Session high',rupees(quote.dayHigh)],
      ['Session low',rupees(quote.dayLow)],['52-week high',rupees(quote.week52High)],
      ['52-week low',rupees(quote.week52Low)],['Decision','INSUFFICIENT DATA']
    ]),paragraph('NSE source fetched '+asText(quote.fetchedAt)+' · Exchange timestamp '+asText(quote.marketTimestamp)+
      ' · '+asText(quote.exchange)+' / '+asText(quote.isin)),
      paragraph('AMFI classification, financial statements, liquidity/depth, surveillance clearance and tradeable setup have not been verified. No BUY recommendation can be issued.'),
      externalSymbolLinks(quote.symbol));
  }
  async function showEquity(symbol) {
    if(!/^[A-Z0-9&_.-]{1,25}$/.test(symbol)) return;
    quoteState.symbol=symbol;byId('equityQuery').value=symbol;
    byId('equityDetail').replaceChildren(paragraph('Requesting fresh official NSE data for '+symbol+'…'));
    try {renderQuote(await fetchJSON('/api/equity/market/quote/'+encodeURIComponent(symbol)+'?ts='+Date.now()));}
    catch(e) {byId('equityDetail').replaceChildren(paragraph('Quote unavailable: '+e.message,'error'));}
  }
  byId('equityLookup').onsubmit=async event=>{
    event.preventDefault();
    const q=byId('equityQuery').value.trim(),out=byId('equityMatches');
    out.replaceChildren(paragraph('Searching NSE…'));
    try {
      const result=await fetchJSON('/api/equity/market/search?q='+encodeURIComponent(q)+'&ts='+Date.now());
      const suggestions=result.suggestions||[];
      out.replaceChildren();
      if(!suggestions.length) {
        out.append(paragraph('No matching NSE equity symbols returned. You may search by an exact ticker.'));
      } else {
        const list=node('div',null,'search-results');
        for(const s of suggestions){
          const button=node('button',s.symbol+' · '+asText(s.company),'secondary');
          button.type='button';button.onclick=()=>showEquity(s.symbol);
          list.append(button);
        }
        out.append(list);
      }
      const direct=node('button','Check exact ticker '+q.toUpperCase(),'secondary');
      direct.type='button';direct.onclick=()=>showEquity(q.toUpperCase());out.append(direct);
    } catch(e) {out.replaceChildren(paragraph('NSE search unavailable: '+e.message,'error'));}
  };
  byId('equityRefresh').onclick=()=>{
    const symbol=quoteState.symbol || byId('equityQuery').value.trim().toUpperCase();
    if(!symbol) byId('equityDetail').replaceChildren(paragraph('Search for or enter an NSE symbol first.','error'));
    else showEquity(symbol);
  };
  const screenForm=byId('screen');
  async function screenAll() {
    if(!screenForm.reportValidity()) return;
    const f=new FormData(screenForm),min=Number(f.get('minPrice')),max=Number(f.get('maxPrice'));
    const low=Number(f.get('minSessions')),high=Number(f.get('maxSessions'));
    const out=byId('screenResult');
    if(min<=0 || min>=max || low<1 || low>high){
      out.replaceChildren(paragraph('Invalid price band or holding-session range.','error'));return;
    }
    out.replaceChildren(paragraph('Fetching a fresh NSE Smallcap 250 constituent snapshot…'));
    const button=byId('screenAll');
    button.disabled=true;
    try {
      const url='/api/equity/market/screen?minPrice='+encodeURIComponent(min)+
        '&maxPrice='+encodeURIComponent(max)+'&ts='+Date.now();
      const r=await fetchJSON(url);
      const summary=metricTiles([
        ['Constituents with prices',r.constituentsWithPrices],['Matching price band',r.matched],
        ['Research state','PRELIMINARY'],['AMFI coverage','INCOMPLETE']
      ]);
      const warning=paragraph('As-of NSE '+asText(r.marketTimestamp)+' · fetched '+asText(r.fetchedAt)+
        ' · Holding period '+low+'–'+high+' exchange sessions. This is a price-filtered NSE index scan, not a complete AMFI small-cap screen. Liquidity, governance, financials and 2:1 net R:R still require verification.','notice');
      const data=r.stocks||[];
      const results=dataTable('Matching constituents',
        ['Symbol','Latest price','Change','Decision'],
        data.map(s=>[s.symbol,rupees(s.price),
          s.changePercent==null?'—':numeric(s.changePercent)+'%',s.decision]),
        'No index members match the selected price range.');
      const details=node('div',null,'search-results');
      data.forEach(s=>{
        const b=node('button','Inspect '+s.symbol,'secondary');b.type='button';
        b.onclick=()=>{showEquity(s.symbol);byId('equityQuery').scrollIntoView({behavior:'smooth'});};
        details.append(b);
      });
      out.replaceChildren(summary,warning,results,details);
    } catch(e) {out.replaceChildren(paragraph('Fresh web scan unavailable: '+e.message+' No cached recommendations will be used.','error'));}
    finally {button.disabled=false;}
  }
  screenForm.onsubmit=e=>{e.preventDefault();screenAll();};
  byId('screenAll').onclick=screenAll;

  const backtest=byId('equityBacktest');
  backtest.onsubmit=async event=>{
    event.preventDefault();if(!backtest.reportValidity()) return;
    const file=backtest.elements.file.files[0],out=byId('equityBacktestResults');
    if(!file || file.size>5_000_000) {out.replaceChildren(paragraph('Supply a CSV under 5MB.','error'));return;}
    const body=new FormData(backtest);
    out.replaceChildren(paragraph('Running local equity simulation; no orders or broker access…'));
    const button=backtest.querySelector('button');button.disabled=true;
    try {
      const res=await fetch('/api/equity/backtest',{method:'POST',body});
      const raw=await res.text();let data={};
      try{data=raw?JSON.parse(raw):{};}catch{throw Error('Invalid simulator result');}
      if(!res.ok) throw Error(data.error||data.message||'HTTP '+res.status);
      const trades=Array.isArray(data.trades)?data.trades:[];
      out.replaceChildren(metricTiles([
        ['Completed trades',data.totalTrades],['Net P&L',rupees(data.netProfit)],
        ['Return %',data.returnPercent==null?'—':numeric(data.returnPercent)+'%'],
        ['Win rate %',data.winRatePercent==null?'—':numeric(data.winRatePercent)+'%'],
        ['Capital at end',rupees(data.finalCapital)],['Closed-equity drawdown %',numeric(data.maxClosedEquityDrawdownPercent)]
      ]),paragraph('Simulation only. Underlying trading fees, liquidity and unadjusted corporate actions can materially affect actual performance.'),
        dataTable('Historical equity fills',['Entry','Exit','Side','Quantity','Entry','Exit','Net P&L','Reason'],
          trades.slice(0,100).map(t=>[
            t.entryAt,t.exitAt,t.side,t.quantity,rupees(t.entryPrice),
            rupees(t.exitPrice),rupees(t.netProfit),t.exitReason
          ]),'No trades for the selected period.'));
    } catch(e) {out.replaceChildren(paragraph('Backtest failed: '+e.message,'error'));}
    finally {button.disabled=false;}
  };
  function navigate() {
    const current=tabs.find(a=>a.hash===location.hash)||tabs[0];
    for(const tab of tabs) {
      const visible=tab===current,section=byId(tab.hash.slice(1));
      section.hidden=!visible;
      if(visible) tab.setAttribute('aria-current','page'); else tab.removeAttribute('aria-current');
    }
    const id=current.hash.slice(1);
    if(markets.includes(id.toUpperCase())) refreshIndex(id.toUpperCase());
  }
  window.addEventListener('hashchange',navigate);
  navigate();
  setInterval(()=>{
    if(document.hidden)return;
    const id=tabs.find(a=>a.getAttribute('aria-current')==='page')?.hash.slice(1);
    if(id==='nifty'||id==='sensex') refreshIndex(id.toUpperCase());
  },10000);
})();
