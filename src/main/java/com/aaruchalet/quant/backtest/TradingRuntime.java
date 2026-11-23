package com.aaruchalet.quant.backtest;

import static com.aaruchalet.quant.backtest.TradingJournal.*;
import static com.aaruchalet.quant.backtest.SmartMarket.*;
import static com.aaruchalet.quant.backtest.IndexOptionsReplay.*;
import com.aaruchalet.quant.backtest.BacktestReport.Side;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.DisposableBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

@Component
@EnableScheduling
class TradingRuntime implements DisposableBean {
    enum Mode { BACKTEST, PAPER, LIVE }
    private final Mode mode;
    private final IndexOptionsConfig options;
    private final VwapEmaV2Config strategy;
    private final TradingJournal journal;
    private final SmartMarket market;
    private final String account;
    private final int entryWindow, timeout;
    private final BigDecimal stopBuffer;
    private final Clock clock;
    private boolean preflightOnly;
    private Path pauseFile;
    private volatile boolean paperRunning;
    private String lastActivity;
    private LocalDateTime activitySlot;
    private LocalDate authenticated;
    private LocalDateTime evaluated;
    private LocalDateTime positionDiagnostic;
    private LocalDateTime lastRiskDiagnostic;
    private volatile String status = "Starting";
    private volatile Instant updated;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TradingRuntime.class);

    @org.springframework.beans.factory.annotation.Autowired
    TradingRuntime(@Value("${trading.mode}") String mode, JsonMapper json, AngelOneHistoricalClient client,
            @Value("${trading.strategy.options-config-path}") String opts, @Value("${trading.strategy.v2-config-path}") String strat,
            @Value("${trading.runtime.state-directory}") String dir, @Value("${trading.runtime.client-code}") String account,
            @Value("${trading.runtime.max-quote-age-seconds}") int age, @Value("${trading.preflight-only:false}") boolean preflightOnly, @Value("${trading.runtime.entry-window-seconds}") int entryWindow,
            @Value("${trading.runtime.order-timeout-seconds}") int timeout, @Value("${trading.runtime.stop-limit-buffer-percent}") BigDecimal buffer) throws Exception {
        this(Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT)),IndexOptionsConfig.load(SettingsResource.resolve(opts)),VwapEmaV2Config.fromYaml(SettingsResource.resolve(strat)),
                preflightOnly || "BACKTEST".equalsIgnoreCase(mode.strip()) ? null : new TradingJournal(Path.of(dir),mode.strip(),json),
                new SmartMarket(client,json,age),account,entryWindow,timeout,buffer,Clock.system(AngelOneHistoricalClient.IST));
        this.preflightOnly=preflightOnly;
        this.pauseFile=Path.of(dir).resolve("pause-entries");
        if (age < 1 || age > 300) throw new IllegalArgumentException("Invalid quote freshness limit");
    }

    TradingRuntime(Mode mode,IndexOptionsConfig options,VwapEmaV2Config strategy,TradingJournal journal,
                   SmartMarket market,String account,int entryWindow,int timeout,BigDecimal buffer,Clock clock) {
        this.mode=mode; this.options=options; this.strategy=strategy; this.journal=journal; this.market=market;
        this.account=account; this.entryWindow=entryWindow; this.timeout=timeout; this.stopBuffer=buffer; this.clock=clock;
        if (mode == Mode.LIVE && (account == null || account.isBlank())) throw new IllegalArgumentException("LIVE requires ANGEL_ONE_CLIENT_CODE");
        if (entryWindow < 1 || entryWindow >= 300 || timeout < 1 || buffer.signum()<=0 || buffer.compareTo(BigDecimal.valueOf(100))>=0)
            throw new IllegalArgumentException("Invalid trading runtime timing or stop-limit buffer");
        if (mode == Mode.PAPER) status="Ready to start paper trading";
        if (mode == Mode.BACKTEST) status="Historical simulation";
    }

    @Scheduled(fixedDelayString="${trading.runtime.poll-ms}")
    synchronized void poll() {
        if (mode == Mode.BACKTEST || preflightOnly) return;
        try {
            if (mode==Mode.PAPER && !paperRunning) {
                var state=journal.read();
                if(state.pending==null && state.quantity==0) return;
                // Reconcile existing exposure after restart or pause even when new entries are disabled.
            }
            tick(mode!=Mode.PAPER || paperRunning);
        }
        catch (Exception ex) {
            String detail = ex instanceof IllegalStateException || ex instanceof IOException || ex instanceof IllegalArgumentException
                    ? ex.getMessage() : ex.getClass().getSimpleName();
            String message="Runner waiting: " + detail;
            if (!message.equals(status)) LOG.warn("{}",message);
            activity("RUNNER_ERROR",message);
            status=message;
        } finally { updated=clock.instant(); }
    }

    synchronized Map<String,Object> paperControl(boolean running) {
        if(mode!=Mode.PAPER || preflightOnly || journal==null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Paper controls require PAPER execution mode");
        if(paperRunning!=running) {
            paperRunning=running;
            status=running?"Paper engine started; waiting for the next poll":"Paper entries paused; existing positions remain managed";
            activity("PAPER_CONTROL",status);
        }
        return Map.of("paperRunning",paperRunning,"status",status);
    }

    private void activity(String stage,String message) {
        var now=LocalDateTime.now(clock);
        var slot=now.withMinute(now.getMinute()/5*5).withSecond(0).withNano(0);
        if(slot.equals(activitySlot) && message.equals(lastActivity)) return;
        lastActivity=message;
        activitySlot=slot;
        for(Index idx:Index.values()) trace(idx,null,null,stage,message);
    }

    void tick() throws Exception { tick(true); }

    private void tick(boolean allowEntries) throws Exception {
        State s=journal.read();
        var now=LocalDateTime.now(clock);
        var date=now.toLocalDate(); var time=now.toLocalTime();
        if (time.isBefore(strategy.marketOpen()) || !time.isBefore(strategy.marketClose()) ||
                date.getDayOfWeek().getValue()>5) {
            status=s.quantity>0 || s.pending!=null ? "Session closed with unresolved exposure" : "Outside trading session"; activity("SESSION",status); return;
        }
        if (mode==Mode.LIVE && !date.equals(authenticated)) {
            if (!account.equalsIgnoreCase(field(market.request("user/v1/getProfile",null),"clientcode")))
                throw new IllegalStateException("Broker profile does not match configured account");
            if (s.account!=null && !account.equalsIgnoreCase(s.account)) throw new IllegalStateException("Journal belongs to a different account");
            authenticated=date;
        }
        if (!date.equals(s.day)) {
            if (s.day!=null && (s.quantity>0 || s.pending!=null || s.halted!=null)) {
                if (mode==Mode.LIVE) {
                    if (s.contract==null) throw new IllegalStateException("Missing contract for previous exposure");
                    ensureBrokerQuantity(s.contract,0);
                }
                journal.save("Prior session ended with incomplete fill history; daily totals unverified",s);
            }
            if (mode==Mode.LIVE) for (var row : book())
                if (field(row,"ordertag").startsWith("qe")) throw new IllegalStateException("Existing strategy orders without matching daily journal");
            s=new State(); s.day=date; s.account=account; journal.save("Session opened",s);
        }
        if (s.halted!=null) { status=s.halted; activity("HALTED",status); return; }
        var slot=now.withSecond(0).withNano(0).withMinute(now.getMinute()/5*5);
        boolean squareOff=!time.isBefore(strategy.squareOff());
        if (s.pending!=null) {
            if(mode==Mode.PAPER) finish(s,s.pending.quantity(),s.pending.price(),"complete");
            else reconcile(s,squareOff,slot);
            return;
        }
        if (s.quantity>0) { manage(s,squareOff,slot); return; }
        if(!allowEntries) return;
        if (paused() || s.entryPaused || squareOff || time.isBefore(strategy.entryStart()) || time.isBefore(strategy.strategyStart())
                || slot.toLocalTime().isAfter(strategy.lastEntry()) || s.entries>=strategy.maxTradesPerDay()
                || s.pnl.compareTo(BigDecimal.valueOf(-strategy.maxDailyLossInr()))<=0
                || s.pnl.compareTo(BigDecimal.valueOf(strategy.maxDailyProfitInr()))>=0) {
            status="No new entries under session/risk rules";
            if (!slot.equals(lastRiskDiagnostic)) {
                lastRiskDiagnostic=slot;
                String reason=paused() ? "OPERATOR_PAUSED" : s.entryPaused ? "ENTRY_PAUSED"
                        : squareOff ? "SQUARE_OFF" : time.isBefore(strategy.entryStart()) ? "BEFORE_ENTRY_START"
                        : slot.toLocalTime().isAfter(strategy.lastEntry()) ? "AFTER_LAST_ENTRY"
                        : s.entries>=strategy.maxTradesPerDay() ? "MAX_TRADES_REACHED"
                        : s.pnl.compareTo(BigDecimal.valueOf(-strategy.maxDailyLossInr()))<=0 ? "DAILY_LOSS_CUTOFF"
                        : s.pnl.compareTo(BigDecimal.valueOf(strategy.maxDailyProfitInr()))>=0 ? "DAILY_PROFIT_CUTOFF"
                        : "ENTRY_GATE_BLOCKED";
                for(Index idx:Index.values()) trace(idx,slot,null,"RISK_GATE",reason);
                if(mode==Mode.PAPER && paperRunning && slot.toLocalTime().isAfter(strategy.marketOpen())
                        && !slot.minusMinutes(5).toLocalTime().isAfter(strategy.squareOff()))
                    observeBlockedSlot(date,slot,reason);
            }
            return;
        }
        if (slot.equals(evaluated) || slot.equals(s.attempted)) return;
        if(Duration.between(slot,now).toSeconds()>entryWindow) {
            status="Waiting for next completed 5-minute candle at "+slot.plusMinutes(5).toLocalTime()+" IST";
            activity("CANDLE_WAIT",status);
            if(mode==Mode.PAPER && paperRunning && !slot.equals(lastRiskDiagnostic)) {
                lastRiskDiagnostic=slot;
                observeBlockedSlot(date,slot,"ENTRY_TIMING_WINDOW_CLOSED");
            }
            return;
        }
        activity("INSTRUMENTS","Checking instrument master for "+date);
        market.loadMaster(date);
        for (Index idx : Index.values()) {
            V2SignalState.Decision sig;
            try {
                trace(idx,slot,null,"CANDLE_CHECK","Reading completed index and ETF candles for EMA, RSI and session VWAP");
                var signal=market.signal(idx,slot,options,strategy);
                sig=signal.decision();
                trace(idx,slot,sig,"INDICATOR_CHECK",sig.reason(),signal);
            } catch (Exception failure) {
                trace(idx,slot,null,"DATA_UNAVAILABLE",
                        "Signal evaluation failed: "+failure.getClass().getSimpleName());
                throw failure;
            }
            if (sig.entry()==null) continue;
            Instrument spot=market.underlying(idx,options,false), proxy=market.underlying(idx,options,true);
            var underlying=market.quotes(spot,proxy);
            trace(idx,slot,sig,"QUOTE_CHECK","Spot "+underlying.get(spot).ltp()+" · ETF "+underlying.get(proxy).ltp()+" · VWAP "+sig.vwap());
            if (!sig.permitsOpen(underlying.get(proxy).ltp().doubleValue())) {
                trace(idx,slot,sig,"BLOCKED","Fresh ETF quote failed the final VWAP-side recheck");
                continue;
            }
            boolean call=sig.entry()==Side.LONG;
            var contract=market.select(idx,call?Right.CE:Right.PE,underlying.get(spot).ltp(),date,
                    call?options.callOffset():options.putOffset());
            if (contract==null) {
                trace(idx,slot,sig,"BLOCKED","No eligible listed option contract");
                continue;
            }
            BigDecimal available=options.dailyBudget().subtract(s.spent);
            if (mode==Mode.LIVE) {
                ensureBrokerQuantity(contract,0);
                available=available.min(decimal(market.request("user/v1/getRMS",null),"availablecash"));
            }
            var q=quote(contract);
            if (q.ask().signum()<=0 || q.bid().signum()<=0) {
                trace(idx,slot,sig,"BLOCKED","No valid two-sided option market");
                continue;
            }
            long qty=options.lots(idx,q.ask(),Math.min(q.bidSize(),q.askSize()),available)*idx.lotSize;
            trace(idx,slot,null,"SIZE_CHECK",contract.instrument().symbol()+" · bid "+q.bid()+" × "+q.bidSize()+" · ask "+q.ask()+" × "+q.askSize()+" · available ₹"+available+" · eligible units "+qty);
            if (qty==0) {
                trace(idx,slot,sig,"BLOCKED","Daily budget, broker cash, or executable depth cannot fund one lot");
                continue;
            }
            var submitAt=LocalDateTime.now(clock);
            if (paused() || Duration.between(slot,submitAt).toSeconds()>entryWindow || !submitAt.toLocalTime().isBefore(strategy.squareOff())) return;
            trace(idx,slot,sig,"ORDER_INTENT","Signal approved; "+(mode==Mode.PAPER?"simulated":"broker")+
                    " buy of "+(qty/idx.lotSize)+" lot(s) after final budget and depth checks");
            s.attempted=slot; s.contract=contract;
            submit(s,Action.BUY,qty,q.ask(),BigDecimal.ZERO);
            return;
        }
        evaluated=slot; status="Waiting for next qualifying candle";
    }

    private void observeBlockedSlot(LocalDate date,LocalDateTime slot,String reason) throws Exception {
        market.loadMaster(date);
        for(Index idx:Index.values()) {
            trace(idx,slot,null,"CANDLE_CHECK","Reading completed candles for observation; entry blocked by "+reason);
            try {
                var signal=market.signal(idx,slot,options,strategy);
                trace(idx,slot,signal.decision(),"OBSERVATION",signal.decision().reason()+" · entry blocked by "+reason,signal);
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch(Exception ex) {
                trace(idx,slot,null,"DATA_UNAVAILABLE","Candle observation failed: "+ex.getClass().getSimpleName());
            }
        }
    }

    private Quote quote(Contract c) throws Exception { return market.quotes(c.instrument()).get(c.instrument()); }

    private void manage(State s,boolean squareOff,LocalDateTime slot) throws Exception {
        Quote q;
        try {
            q=quote(s.contract);
            if (q.bid().signum()<=0 || q.bidSize()<=0) throw new IllegalStateException("Held contract has no executable bid");
            if (!slot.equals(positionDiagnostic)) {
                trace(s.contract.index(),null,null,"POSITION_CHECK","Bid "+q.bid()+" · stop "+s.stop+" · target "+s.target+" · units "+s.quantity);
                positionDiagnostic=slot;
            }
            if (s.exitReason==null) s.exitReason=exitReason(s,q,squareOff,slot);
        } catch (IOException | IllegalStateException unavailable) {
            if (mode!=Mode.LIVE || squareOff || s.exitReason!=null) throw unavailable;
            protect(s);
            return;
        }
        if (s.exitReason!=null) {
            long qty=Math.min(s.quantity,q.bidSize())/s.contract.index().lotSize*s.contract.index().lotSize;
            if (qty==0) throw new IllegalStateException("Insufficient exit bid quantity");
            if (mode==Mode.LIVE) ensureBrokerQuantity(s.contract,s.quantity);
            submit(s,Action.SELL,qty,q.bid(),BigDecimal.ZERO);
        } else if (mode==Mode.LIVE) protect(s);
        else status="Paper position open";
    }

    private void protect(State s) throws Exception {
        ensureBrokerQuantity(s.contract,s.quantity);
        var tick=s.contract.tick();
        var trigger=roundDown(s.stop,tick).max(tick);
        var limit=roundDown(trigger.multiply(BigDecimal.ONE.subtract(stopBuffer.movePointLeft(2))),tick).max(tick);
        submit(s,Action.STOP,s.quantity,limit,trigger);
    }

    private String exitReason(State s,Quote q,boolean squareOff,LocalDateTime slot) throws Exception {
        if (squareOff) return "SQUARE_OFF";
        if (q.bid().compareTo(s.stop)<=0) return "STOP_LOSS";
        if (q.bid().compareTo(s.target)>=0) return "TARGET";
        if (!strategy.exitOnEmaReversal() && !strategy.exitOnVwapInvalidation()) return null;
        market.loadMaster(slot.toLocalDate());
        var signal=market.signal(s.contract.index(),slot,options,strategy);
        var d=signal.decision(); boolean call=s.contract.right()==Right.CE;
        if (strategy.exitOnEmaReversal() && d.cross()==(call?Side.SHORT:Side.LONG)) return "EMA_REVERSAL";
        if (strategy.exitOnVwapInvalidation() && d.vwap()!=null && (call ? signal.lastProxyClose()<d.vwap():signal.lastProxyClose()>d.vwap()))
            return "VWAP_INVALIDATION";
        return null;
    }
    static BigDecimal roundDown(BigDecimal n, BigDecimal tick) { return n.divide(tick,0,RoundingMode.DOWN).multiply(tick); }

    private void submit(State s,Action action,long qty,BigDecimal price,BigDecimal trigger) throws Exception {
        var tag="qe"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        s.pending=new Order(action,tag,null,qty,price,trigger,clock.instant());
        if (mode==Mode.PAPER) { finish(s,qty,price,"complete"); return; }
        if (!account.equalsIgnoreCase(field(market.request("user/v1/getProfile",null),"clientcode")))
            throw new IllegalStateException("Broker account mismatch at order submission");
        journal.save("Submit intent " + action,s);
        var i=s.contract.instrument();
        var params=new LinkedHashMap<String,String>();
        params.put("variety",action==Action.STOP?"STOPLOSS":"NORMAL"); params.put("exchange",i.exchange());
        params.put("tradingsymbol",i.symbol()); params.put("symboltoken",i.token());
        params.put("transactiontype",action==Action.BUY?"BUY":"SELL"); params.put("producttype","INTRADAY");
        params.put("duration","DAY"); params.put("ordertype",action==Action.STOP?"STOPLOSS_LIMIT":"LIMIT");
        params.put("price",price.toPlainString()); params.put("quantity",String.valueOf(qty)); params.put("ordertag",tag);
        if (action==Action.STOP) params.put("triggerprice",trigger.toPlainString());
        String id=field(market.request(ORDER+"placeOrder",params),"orderid");
        if (id.isEmpty()) throw new IllegalStateException("Ambiguous submission; reconciling tag");
        s.pending=s.pending.identified(id); journal.save("Broker acknowledged " + action,s);
        status="Broker working " + action;
    }

    private JsonNode book() throws Exception {
        JsonNode data=market.request(ORDER+"getOrderBook",null);
        if (!data.isArray() && !data.isNull()) throw new IOException("Invalid order book response");
        return data;
    }
    private void ensureBrokerQuantity(Contract c,long expected) throws Exception {
        JsonNode data=market.request(ORDER+"getPosition",null);
        if (!data.isArray() && !data.isNull()) throw new IOException("Invalid position response");
        long qty=0;
        for (var row:data) if (field(row,"symboltoken").equals(c.instrument().token())
                && field(row,"exchange").equals(c.instrument().exchange()) && "INTRADAY".equals(field(row,"producttype")))
            qty=Math.addExact(qty,decimal(row,"netqty").longValueExact());
        for (var row:book())
            if (!terminal(field(row,"orderstatus").toLowerCase(Locale.ROOT))
                    && field(row,"symboltoken").equals(c.instrument().token())
                    && field(row,"exchange").equals(c.instrument().exchange()))
                throw new IllegalStateException("Another working order exists on this contract");
        if (qty!=expected) throw new IllegalStateException("Broker position differs from journal; reconcile before trading");
    }
    private static boolean terminal(String status) { return Set.of("complete","rejected","cancelled","canceled").contains(status); }

    private void reconcile(State s,boolean squareOff,LocalDateTime slot) throws Exception {
        Order p=s.pending;
        JsonNode matched=null;
        for (var row:book()) {
            boolean match=p.id()==null ? p.tag().equals(field(row,"ordertag")) : p.id().equals(field(row,"orderid"));
            if (!match) continue;
            if (matched!=null) throw new IllegalStateException("Ambiguous broker order match");
            matched=row;
        }
        if (matched==null) throw new IllegalStateException("Order acknowledgement unresolved; matching broker order required");
        var i=s.contract.instrument();
        if (!i.token().equals(field(matched,"symboltoken")) || !i.exchange().equals(field(matched,"exchange"))
                || !(p.action()==Action.BUY?"BUY":"SELL").equals(field(matched,"transactiontype"))
                || decimal(matched,"quantity").longValueExact()!=p.quantity() || !"INTRADAY".equals(field(matched,"producttype")))
            throw new IllegalStateException("Broker order identity mismatch");
        var orderStatus=field(matched,"orderstatus").toLowerCase(Locale.ROOT);
        long filled=decimal(matched,"filledshares").longValueExact();
        if (terminal(orderStatus)) {
            finish(s,filled,filled==0?BigDecimal.ZERO:decimal(matched,"averageprice"),orderStatus);
            if (p.action()==Action.BUY && s.quantity>0) manage(s,squareOff,slot);
            return;
        }
        boolean cancel=squareOff || filled>0;
        if (p.action()==Action.STOP) {
            if (s.exitReason==null && !squareOff) {
                var q=quote(s.contract);
                if (q.bid().signum()>0) {
                    String reason=exitReason(s,q,false,slot);
                    // Give the already-working stop a chance to execute at its limit.
                    if ("STOP_LOSS".equals(reason) && q.bid().compareTo(p.price())>=0) reason=null;
                    s.exitReason=reason;
                }
            }
            if (squareOff) s.exitReason="SQUARE_OFF";
            cancel |= s.exitReason!=null;
        } else {
            cancel=filled>0 || (p.action()==Action.BUY && (squareOff || paused()))
                    || Duration.between(p.created(),clock.instant()).getSeconds()>=timeout;
        }
        if (cancel && !orderStatus.contains("cancel pending")) {
            journal.save("Cancel requested " + p.action(),s);
            market.request(ORDER+"cancelOrder",Map.of("variety",p.action()==Action.STOP?"STOPLOSS":"NORMAL","orderid",field(matched,"orderid")));
        }
        status="Broker " + p.action() + " " + orderStatus;
    }

    private void finish(State s,long filled,BigDecimal price,String terminalStatus) throws Exception {
        var p=s.pending;
        if (filled<0 || filled%s.contract.index().lotSize!=0 || filled>p.quantity() || filled>0 && (price.signum()<=0 || p.action()!=Action.BUY && filled>s.quantity)
                || "complete".equals(terminalStatus) && filled!=p.quantity())
            throw new IllegalStateException("Inconsistent terminal broker fill");
        if (filled>0) {
            var gross=price.multiply(BigDecimal.valueOf(filled));
            var fee=options.fee(s.contract.index(),gross,p.action()==Action.BUY);
            s.pnl=s.pnl.subtract(fee);
            if (p.action()==Action.BUY) {
                s.spent=s.spent.add(gross).add(fee); s.entries++; s.average=price; s.quantity=filled;
                s.stop=price.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(strategy.stopPercent()).movePointLeft(2)));
                s.target=price.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(strategy.targetPercent()).movePointLeft(2)));
            } else {
                s.pnl=s.pnl.add(price.subtract(s.average).multiply(BigDecimal.valueOf(filled)));
                s.quantity-=filled;
                if (p.action()==Action.STOP) s.exitReason="STOP_LOSS";
            }
        }
        if ("rejected".equals(terminalStatus)) {
            s.entryPaused=true;
            if (p.action()==Action.STOP) s.exitReason="STOP_REJECTED";
            if (p.action()==Action.SELL) s.halted="Exit rejected; check and manage exposure in Angel One";
        }
        String event=p.action()+" "+terminalStatus+" "+filled+" @ "+price+" "+s.contract.instrument().symbol()
                +(s.exitReason==null?"":" "+s.exitReason);
        Index tradedIndex=s.contract.index();
        if (s.quantity==0) { s.contract=null; s.exitReason=null; }
        s.pending=null; journal.save(event,s);
        trace(tradedIndex,null,null,"ORDER_EVENT",event);
        status=event;
    }

    /** Read-only diagnostic events, written after the existing execution decisions; no order side effects. */
    private void trace(Index index,LocalDateTime slot,V2SignalState.Decision d,
                       String stage,String detail) {
        trace(index,slot,d,stage,detail,null);
    }
    private void trace(Index index,LocalDateTime slot,V2SignalState.Decision d,String stage,String detail,Signal signal) {
        if (journal==null) return;
        try {
        var item=new LinkedHashMap<String,Object>();
        item.put("at",clock.instant().toString());
        if(slot!=null) item.put("candleOpen",slot.minusMinutes(5).toString());
        if(signal!=null) {
            item.put("indexCandle",signal.indexCandle());
            item.put("proxyCandle",signal.proxyCandle());
        }
        item.put("stage",stage);
        item.put("detail",detail);
        if (d!=null) {
            item.put("ready",d.ready());
            item.put("ema9",d.emaFast());
            item.put("ema20",d.emaSlow());
            item.put("rsi14",d.rsi());
            item.put("sessionVwap",d.vwap());
            item.put("confirmedCrossover",d.cross()==null?"NONE":d.cross().name());
            item.put("candidate",d.entry()==null?"NONE":d.entry()==Side.LONG?"CALL":"PUT");
            item.put("emaGate",d.cross()!=null);
            item.put("rsiGate",d.cross()!=null && d.rsi()!=null
                    && (d.cross()==Side.LONG?d.rsi()>strategy.longRsiAbove():
                        strategy.shortsEnabled() && d.rsi()<strategy.shortRsiBelow()));
            item.put("vwapFinalGate",d.entry()!=null);
        }
        journal.trace(index,item);
        } catch (Exception ex) {
            // Observability failure must not add a new execution dependency.
            LOG.warn("Diagnostic storage failed for {}: {}",index,ex.getClass().getSimpleName());
        }
    }

    private boolean paused() { return pauseFile!=null && java.nio.file.Files.exists(pauseFile); }

    /** A one-shot, read-only check on the actual execution host. */
    void preflight() throws Exception {
        if (!preflightOnly) throw new IllegalStateException("Preflight-only launch required");
        if (account.isBlank() || !account.equalsIgnoreCase(field(market.request("user/v1/getProfile",null),"clientcode")))
            throw new IllegalStateException("Preflight failed: account mismatch");
        decimal(market.request("user/v1/getRMS",null),"availablecash"); book();
        market.request(ORDER+"getPosition",null);
        var now=LocalDateTime.now(clock);
        market.loadMaster(now.toLocalDate());
        boolean inSession=now.getDayOfWeek().getValue()<=5 && !now.toLocalTime().isBefore(strategy.marketOpen())
                && now.toLocalTime().isBefore(strategy.marketClose());
        for (Index idx : Index.values()) {
            var spot=market.underlying(idx,options,false);
            var etf=market.underlying(idx,options,true);
            if (inSession) {
                var quotes=market.quotes(spot,etf);
                for (Right right: Right.values()) {
                    var c=market.select(idx,right,quotes.get(spot).ltp(),now.toLocalDate(),
                            right==Right.CE?options.callOffset():options.putOffset());
                    if (c==null) throw new IllegalStateException("Preflight: missing option contract");
                    quote(c);
                }
                var slot=now.withMinute(now.getMinute()/5*5).withSecond(0).withNano(0);
                if (slot.toLocalTime().isAfter(strategy.marketOpen())) market.signal(idx,slot,options,strategy);
            }
        }
        LOG.info("Read-only preflight passed: profile, funds, order book, positions and index/ETF mappings. Session quote check: {}",inSession);
    }

    Map<String,Object> snapshot() throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("mode",mode); result.put("paperRunning",paperRunning); result.put("status",status); result.put("updated",updated);
        result.put("dailyBudget",options.dailyBudget()); result.put("maximumLots",options.maximumLots()==Integer.MAX_VALUE?"APPLICATION":options.maximumLots());
        if (journal!=null) {
            var s=journal.read(); result.put("day",s.day); result.put("spent",s.spent); result.put("quantity",s.quantity);
            result.put("contract",s.contract); result.put("pending",s.pending==null?null:s.pending.action());
            result.put("stop",s.stop); result.put("target",s.target); result.put("events",journal.recent());
        }
        return result;
    }
    /** Dedicated read-only NIFTY/SENSEX dashboard. Mode is controlled exclusively by startup YAML. */
    Map<String,Object> indexSnapshot(Index index) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("index",index.name());
        result.put("paperRunning",paperRunning);
        result.put("mode",mode.name());
        result.put("enabled",mode!=Mode.BACKTEST && !preflightOnly);
        result.put("status",mode==Mode.BACKTEST?"Trading inactive: set trading.mode to PAPER or LIVE in application.yml":status);
        result.put("updated",updated);
        result.put("signalInstrument",index==Index.NIFTY?options.niftySignal():options.sensexSignal());
        result.put("vwapInstrument",index==Index.NIFTY?options.niftyProxy():options.sensexProxy());
        result.put("lotSize",index.lotSize);
        result.put("maximumLots",options.maximumLots()==Integer.MAX_VALUE?"APPLICATION":options.maximumLots());
        result.put("dailyBudget",options.dailyBudget());
        var strategyDetails=new LinkedHashMap<String,Object>();
        strategyDetails.put("identifier","VWAP_BOUNCE_EMA_V2");
        strategyDetails.put("timeframe","5 minutes");
        strategyDetails.put("emaFast",strategy.fastEma());
        strategyDetails.put("emaSlow",strategy.slowEma());
        strategyDetails.put("rsiPeriod",strategy.rsiPeriod());
        strategyDetails.put("longRsiAbove",strategy.longRsiAbove());
        strategyDetails.put("shortRsiBelow",strategy.shortRsiBelow());
        strategyDetails.put("stopPercent",strategy.stopPercent());
        strategyDetails.put("targetPercent",strategy.targetPercent());
        strategyDetails.put("firstEntry",strategy.entryStart().toString());
        strategyDetails.put("lastEntry",strategy.lastEntry().toString());
        strategyDetails.put("squareOff",strategy.squareOff().toString());
        strategyDetails.put("maximumTradesPerDay",strategy.maxTradesPerDay());
        strategyDetails.put("dailyLossCutoff",strategy.maxDailyLossInr());
        strategyDetails.put("dailyProfitCutoff",strategy.maxDailyProfitInr());
        result.put("strategy",strategyDetails);
        if(journal!=null) {
            var state=journal.read();
            result.put("day",state.day);
            result.put("spent",state.spent);
            result.put("remainingBudget",options.dailyBudget().subtract(state.spent).max(BigDecimal.ZERO));
            result.put("dailyPnl",state.pnl);
            result.put("entries",state.entries);
            result.put("paused",paused() || state.entryPaused);
            result.put("halted",state.halted);
            boolean owns=state.contract!=null && state.contract.index()==index;
            result.put("position",owns?state.contract:null);
            result.put("quantity",owns?state.quantity:0);
            result.put("averagePremium",owns?state.average:null);
            result.put("stop",owns?state.stop:null);
            result.put("target",owns?state.target:null);
            result.put("pending",owns && state.pending!=null?state.pending.action():null);
            var now=LocalDateTime.now(clock);
            var gates=new LinkedHashMap<String,Object>();
            gates.put("tradingSession",now.getDayOfWeek().getValue()<=5 &&
                    !now.toLocalTime().isBefore(strategy.marketOpen()) && now.toLocalTime().isBefore(strategy.marketClose()));
            gates.put("entryWindow",!now.toLocalTime().isBefore(strategy.entryStart()) &&
                    !now.toLocalTime().isAfter(strategy.lastEntry()));
            gates.put("entriesAvailable",state.entries<strategy.maxTradesPerDay());
            gates.put("dailyLossNotReached",state.pnl.compareTo(BigDecimal.valueOf(-strategy.maxDailyLossInr()))>0);
            gates.put("dailyProfitNotReached",state.pnl.compareTo(BigDecimal.valueOf(strategy.maxDailyProfitInr()))<0);
            gates.put("sharedPositionAvailable",state.quantity==0 && state.pending==null);
            gates.put("entriesNotPaused",(mode!=Mode.PAPER || paperRunning) && !paused() && !state.entryPaused && state.halted==null);
            result.put("riskChecks",gates);
            result.put("evaluations",journal.traces(index));
        } else {
            result.put("riskChecks",Map.of());
            result.put("evaluations",List.of());
        }
        return result;
    }
    @Override public void destroy() throws Exception { if (journal!=null) journal.close(); }
}

@RestController
class RuntimeStatusController {
    private final TradingRuntime runtime;
    RuntimeStatusController(TradingRuntime runtime) { this.runtime=runtime; }
    @PostMapping(path="/api/trading/paper/start", consumes="application/json")
    Map<String,Object> startPaper() {return runtime.paperControl(true);}
    @PostMapping(path="/api/trading/paper/pause", consumes="application/json")
    Map<String,Object> pausePaper() {return runtime.paperControl(false);}
    @GetMapping("/api/trading/status")
    Map<String,Object> status() throws Exception { return runtime.snapshot(); }
    @GetMapping("/api/trading/indices/{index}")
    Map<String,Object> indexStatus(@PathVariable Index index) throws Exception { return runtime.indexSnapshot(index); }
}
