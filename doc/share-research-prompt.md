# Share Research — Full Company Analysis with Final BUY / SELL / HOLD / WAIT Verdict

## PROJECT ROLE
You are an expert Indian equity research analyst and senior Java/Spring Boot quantitative-platform engineer. Enhance the **Equity Research → Research** tab shown in the application. The page must produce a **complete, evidence-backed analysis for the selected NSE/BSE equity** and finish with **exactly one prominent decision: BUY, SELL, HOLD, or WAIT**.

This application must work independently with market-data ingestion, scheduled updates, deterministic indicators/scoring, and auditable decision rules. **Do not require any AI API, LLM, manual equity entry, or human to calculate indicators.** If an AI summary feature is added later, it must be optional and must not control trade decisions.

## 1. HOW STOCKS ARE SELECTED
- Automatically discover equities from official NSE/BSE universe and latest applicable AMFI small-cap classification; the user should not have to supply a symbol.
- Retain the Discover tab: display qualifying stocks within the configured price range and allow clicking a row to open Research.
- Support research from a selected candidate and optional manual NSE/BSE symbol lookup, but do not make manual input mandatory.
- Use the existing `stock.md` configuration for **minimum share price, maximum share price, and holding period**. Do not create duplicate competing settings. Keep the remaining screening and risk criteria unchanged unless implementation requires clearly documented defaults.
- Treat *stock under ₹100* and *small cap* as distinct checks: verify AMFI classification independently of share price.

## 2. DATA COLLECTION AND PROVENANCE
Fetch and reconcile, as available:
1. **Identity:** company legal name, exchange, symbol, ISIN, sector/industry, market cap, free float, small-cap status.
2. **Price:** latest official EOD close, prior close, day change %, OHLC, 52-week high/low, corporate-action-adjusted daily OHLCV for >=250 sessions if available, weekly candles, timestamp, source link.
3. **Liquidity:** 20-day and 60-day average traded value, volume, turnover, delivery volume if reliable, trading frequency, spread or slippage proxy when available.
4. **Financials:** quarterly and annual revenue, EBITDA/operating profit, PAT, EPS, margins, ROE, ROCE, debt/equity, interest coverage, operating/free cash flow, balance sheet, receivables and dilution.
5. **Valuation:** P/E, P/B, EV/EBITDA, price/sales and suitable peer/sector comparisons; mark metrics N/A if inapplicable.
6. **Ownership and governance:** promoters, pledged shares, latest shareholding changes, FII/FPI and DII, audit qualifications, related-party issues and regulatory filings.
7. **News/catalysts:** official exchange announcements, upcoming earnings, dividends, splits/rights/bonus, orders/contracts, capex, policy impacts, litigation, insider disclosures and company-specific news within the previous 30–90 days; cite dates and sources.
8. **Trading restrictions:** ASM/GSM, trade-to-trade, suspension, lower/upper circuit exposure and other current exchange restrictions.
9. **Market context:** Nifty 50, Nifty Smallcap 250, relevant sector index, volatility and market breadth when verified data exists.

For **every dataset** retain source URL, source type, publication/as-of date, retrieval time (Asia/Kolkata), adjustment basis, freshness and confidence. Prefer NSE/BSE, AMFI, company filings and official announcements; secondary websites are cross-checks. Respect provider licensing, rate limits and terms of service. Maintain retries, caching, error states and deterministic fallback to last verified data with an explicit STALE label. **Never fabricate missing values, prices, announcements, financial statements or signals.**

## 3. COMPANY RESEARCH PAGE: REQUIRED PANELS
Build clear responsive sections with meaningful loading/error/empty states:

### A. Company identity and price summary
Display company name, symbol, ISIN, exchange, sector, market cap, small-cap classification, EOD close, previous close, day % change, 52-week range, as-of timestamp and source links. Show a large badge for data quality (VERIFIED / PRELIMINARY / STALE / INSUFFICIENT).

### B. Price chart and technical study
Show an interactive daily candlestick/line chart with configurable 1M, 3M, 6M and 1Y ranges; overlay 9 EMA, 20 EMA, 50 EMA and 200 SMA, plus volume and marked support/resistance, entry, stop and targets. For unsupported history, render N/A instead of an invented indicator.

Compute and explain:
- EMA 9, EMA 20, EMA 50, SMA 200, crossover state and slopes.
- RSI(14), MACD(12,26,9), ADX(14), ATR(14), 20/60-day high/low, trend structure and relative strength versus Nifty Smallcap 250.
- Volume versus 20-day average, accumulation/distribution (if valid), breakout, pullback, support/resistance, volatility and potential false-breakout risk.
- Signal rationale in plain language: what is bullish, bearish or neutral, and why.

