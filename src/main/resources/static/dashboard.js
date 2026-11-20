/* quant-engine-test · local historical research dashboard, no external dependencies */
"use strict";
(() => {
  const $ = (id) => document.getElementById(id);
  const form = $("backtestForm");
  const runButton = $("runButton");
  const feedback = $("feedback");
  const currency = new Intl.NumberFormat("en-IN", {style: "currency", currency: "INR", maximumFractionDigits: 2});
  const number = new Intl.NumberFormat("en-IN", {maximumFractionDigits: 2});
  const percent = (value) => number.format(Number(value) || 0) + "%";
  const money = (value) => currency.format(Number(value) || 0);
  const n = (value) => number.format(Number(value) || 0);
  const pageSize = 12;
  const state = {report: null, page: 0, fileReady: false, activeVersion: "v1"};

  function showFeedback(message, kind = "info") {
    feedback.textContent = message;
    feedback.className = "feedback" + (kind === "info" ? "" : " " + kind);
    feedback.hidden = false;
  }

  function setBusy(busy) {
    runButton.disabled = busy || !state.fileReady;
    runButton.classList.toggle("busy", busy);
    $("runButtonText").textContent = busy ? "Running simulation…" : "Run historical backtest";
  }

  async function checkHistory() {
    try {
      const response = await fetch("/api/backtests/history-status", {cache: "no-store"});
      if (!response.ok) throw new Error("HTTP " + response.status);
      const status = await response.json();
      state.fileReady = Boolean(status.available);
      if (status.symbol) $("symbol").value = status.symbol;
      $("dataLabel").textContent = state.fileReady ? "Historical CSV ready" : "Historical CSV not found";
      $("dataDetail").textContent = state.fileReady
        ? String(status.fileName || "Local CSV") + " · " + n(Math.round(Number(status.sizeBytes || 0) / 1024)) + " KB"
        : "Configure HISTORY_CSV_PATH and restart the app";
      $("dataIndicator").className = "data-indicator " + (state.fileReady ? "ready" : "missing");
      $("dataIndicator").setAttribute("aria-label", state.fileReady ? "CSV available" : "CSV missing");
      runButton.disabled = !state.fileReady;
      if (!state.fileReady) {
        showFeedback("Your local historical CSV is not available. Set HISTORY_CSV_PATH to the downloaded NIFTYBEES file, restart Spring Boot, and refresh this page.", "error");
      }
    } catch (error) {
      state.fileReady = false;
      $("dataLabel").textContent = "Unable to check data source";
      $("dataDetail").textContent = "Refresh this page after starting Spring Boot";
      $("dataIndicator").className = "data-indicator missing";
      runButton.disabled = true;
      showFeedback("Cannot reach the local backtest API: " + error.message, "error");
    }
  }

  const numericFields = [
    "initialCapital", "riskPercent", "stopLossPercent", "targetRiskReward",
    "slippageBps", "fastEma", "slowEma", "rsiPeriod", "maxTradesPerDay",
    "feeBps", "feePerOrder", "maxDailyLossPercent", "lotSize", "longRsiMin", "longRsiMax"
  ];

  function buildRequest() {
    const request = {
      symbol: $("symbol").value.trim().toUpperCase(),
      from: $("from").value,
      through: $("through").value,
      allowShort: $("allowShort").checked
    };
    const isV2 = $("strategyVersion").value === "v2";
    // V2 EMA, RSI, VWAP, stop/target, times and daily limits are external YAML config,
    // not stale/disabled V1 form fields. Capital and slippage vary per run; V2 fees come from YAML.
    const fields = isV2 ? ["initialCapital", "slippageBps"] : numericFields;
    for (const field of fields) request[field] = Number($(field).value);
    if (request.from > request.through) throw new Error("The start date must not be later than the end date.");
    if (!isV2 && request.fastEma >= request.slowEma) throw new Error("Slow EMA must be greater than fast EMA.");
    if (!isV2 && request.longRsiMin >= request.longRsiMax) throw new Error("Long RSI minimum must be below maximum.");
    return request;
  }

  async function runBacktest(event) {
    event.preventDefault();
    if (!state.fileReady) {
      showFeedback("No local historical CSV is configured. Refresh after correcting HISTORY_CSV_PATH.", "error");
      return;
    }
    if (!form.reportValidity()) return;
    let request;
    try { request = buildRequest(); }
    catch (error) { showFeedback(error.message, "error"); return; }
    setBusy(true);
    showFeedback("Processing your local historical candles and executing the strategy…");
    try {
      const isV2 = $("strategyVersion").value === "v2";
      const response = await fetch(isV2 ? "/api/backtests/vwap-ema-v2" : "/api/backtests/ema-vwap-rsi", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(request)
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.error || payload.message || "Backtest failed (HTTP " + response.status + ")");
      if (!payload || !Array.isArray(payload.trades) || !Array.isArray(payload.closedTradeEquityCurve)) {
        throw new Error("Server returned an incomplete backtest report.");
      }
      state.report = payload;
      state.activeVersion = $("strategyVersion").value;
      state.page = 0;
      $("tradeSearch").value = "";
      $("sideFilter").value = "";
      renderReport(payload);
      $("emptyState").hidden = true;
      $("results").hidden = false;
      $("downloadJson").disabled = false;
      $("downloadCsv").disabled = false;
      showFeedback("Backtest completed against your local historical CSV. Review simulated results below; these are not live trading profits.", "success");
    } catch (error) {
      showFeedback("Backtest failed: " + error.message, "error");
    } finally {
      setBusy(false);
    }
  }

  function setText(id, value) { $(id).textContent = value; }
  function setPolarity(id, value) {
    const el = $(id);
    el.classList.remove("positive", "negative");
    if (Number(value) > 0) el.classList.add("positive");
    if (Number(value) < 0) el.classList.add("negative");
  }
  function formatDatetime(date) {
    return String(date || "—").replace("T", " · ").replace(/(\d{2}:\d{2}):\d{2}$/, "$1");
  }
  function formatDate(date) {
    const d = String(date || "").slice(0, 10);
    const parts = d.split("-");
    if (parts.length !== 3) return d || "—";
    return parts[2] + " " + new Date(Date.UTC(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2])))
      .toLocaleString("en-IN", {month: "short", timeZone: "UTC"}) + " " + parts[0];
  }

  function renderReport(report) {
    setText("resultTitle", report.symbol + " · " + (state.activeVersion === "v2" ?
      "EMA → RSI → VWAP-side V2" : "Legacy EMA/VWAP/RSI V1") + " simulation");
    setText("resultPeriod", formatDate(report.from) + " – " + formatDate(report.through) + " · " + report.timezone);
    setText("metricProfit", money(report.netProfit));
    setText("metricReturn", (Number(report.returnPercent) > 0 ? "+" : "") + percent(report.returnPercent) + " return");
    setText("metricCapital", money(report.finalCapital));
    setText("metricInitialCapital", "From " + money(report.initialCapital));
    setText("metricWinRate", percent(report.winRatePercent));
    setText("metricWins", n(report.winningTrades) + " wins · " + n(report.losingTrades) + " losses");
    setText("metricTrades", n(report.totalTrades));
    setText("metricSessions", n(report.tradingSessions) + " requested-range sessions");
    setText("metricDrawdown", percent(report.maxClosedEquityDrawdownPercent));
    setText("metricProfitFactor", report.profitFactor == null ? "N/A" : n(report.profitFactor));
    setPolarity("metricProfit", report.netProfit);
    setPolarity("metricReturn", report.returnPercent);
    setText("coverageNote", n(report.candlesProcessed) + " requested-range candles across " + n(report.tradingSessions)
      + " sessions; " + n((report.skippedSessions || []).length) + " incomplete sessions excluded.");
    const skips = Array.isArray(report.skippedSessions) ? report.skippedSessions : [];
    $("skippedBlock").hidden = skips.length === 0;
    setText("skippedNote", skips.length <= 15 ? skips.join(" · ") : skips.slice(0, 15).join(" · ") + " · and " + (skips.length - 15) + " more");
    setText("tradeSummary", n(report.totalTrades) + " completed trades · " + money(report.trades.reduce((sum, t) => sum + Number(t.totalFees || 0), 0)) + " total simulated fees");
    drawEquity(report.closedTradeEquityCurve);
    renderTrades();
  }

  const svgNamespace = "http://www.w3.org/2000/svg";
  function svgElement(tag, attrs = {}, text = "") {
    const element = document.createElementNS(svgNamespace, tag);
    for (const [key, val] of Object.entries(attrs)) element.setAttribute(key, String(val));
    if (text) element.textContent = text;
    return element;
  }

  function drawEquity(curve) {
    const svg = $("equityChart");
    svg.replaceChildren();
    const points = curve.filter((point) => Number.isFinite(Number(point.equity)));
    if (points.length === 0) {
      svg.appendChild(svgElement("text", {x: 460, y: 130, "text-anchor": "middle", fill: "#8698b1", "font-size": 14}, "No equity data"));
      return;
    }
    const W = 920, H = 280, left = 94, right = 24, top = 21, bottom = 36;
    const plotW = W - left - right, plotH = H - top - bottom;
    const values = points.map((p) => Number(p.equity));
    const minVal = Math.min(...values), maxVal = Math.max(...values);
    const padding = Math.max((maxVal - minVal) * 0.14, Math.abs(maxVal) * 0.005, 1);
    const lower = minVal - padding, upper = maxVal + padding;
    const coords = values.map((value, index) => ({
      x: left + (points.length > 1 ? index / (points.length - 1) : 0.5) * plotW,
      y: top + ((upper - value) / (upper - lower)) * plotH
    }));
    for (let i = 0; i <= 4; i++) {
      const value = lower + (upper - lower) * (1 - i / 4);
      const y = top + i * plotH / 4;
      svg.appendChild(svgElement("line", {x1: left, y1: y, x2: W - right, y2: y,
        stroke: "#e7edf5", "stroke-dasharray": "4 6"}));
      svg.appendChild(svgElement("text", {x: left - 12, y: y + 4, "text-anchor": "end",
        fill: "#8b9bb0", "font-size": 11}, "₹" + new Intl.NumberFormat("en-IN", {notation: "compact", maximumFractionDigits: 1}).format(value)));
    }
    const d = coords.map((point, index) => (index ? "L" : "M") + point.x.toFixed(2) + "," + point.y.toFixed(2)).join(" ");
    const fill = d + " L" + coords[coords.length - 1].x.toFixed(2) + "," + (H - bottom) +
      " L" + coords[0].x.toFixed(2) + "," + (H - bottom) + " Z";
    svg.appendChild(svgElement("path", {d: fill, fill: "#d9e9f8", opacity: ".7"}));
    svg.appendChild(svgElement("path", {d, stroke: "#357cbe", "stroke-width": 2.6, fill: "none",
      "stroke-linejoin": "round", "stroke-linecap": "round"}));
    const last = coords[coords.length - 1];
    svg.appendChild(svgElement("circle", {cx: last.x, cy: last.y, r: 5, fill: "#fff", stroke: "#357cbe", "stroke-width": 2.5}));
    setText("chartStart", formatDate(points[0].timestamp));
    setText("chartEnd", formatDate(points[points.length - 1].timestamp));
  }

  function renderTrades() {
    if (!state.report) return;
    const query = $("tradeSearch").value.trim().toLowerCase();
    const side = $("sideFilter").value;
    const filtered = state.report.trades.filter((trade) => {
      if (side && trade.side !== side) return false;
      return !query || [trade.entryAt, trade.exitAt, trade.exitReason, trade.side].some((x) =>
        String(x || "").toLowerCase().includes(query));
    });
    const maxPage = Math.max(0, Math.ceil(filtered.length / pageSize) - 1);
    state.page = Math.min(maxPage, state.page);
    const start = state.page * pageSize;
    const rows = filtered.slice(start, start + pageSize);
    const tbody = $("tradeRows");
    tbody.replaceChildren();
    if (!rows.length) {
      const tr = document.createElement("tr"), td = document.createElement("td");
      td.colSpan = 9;
      td.className = "empty-row";
      td.textContent = filtered.length === 0 && query ? "No trades match your search." : "No executed trades for this period.";
      tr.appendChild(td);
      tbody.appendChild(tr);
    }
    for (const trade of rows) {
      const tr = document.createElement("tr");
      const values = [formatDatetime(trade.entryAt), formatDatetime(trade.exitAt), trade.side, n(trade.quantity),
        n(trade.entryPrice), n(trade.exitPrice), n(trade.totalFees), money(trade.netProfit),
        String(trade.exitReason || "").replaceAll("_", " ")];
      values.forEach((value, index) => {
        const td = document.createElement("td");
        td.textContent = value;
        if (index >= 3 && index <= 7) td.classList.add("numeric");
        if (index === 2) td.className = "side-cell";
        if (index === 2) {
          const chip = document.createElement("span");
          chip.className = "side-chip" + (trade.side === "SHORT" ? " short" : "");
          chip.textContent = trade.side;
          td.replaceChildren(chip);
        }
        if (index === 7) td.classList.add(Number(trade.netProfit) >= 0 ? "pnl-positive" : "pnl-negative");
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    }
    setText("paginationInfo", filtered.length === 0 ? "0 trades" :
      n(start + 1) + "–" + n(Math.min(filtered.length, start + pageSize)) + " of " + n(filtered.length) + " trades");
    $("prevPage").disabled = state.page === 0;
    $("nextPage").disabled = state.page >= maxPage;
  }

  function saveFile(filename, contents, type) {
    const blob = new Blob([contents], {type});
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function csvCell(value) {
    if (typeof value === "number") return Number.isFinite(value) ? String(value) : "";
    let text = String(value ?? "");
    if (/^[\s]*[=+@\-]/.test(text)) text = "'" + text; // spreadsheet formula injection protection
    return '"' + text.replaceAll('"', '""') + '"';
  }

  function downloadTrades() {
    if (!state.report) return;
    const columns = ["side", "entryAt", "exitAt", "entryPrice", "exitPrice", "quantity", "stopLoss",
      "target", "exitReason", "grossProfit", "totalFees", "netProfit"];
    const lines = [columns.join(",")];
    for (const trade of state.report.trades) lines.push(columns.map((column) => csvCell(trade[column])).join(","));
    saveFile("niftybees-backtest-trades.csv", lines.join("\r\n") + "\r\n", "text/csv;charset=utf-8");
  }

  const v1Only = ["riskPercent","stopLossPercent","targetRiskReward","fastEma","slowEma",
    "rsiPeriod","maxTradesPerDay","maxDailyLossPercent","lotSize","longRsiMin","longRsiMax","allowShort",
    "feeBps","feePerOrder"];
  $("strategyVersion").addEventListener("change", () => {
    const isV2 = $("strategyVersion").value === "v2";
    $("v2Explanation").hidden = !isV2;
    for (const field of v1Only) $(field).disabled = isV2;
    state.report = null;
    $("results").hidden = true;
    $("emptyState").hidden = false;
    $("downloadJson").disabled = true;
    $("downloadCsv").disabled = true;
    if (isV2) showFeedback("V2 requires genuine bar-opening prices through the square-off time in your YAML configuration.");
    else feedback.hidden = true;
  });
  form.addEventListener("submit", runBacktest);
  $("tradeSearch").addEventListener("input", () => { state.page = 0; renderTrades(); });
  $("sideFilter").addEventListener("change", () => { state.page = 0; renderTrades(); });
  $("prevPage").addEventListener("click", () => { if (state.page > 0) { state.page--; renderTrades(); } });
  $("nextPage").addEventListener("click", () => { state.page++; renderTrades(); });
  $("downloadJson").addEventListener("click", () => {
    if (state.report) saveFile("niftybees-backtest-report.json", JSON.stringify(state.report, null, 2) + "\n", "application/json");
  });
  $("downloadCsv").addEventListener("click", downloadTrades);
  checkHistory();
})();
