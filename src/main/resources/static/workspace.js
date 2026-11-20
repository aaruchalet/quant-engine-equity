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
    const gateDescriptions={
      tradingSession:'Market session must be open',
      entryWindow:'Configured 09:30–14:45 entry interval',
      entriesAvailable:'Daily entry limit has capacity',
      dailyLossNotReached:'Shared daily loss cutoff not reached',
      dailyProfitNotReached:'Shared daily profit cutoff not reached',
      sharedPositionAvailable:'Only one NIFTY/SENSEX option position at a time',
      entriesNotPaused:'Pause, rejection and kill-switch controls'
    };
    const gateNames={
      tradingSession:'Market open',entryWindow:'Entry window',
      entriesAvailable:'Trade capacity',dailyLossNotReached:'Daily loss limit',
      dailyProfitNotReached:'Daily profit limit',
      sharedPositionAvailable:'Shared position',entriesNotPaused:'Entry permissions'
    };
    const risk=dataTable('Pre-entry and risk gates',
      ['Risk check','Status','Meaning'],
      Object.entries(checkMap).map(([k,v])=>[gateNames[k]||k,v?'PASS':'BLOCK',
        gateDescriptions[k]||'Configured operating gate']),
      'Risk checks are inactive until PAPER or LIVE is configured.');
    risk.classList.add('risk-panel');
    risk.querySelectorAll('tbody tr').forEach(tr=>{
      const status=tr.cells[1];
      if(status)status.replaceChildren(badge(status.textContent));
    });
    const settings=node('div',null,'settings-panel');
    settings.append(node('h3','YAML strategy settings'));
    const settingsGrid=node('dl',null,'strategy-settings');
    for(const [label,key] of strategyKeys){
      const item=node('div',null,'strategy-setting');
      item.append(node('dt',label),node('dd',asText(result.strategy?.[key])));
      settingsGrid.append(item);
    }
    settings.append(settingsGrid);
    const indexSplit=node('div',null,'index-split');
    indexSplit.append(risk,settings);
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
    view.replaceChildren(row,paragraph(statusText),summary,positionPanel,indexSplit,decisions);
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

  const equityPaneNames=['Discover','Research','Backtest'];
  function selectEquityPane(pane) {
    for(const name of equityPaneNames){
      const active=name===pane, tab=byId('equity'+name+'Tab');
      const content=byId('equity'+name+'Pane');
      tab.setAttribute('aria-selected',String(active));
      tab.tabIndex=active?0:-1;
      content.hidden=!active;
    }
  }
  for(const name of equityPaneNames){
    byId('equity'+name+'Tab').onclick=()=>selectEquityPane(name);
  }
  const researchState={candidate:null,inFlight:false};
  const researchMoney=value=>value==null?'—':rupees(value);
  const researchNumber=value=>value==null?'—':numeric(value);
  function researchLinks(symbol) {
    const links=externalSymbolLinks(symbol);
    const extra=[
      ['BSE announcements','https://www.bseindia.com/corporates/ann.html'],
      ['SEBI notices','https://www.sebi.gov.in/'],
      ['AMFI classifications','https://www.amfiindia.com/otherdata/categorisation-of-stocks']
    ];
    for(const [name,url] of extra){
      const a=node('a',name);a.href=url;a.target='_blank';a.rel='noopener noreferrer';
      links.append(a);
    }
    return links;
  }
  function renderResearch(data,candidate) {
    const out=byId('equityResearchDetail');
    const heading=node('div',null,'research-heading');
    heading.append(node('h3',asText(candidate.company||data.symbol)+' · '+data.symbol),
      badge(data.status==='PRELIMINARY_RESEARCH'?'PRELIMINARY DATA':'PARTIAL DATA'));
    const meta=paragraph('Verified exchange '+data.exchange+' · ISIN '+data.isin+
      ' · Latest session '+asText(data.asOf)+' · Retrieved '+asText(data.retrievedAt)+
      ' · '+data.observedSessions+'/'+data.requiredSessions+' sessions.');
    const summary=metricTiles([
      ['Official EOD close',researchMoney(data.latestClose)],
      ['Previous close',researchMoney(data.previousClose)],
      ['Daily change',data.priceChangePercent==null?'—':researchNumber(data.priceChangePercent)+'%'],
      ['20-session avg turnover',data.averageTradedValueCr==null?'UNVERIFIED':'₹'+researchNumber(data.averageTradedValueCr)+' cr'],
      ['Indicative EMA 9',researchMoney(data.ema9Indicative)],
      ['Indicative EMA 20',researchMoney(data.ema20Indicative)],
      ['Indicative RSI 14',researchNumber(data.rsi14Indicative)],
      ['Observed-period range',researchMoney(data.periodLow)+' – '+researchMoney(data.periodHigh)]
    ]);
    const limitations=node('div',null,'research-coverage');
    limitations.append(node('h3','Data quality and limitations'));
    const limitationList=node('ul');
    for(const warning of data.limitations||[])limitationList.append(node('li',warning));
    limitations.append(limitationList);
    const checks=node('div',null,'research-missing');
    checks.append(node('h3','Verification still required before a BUY decision'));
    const checklist=node('ul');
    for(const check of data.missingChecks||[])checklist.append(node('li',check));
    checks.append(checklist);
    const prices=Array.isArray(data.dailyPrices)?data.dailyPrices.slice().reverse():[];
    const history=dataTable('Official dated closing-price history',
      ['Session','Close','Traded value (₹cr)','Archive source'],
      prices.map(p=>[p.session,researchMoney(p.close),researchNumber(p.tradedValueCr),'Official archive']),
      'No verified session history available.');
    const cells=history.querySelectorAll('tbody tr');
    cells.forEach((row,i)=>{
      const p=prices[i],cell=row.cells[3];
      if(!p||!cell)return;
      if(/^https:\/\/(nsearchives\.nseindia\.com|www\.bseindia\.com)\//.test(p.source)){
        const a=node('a','View exchange report');
        a.href=p.source;a.target='_blank';a.rel='noopener noreferrer';
        cell.replaceChildren(a);
      }
    });
    out.replaceChildren(heading,meta,summary,
      paragraph('Preliminary exchange-history research only. No verified buy signal, stop, target, probability of profit or full fundamental score can be inferred from these archives.','notice'),
      limitations,checks,researchLinks(data.symbol),history);
  }
  async function researchCandidate(candidate) {
    if(!candidate || !/^INE[A-Z0-9]{9}$/.test(candidate.isin||'')
        || !['NSE','BSE'].includes(candidate.exchange)) {
      selectEquityPane('Research');
      byId('equityResearchDetail').replaceChildren(paragraph(
        'A verified ISIN and NSE/BSE listing are required. Select a candidate from Discover.','error'));
      return;
    }
    researchState.candidate=candidate;
    selectEquityPane('Research');
    const out=byId('equityResearchDetail'),button=byId('researchRefresh');
    out.replaceChildren(paragraph('Researching '+asText(candidate.company||candidate.symbol)+
      ' using dated official '+candidate.exchange+' exchange archives…'));
    button.disabled=true;
    researchState.inFlight=true;
    try {
      const data=await fetchJSON('/api/equity/research?isin='+encodeURIComponent(candidate.isin)+
        '&exchange='+encodeURIComponent(candidate.exchange)+'&ts='+Date.now());
      if(researchState.candidate!==candidate)return;
      renderResearch(data,candidate);
    } catch(error) {
      out.replaceChildren(paragraph('Unable to complete exchange research: '+error.message,'error'),
        researchLinks(candidate.symbol));
    } finally {
      researchState.inFlight=false;button.disabled=false;
    }
  }
  byId('researchRefresh').onclick=()=>{
    if(researchState.candidate&&!researchState.inFlight)researchCandidate(researchState.candidate);
  };
  function candidateTable(rows) {
    const wrap=node('div',null,'table-wrap candidates');
    wrap.append(node('h3','AMFI-classified small-cap candidates · select a row to research'));
    if(!rows.length){
      wrap.append(paragraph('No eligible candidates could be verified from official reports.'));
      return wrap;
    }
    const table=node('table',null,'candidate-table');
    const head=table.createTHead().insertRow();
    for(const heading of ['Company','Exchange','Symbol','Official close','Session',
      '20-session turnover (₹cr)','Coverage','Action'])head.append(node('th',heading));
    const tbody=table.createTBody();
    for(const candidate of rows.slice(0,100)){
      const tr=tbody.insertRow();
      tr.className='candidate-row';
      tr.tabIndex=0;
      tr.setAttribute('aria-label','Research '+asText(candidate.company)+' · '+candidate.symbol);
      tr.onclick=()=>researchCandidate(candidate);
      tr.onkeydown=e=>{
        if(e.target===tr&&(e.key==='Enter'||e.key===' ')){
          e.preventDefault();researchCandidate(candidate);
        }
      };
      const first=tr.insertCell(),link=node('button',candidate.company,'table-link');
      link.type='button';link.onclick=e=>{e.stopPropagation();researchCandidate(candidate);};
      first.append(link);
      for(const value of [candidate.exchange,candidate.symbol,rupees(candidate.close),candidate.session,
        candidate.turnoverCr==null?'UNVERIFIED':numeric(candidate.turnoverCr)]){
        tr.append(node('td',asText(value)));
      }
      const status=tr.insertCell(),decision=asText(candidate.decision);
      status.append(badge(decision==='RESEARCH REQUIRED'?'HISTORY VERIFIED':
        decision==='BELOW LIQUIDITY FLOOR'?'BELOW LIQUIDITY FLOOR':'PARTIAL DATA'));
      const action=tr.insertCell(),open=node('button','Research','secondary');
      open.type='button';open.onclick=e=>{e.stopPropagation();researchCandidate(candidate);};
      action.append(open);
    }
    wrap.append(table);
    if(rows.length>100)wrap.append(paragraph('Showing the first 100 candidates. Refine the price band to narrow the results.'));
    return wrap;
  }

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
      ['Official closing price',rupees(quote.price)],['Change %',quote.changePercent == null?'—':numeric(quote.changePercent)+'%'],
      ['Previous close',rupees(quote.previousClose)],['Session high',rupees(quote.dayHigh)],
      ['Session low',rupees(quote.dayLow)],['52-week high',rupees(quote.week52High)],
      ['52-week low',rupees(quote.week52Low)],['Decision','INSUFFICIENT DATA']
    ]),paragraph('Official EOD archive fetched '+asText(quote.fetchedAt)+' · Closing session '+asText(quote.marketTimestamp)+
      ' · '+asText(quote.exchange)+' / '+asText(quote.isin)),
      paragraph('NOT LIVE. AMFI classification, financial statements, liquidity/depth, surveillance clearance and tradeable setup have not been verified. No BUY recommendation can be issued.'),
      externalSymbolLinks(quote.symbol));
    if(quote.isin && ['NSE','BSE'].includes(quote.exchange)){
      const action=node('button','Research '+quote.symbol);
      action.type='button';
      action.onclick=()=>researchCandidate({exchange:quote.exchange,symbol:quote.symbol,
        isin:quote.isin,company:quote.company});
      root.append(action);
    }
  }
  async function showEquity(symbol) {
    if(!/^[A-Z0-9&_.-]{1,25}$/.test(symbol)) return;
    quoteState.symbol=symbol;byId('equityQuery').value=symbol;
    byId('equityDetail').replaceChildren(paragraph('Requesting fresh official NSE data for '+symbol+'…'));
    try {renderQuote(await fetchJSON('/api/equity/eod/'+encodeURIComponent(symbol)+'?ts='+Date.now()));}
    catch(e) {byId('equityDetail').replaceChildren(paragraph('Quote unavailable: '+e.message,'error'));}
  }
  byId('equityLookup').onsubmit=async event=>{
    event.preventDefault();
    const q=byId('equityQuery').value.trim(),out=byId('equityMatches');
    out.replaceChildren(paragraph('Searching NSE…'));
    try {
      const result=await fetchJSON('/api/equity/lookup?q='+encodeURIComponent(q)+'&ts='+Date.now());
      const suggestions=result.suggestions||[];
      out.replaceChildren();
      if(!suggestions.length) {
        out.append(paragraph('No matching exchange equity symbols returned. You may search by an exact ticker.'));
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
    } catch(e) {out.replaceChildren(paragraph('Exchange EOD search unavailable: '+e.message,'error'));}
  };
  byId('equityRefresh').onclick=()=>{
    const symbol=quoteState.symbol || byId('equityQuery').value.trim().toUpperCase();
    if(!symbol) byId('equityDetail').replaceChildren(paragraph('Search for or enter an NSE/BSE symbol first.','error'));
    else showEquity(symbol);
  };
  const screenForm=byId('screen');
  async function screenAll() {
    if(!screenForm.reportValidity()) return;
    const f=new FormData(screenForm),min=Number(f.get('minPrice')),max=Number(f.get('maxPrice'));
    const low=Number(f.get('minSessions')),high=Number(f.get('maxSessions'));
    const capital=f.get('capital')===''?null:Number(f.get('capital'));
    const out=byId('screenResult');
    if(min<=0 || min>=max || low<1 || low>high){
      out.replaceChildren(paragraph('Invalid price band or holding-session range.','error'));return;
    }
    out.replaceChildren(paragraph('Refreshing AMFI classification and the latest available official NSE/BSE closing-price archives…'));
    const button=byId('screenAll');
    button.disabled=true;
    try {
      const scan=await fetchJSON('/api/equity/discover',{
        method:'POST',headers:{'Content-Type':'application/json','Cache-Control':'no-cache'},
        body:JSON.stringify({minPrice:min,maxPrice:max,minSessions:low,maxSessions:high,
          capital,availableCash:null})
      });
      const summary=metricTiles([
        ['AMFI small-cap universe',scan.smallCaps],['Verified ISIN mappings',scan.mapped],
        ['In configured price band',scan.priceMatches],
        ['Above ₹5cr liquidity floor',scan.liquidityPasses],
        ['Discovery result',scan.outcome],['Qualified BUY signals','NOT EVALUATED']
      ]);
      const dates=Object.entries(scan.priceDates||{}).map(([market,date])=>market+' '+date).join(' · ');
      const warning=paragraph('Exchange closing dates: '+(dates||'Unavailable')+' · Retrieved '+asText(scan.retrievedAt)+
        ' · Holding period: '+low+'–'+high+' trading sessions. This scan checks AMFI, ISIN, prices and 20-session traded value only. Governance, financials, surveillance, volume chart and net R:R have NOT been verified.','notice');
      const data=Array.isArray(scan.candidates)?scan.candidates:[];
      const results=candidateTable(data);
      const sourceLinks=node('div',null,'source-links');
      for(const url of [scan.amfiSource,...(scan.exchangeSources||[])]){
        if(!url || !String(url).startsWith('https://')) continue;
        const a=node('a','Source');a.href=url;a.target='_blank';a.rel='noopener noreferrer';
        a.title=url;sourceLinks.append(a);
      }
      out.replaceChildren(summary,warning,...(scan.limitations||[]).map(message=>paragraph(message,'notice')),
        results,sourceLinks);
    } catch(e) {
      out.replaceChildren(paragraph('Fresh AMFI/exchange discovery unavailable: '+e.message+
        '. No cached or invented stock picks will be substituted.','error'));
    } finally {button.disabled=false;}
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