**Do not treat 20 candles as sufficient for a reliable 50 EMA, 200 SMA, 52-week range, backtest or overall trade recommendation.** Indicators need adequate warm-up history; report calculation windows and limitations.

### C. Fundamentals and valuation
Table with latest value, previous comparator, YoY/3Y trend, benchmark/peer comparison, interpretation, source and reporting period for revenue growth, net profit growth, EPS, margins, ROE, ROCE, debt/equity, coverage, operating cash flow, P/E, P/B and EV/EBITDA. Avoid applying non-financial-company leverage/return thresholds mechanically to banks/NBFCs.

### D. Promoters, institutions, governance
Show promoter holding and pledging, shareholding trends, FII/DII shifts and auditor/regulatory observations. Highlight material red flags and distinguish confirmed facts from suspicion.

### E. News, catalysts and risk flags
Provide dated linked announcements, potential 1–2 month catalysts, upcoming key events, dilution/corporate actions, exchange surveillance flags, liquidity issues, price circuits and overall market/sector risk. Classify each as POSITIVE, NEUTRAL or NEGATIVE with justification.

### F. Opportunity score and confidence
Retain the prior 100-point scoring rubric:
- Fundamentals 20
- Technical strength 25
- Volume/liquidity 15
- Valuation 10
- News/catalysts 15
- Governance/risk quality 10
- Market/sector alignment 5

Provide category-level subscores, evidence and `score / 100`. Score thresholds: >=85 high-conviction *candidate*, 75–84 candidate, 60–74 watchlist, <60 avoid. **A score is not a calibrated probability of profit.** Any hard-exclusion rule overrides the score. If major evidence is missing, lower confidence and return WAIT rather than assigning optimistic defaults.

### G. Concrete 1–2 month trade plan
Use the configured holding period. Show:
- Current close and preferred BUY entry range (or breakout trigger), if any.
- Identified support and resistance and the technical setup.
- Hard stop-loss price and why it invalidates the thesis.
- Target 1 and Target 2 supported by observed levels or explicit calculation.
- Gross/net potential gain and downside in ₹ and %, net reward:risk after estimated brokerage, taxes, slippage and spread.
- Risk-based quantity for a configurable capital amount; default risk per trade <=1% and stock allocation <=10% according to existing fixed criteria.
- Expected holding window, time-stop rule, exit triggers and next evaluation date.
- Gap-down, circuit and illiquidity warning: stop-loss is not guaranteed execution.

If a valid, cost-adjusted reward:risk of at least 2:1 is impossible, do not recommend BUY. Do not invent targets simply to meet this threshold.

## 4. FINAL VERDICT: EXACTLY ONE OF BUY / SELL / HOLD / WAIT
The result must always conclude with one decision, in a large obvious card, followed by short evidence-based reasons.

**Position context:** accept `positionStatus = NONE | HELD | UNKNOWN` and, if held, optional quantity and cost basis. If the user has not entered a position, assume NONE for action semantics; do not pretend to know their holdings. A SELL verdict is an exit recommendation **only for an existing position**. A HOLD verdict is to retain an existing position. If no position is held, use BUY or WAIT.

### BUY (new position)
Only when all of the following hold:
- positionStatus is NONE;
- all hard exclusion checks pass and core price/liquidity/financial/surveillance data is sufficiently current;
- score >=75/100 and technical setup is positively confirmed;
- entry trigger, stop and targets are calculable;
- net reward:risk >=2:1;
- market/sector context and near-term catalysts do not invalidate the setup.
Output the specific entry trigger; if trigger is not yet met, verdict must be WAIT, not premature BUY.

### SELL (existing position)
Use when `positionStatus = HELD` and a material sell condition occurs, such as:
- stop-loss/thesis-invalidation level broken;
- severe verified governance or trading-restriction issue;
- the planned time stop/exit criterion is met;
- a confirmed adverse event materially changes the investment case.
Explain the trigger and whether the exit is immediate or conditional; do not claim a market order will fill at the displayed price.

### HOLD (existing position)
Use when `positionStatus = HELD`, the investment thesis remains intact, no defined exit trigger has fired, and current reward/risk supports keeping the position. Show an updated stop, targets, watch conditions and next review date.

### WAIT (no entry, uncertainty or missing evidence)
Use when `positionStatus = NONE` and a BUY trigger is absent, or critical data is delayed/insufficient, or the valuation/technical setup/risk is unattractive. Also use WAIT for `positionStatus = UNKNOWN` unless the user explicitly clarifies holdings. Give the exact condition that would change WAIT to BUY, or explain why this share is unsuitable.

