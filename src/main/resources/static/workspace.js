'use strict';
const byId = id => document.getElementById(id);
const markets = ['NIFTY','SENSEX'];
const tabs = [...document.querySelectorAll('nav a')];
const money = n => n == null ? '—' : new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(n);
function node(tag, text, cls) {
  const element = document.createElement(tag);
  if (text != null) element.textContent = text;
  if (cls) element.className = cls;
  return element;
}
function table(title, headers, rows, fallback) {
  const panel = node('article',null,'panel scroll');
  panel.append(node('h2',title));
  if (!rows.length) { panel.append(node('p',fallback)); return panel; }
  const grid = node('table'), head = grid.createTHead().insertRow(), body = grid.createTBody();
  headers.forEach(h => head.append(node('th',h)));
  rows.forEach(row => { const tr = body.insertRow(); row.forEach(v => tr.append(node('td',v ?? '—'))); });
  panel.append(grid);
  return panel;
}
function metrics(pairs) {
  const el = node('div',null,'metrics');
  for (const [k,v] of pairs) { const card = node('div',k,'metric'); card.append(node('b',v ?? '—')); el.append(card); }
  return el;
}
function performance(days) {
  const panel = node('article',null,'panel');
  panel.append(node('h2','Cumulative net P&L'));
  if (!days.length) return panel;
  let cumulative = 0;
  const values = [0,...days.map(day => cumulative += day.netPnl)];
  const low = values.reduce((a,b)=>Math.min(a,b)), high = values.reduce((a,b)=>Math.max(a,b));
  const y = value => high === low ? 60 : 108 - 96 * (value - low) / (high - low);
  const chart = document.createElementNS('http://www.w3.org/2000/svg','svg');
  chart.setAttribute('viewBox','0 0 760 120');
  chart.setAttribute('role','img');
  chart.setAttribute('aria-label','Cumulative net profit and loss from zero to '+money(cumulative));
  const line = document.createElementNS(chart.namespaceURI,'polyline');
  line.setAttribute('points',values.map((value,i)=>(4+752*i/days.length)+','+y(value)).join(' '));
  line.setAttribute('fill','none'); line.setAttribute('stroke','#08735a'); line.setAttribute('stroke-width','3');
  chart.append(line); panel.append(chart,node('small',days[0].date+' — '+days[days.length-1].date+' · Range '+money(low)+' to '+money(high)));
  return panel;
}
function download(data, filename) {
  const button = node('button','Download report','download');
  button.onclick = () => {
    const a = node('a'); a.href = URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));
    a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(a.href),1000);
  };
  return button;
}
async function api(path, payload) {
  const res = await fetch(path,payload ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)} : {cache:'no-store'});
  const data = await res.json();
  if (!res.ok) throw Error(data.message || data.error || 'Request failed (' + res.status + ')');
  return data;
}
function bindRun(form,out,run) {
  form.onsubmit = async event => {
    event.preventDefault(); const button = form.querySelector('button'); button.disabled = true;
    out.replaceChildren(node('p','Processing snapshot…'));
    try {
      const file = form.elements.file.files[0];
      const input = file ? JSON.parse(await file.text()) : null;
      out.replaceChildren(...await run(input));
    } catch (e) { out.replaceChildren(node('p',e.message,'error')); }
    finally { button.disabled = false; }
  };
}
for (const market of markets) {
  const id = market.toLowerCase(), section = byId(id);
  section.innerHTML = '<small>HISTORICAL OPTIONS</small><h1>'+market+' options backtest</h1><p>Replay calls and puts using five-minute signals, historical premiums and quoted depth.</p><form class="upload"><label>Import replay (optional with a local feed)<input name="file" type="file" accept=".json,application/json"></label><button>Run backtest</button></form><div aria-live="polite"><article class="empty"><h2>Backtest results</h2><p>Run the local replay or import session JSON to see fills, fees and daily returns.</p></article></div>';
  const out = section.lastElementChild;
  bindRun(section.querySelector('form'),out,async input => {
    const r = await api('/api/backtests/vwap-ema-v2/options/'+market+(input ? '' : '/local'),input || {});
    return [metrics([['Net P&L',money(r.days.reduce((n,d)=>n+d.netPnl,0))],['Filled trades',r.trades.length],['Sessions',r.days.length],['Skipped entries',r.skipped.length]]),
      performance(r.days),
      table('Executed trades',['Entry (IST)','Expiry','Contract','Lots','Entry','Exit','Fees','Net P&L','Exit reason'],r.trades.map(t=>[t.position.entryAt,t.position.expiry,t.position.strike+' '+t.position.right,t.position.lots,money(t.position.entryPremium),money(t.exitPremium),money(t.position.entryFee+t.exitFee),money(t.netPnl),t.reason]),'No trades filled in this replay.'),
      table('Daily results',['Session','Entry spend','Budget remaining','Net P&L'],r.days.map(d=>[d.date,money(d.entrySpend),money(d.remainingBudget),money(d.netPnl)]),'No sessions.'),
      table('Skipped entries',['Time (IST)','Reason'],r.skipped.map(s=>[s.at,s.reason]),'No skipped entries.'),download(r,id+'-replay.json')];
  });
}
let reading = false;
async function refresh() {
  if (reading) return;
  reading = true;
  try {
    const s = await api('/api/trading/status');
    byId('runtime').replaceChildren(metrics([['Mode',s.mode],['Daily budget',money(s.dailyBudget)],['Entry spend',money(s.spent)],['Position units',s.quantity]]),
      table('Runtime',['Status','Updated','Contract','Pending action','Stop','Target'],[[s.status,s.updated,s.contract?.instrument?.symbol,s.pending,money(s.stop),money(s.target)]]),
      table('Recent activity',['Time','Event','Instrument','Quantity','Realized P&L'],(s.events||[]).map(e=>[e.time,e.event,e.instrument,e.quantity,money(e.realizedPnl)]),'No journal events available in this mode.'));
  } catch(e) { byId('runtime').replaceChildren(node('p',e.message,'error')); } finally { reading=false; }
}
byId('refresh').onclick = refresh;
bindRun(byId('screen'),byId('screenResult'),async snapshot => {
  const config = {};
  for (const [key,value] of new FormData(byId('screen'))) if (key !== 'file') config[key] = Number(value);
  const r = await api(snapshot ? '/api/equity/screen' : '/api/equity/screen/latest',snapshot ? {config,snapshot} : config);
  const evidence = node('div');
  for (const stock of r.results) {
    const detail = node('details'); detail.append(node('summary',stock.symbol+' · '+stock.decision),node('pre',JSON.stringify(stock,null,2))); evidence.append(detail);
  }
  return [metrics([['Result',r.outcome],['Qualified',r.shortlist.length],['Screened',r.results.length],['Market regime',r.regime]]),node('p','Snapshot as of '+r.as_of),
    table('Ranked setups',['Symbol','Price','Score','Entry','Stop','Targets','Net R:R','Sessions','Units','Decision'],r.shortlist.map(s=>[s.symbol,money(s.price),s.score,money(s.entry),money(s.stop_loss),s.targets.map(money).join(' / '),s.net_rr.toFixed(2),s.holding_period,s.position_size,s.decision]),'No candidates qualify for this snapshot and configuration.'),node('h2','Decisions and evidence'),evidence,download(r,'equity-screen.json')];
});
function navigate() {
  const current = tabs.find(a=>a.hash===location.hash) || tabs[0];
  tabs.forEach(a => { byId(a.hash.slice(1)).hidden = a !== current; if(a === current) a.setAttribute('aria-current','page'); else a.removeAttribute('aria-current'); });
  if(current.hash==='#trading') refresh();
}
window.addEventListener('hashchange',navigate); navigate();
setInterval(()=>{ if(!document.hidden && !byId('trading').hidden) refresh(); },10000);