**Mandatory:** never output BUY because the stock is 'cheap' or because RSI alone is low. Never force a BUY from a high score. Never label an unheld stock SELL. One verdict per symbol; no ambiguous `BUY/HOLD` hybrid labels.

## 5. FINAL DISPLAY FORMAT (REQUIRED ORDER)
1. **VERDICT: BUY / SELL / HOLD / WAIT** — prominent, with confidence HIGH / MEDIUM / LOW, data as-of time, and `Position: NONE | HELD | UNKNOWN`.
2. **Top 3 reasons** — exactly why this verdict was selected.
3. **Company snapshot** — identity, price, market cap, sector, classification.
4. **Technical summary** — trends, indicators, volume and support/resistance.
5. **Financial health** — growth, profitability, leverage, cash flow and valuation.
6. **Shareholding, governance and restrictions** — source-linked evidence.
7. **News and upcoming catalysts** — with dates.
8. **Risk and opportunity score** — 100-point breakdown and hard-exclusion flags.
9. **Action plan** — entry, target 1, target 2, stop-loss, potential upside/downside, net R:R, holding period, next review; show N/A when not justified.
10. **Sources and limitations** — linked primary sources, freshness, missing data, assumptions.

Required compact concluding language:

> **FINAL DECISION: [BUY | SELL | HOLD | WAIT]**
> **Why:** [Three factual reasons]
> **Action:** [Specific actionable condition or exit/hold instruction]
> **Entry / Stop / Target 1 / Target 2:** [Verified-derived levels or N/A]
> **Data confidence:** [HIGH | MEDIUM | LOW], as of [date/time]

## 6. AUDITABLE BACKEND RESPONSE
Build Spring Boot endpoints/services that return structured, typed results with stable IDs, for example:

```json
{
  "exchange": "NSE",
  "symbol": "EXAMPLE",
  "isin": "EXAMPLE_ISIN",
  "dataAsOf": null,
  "retrievedAt": null,
  "dataStatus": "INSUFFICIENT",
  "positionStatus": "NONE",
  "decision": "WAIT",
  "confidence": "LOW",
  "score": null,
  "decisionReasons": ["Price history or required filings are not sufficiently verified"],
  "hardExclusionFlags": [],
  "technical": {},
  "fundamentals": {},
  "ownershipAndGovernance": {},
  "newsAndCatalysts": [],
  "tradePlan": {
    "entry": null,
    "stopLoss": null,
    "target1": null,
    "target2": null,
    "netRewardRisk": null
  },
  "sources": [],
  "missingFields": [],
  "nextEvaluationTrigger": "Recalculate after sufficient verified data is available"
}
```

The values above are a **schema example only, not a real stock signal**. Define DTOs/enums, validations, sources/audit metadata and service-layer tests. Ensure the UI and export exactly match the backend's decision and calculation. Persist a timestamped research snapshot so past verdicts can be reproduced.

## 7. QUALITY AND ACCEPTANCE TESTS
- No AI service or manually supplied ticker is needed to discover and research candidates.
- Selecting a discovered stock opens a populated Research page; Refresh recomputes from current verified data.
- Historical candles and financial results are accurately dated and never forward-filled into a fictitious live quote.
- If only 20 sessions are available, unavailable long-period indicators remain N/A and verdict is WAIT where appropriate.
- Corporate actions are correctly accounted for in technical indicators.
- Tests cover bullish breakout, untriggered breakout, bearish trend, stop loss hit, stale sources, suspended/ASM/GSM securities, missing fundamentals, inadequate liquidity, open and absent positions, 2:1 net R:R boundary, and no-buy conditions.
- SELL and HOLD are never recommended without a confirmed held-position context.
- Every final verdict is attributable to explicit, logged rules, inputs and sources.
- No trading orders are executed automatically as a side effect of research. Execution requires a separate, explicitly authorized component.

## 8. IMPORTANT EXAMPLE FROM EXISTING UI
The current Research screenshot for **PC JEWELLER LIMITED (PCJEWELLER)** displays a preliminary dataset with only **20/20 sessions**, close ₹14.05, EMA 9 approximately ₹14.93, EMA 20 approximately ₹15.37, RSI(14) approximately 31.55, and a negative daily change. These are **sample page observations, not independently reverified current prices or a trading recommendation**. The new implementation must not conclude BUY from such sparse evidence or infer a complete 1–2 month trade thesis without additional verified history, fundamentals, trading-status checks and risk analysis.

**DELIVERABLE:** Implement full company-level research with source citations, all panels, deterministic backend scoring and a conspicuous final **BUY / SELL / HOLD / WAIT** verdict, including actionable entry/exit conditions and honest limitations.
