package com.aaruchalet.quant.backtest;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.aaruchalet.quant.backtest.IndexOptionsReplay.*;
import static com.aaruchalet.quant.backtest.SmartMarket.*;
import static com.aaruchalet.quant.backtest.TradingRuntime.Mode;

class TradingRuntimeTest {
    @TempDir Path dir;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    final ZoneId ist=ZoneId.of("Asia/Kolkata");
    final Clock clock=Clock.fixed(ZonedDateTime.of(2026,11,23,10,0,3,0,ist).toInstant(),ist);
    final IndexOptionsConfig cfg=IndexOptionsConfig.load(SettingsResource.resolve("classpath:/index-options.yml"));
    final VwapEmaV2Config strategy=VwapEmaV2Config.fromYaml(SettingsResource.resolve("classpath:/strategy-vwap-ema.example.yml"));
    TradingRuntimeTest() throws Exception {}

    class Exchange extends SmartMarket {
        final Contract contract=new Contract(Index.NIFTY,Right.CE,new Instrument("NFO","123","NIFTYTESTCE"),
                LocalDate.of(2026,11,25),n(25000),new BigDecimal("0.05"));
        int orders=0; boolean lostAck=false; String status="open"; long filled=0, brokerQty=0;
        BigDecimal bid=n(50), ask=n(50);
        Map<String,String> last=Map.of();
        Exchange() {super(null,TradingRuntimeTest.this.json,10);}
        @Override void loadMaster(LocalDate date) {}
        @Override Signal signal(Index index,LocalDateTime slot,IndexOptionsConfig options,VwapEmaV2Config s) {
            return new Signal(new V2SignalState.Decision(99d,BacktestReport.Side.LONG,BacktestReport.Side.LONG,true),100);
        }
        @Override Instrument underlying(Index index,IndexOptionsConfig cfg,boolean proxy) {
            return new Instrument("NSE",proxy?"3":"2",proxy?"ETF":"NIFTY 50");
        }
        @Override Map<Instrument,Quote> quotes(Instrument... req) {
            var quotes=new HashMap<Instrument,Quote>();
            for (var i:req) quotes.put(i,new Quote(n(100),bid,1000,ask,1000,LocalDateTime.now(clock)));
            return quotes;
        }
        @Override Contract select(Index i,Right r,BigDecimal spot,LocalDate date,int offset) {return contract;}
        @Override JsonNode request(String path,Object params) throws Exception {
            return switch (path) {
                case "user/v1/getProfile" -> json.valueToTree(Map.of("clientcode","TEST"));
                case "user/v1/getRMS" -> json.valueToTree(Map.of("availablecash","15000"));
                case "order/v1/getPosition" -> json.valueToTree(List.of(Map.of("symboltoken","123","exchange","NFO",
                        "producttype","INTRADAY","netqty",brokerQty)));
                case "order/v1/placeOrder" -> {
                    @SuppressWarnings("unchecked") var order=(Map<String,String>)params;
                    last=Map.copyOf(order); orders++; status="open"; filled=0;
                    if (lostAck) { lostAck=false; throw new java.io.IOException("Lost acknowledgement"); }
                    yield json.valueToTree(Map.of("orderid","id"+orders));
                }
                case "order/v1/cancelOrder" -> { status="cancelled"; yield json.valueToTree(Map.of()); }
                case "order/v1/getOrderBook" -> {
                    if (last.isEmpty()) yield json.valueToTree(List.of());
                    var row=new HashMap<>(last); row.put("orderid","id"+orders); row.put("orderstatus",status);
                    row.put("filledshares",""+filled); row.put("averageprice",last.get("price"));
                    yield json.valueToTree(List.of(row));
                }
                default -> throw new AssertionError(path);
            };
        }
    }
    static BigDecimal n(long n) {return BigDecimal.valueOf(n);}
    TradingRuntime runner(Mode mode,TradingJournal store,Exchange broker) {
        return new TradingRuntime(mode,cfg,strategy,store,broker,"TEST",45,20,n(1),clock);
    }

    @Test void startAndPauseControlTheSharedScheduledPaperLoop() throws Exception {
        var broker=new Exchange();
        try (var store=new TradingJournal(dir,"PAPER",json)) {
            var app=runner(Mode.PAPER,store,broker);
            app.poll();
            assertEquals(0,store.read().entries);
            app.paperControl(true);
            app.paperControl(true);
            app.poll();
            assertEquals(1,store.read().entries);
            assertEquals(true,app.indexSnapshot(Index.SENSEX).get("paperRunning"));
            assertTrue(store.traces(Index.NIFTY).stream().anyMatch(e->"INDICATOR_CHECK".equals(e.get("stage"))));
            app.paperControl(false);
            broker.bid=n(48);
            app.poll();
            app.poll();
            assertEquals(0,store.read().quantity);
            assertEquals(1,store.read().entries);
            assertEquals(0,broker.orders);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    ()->runner(Mode.LIVE,store,broker).paperControl(true));
        }
    }

    @Test void startAt1507ShowsRealCandleInputsEvenThoughSquareOffBlocksEntry() throws Exception {
        var at1507=Clock.fixed(LocalDate.of(2026,11,23).atTime(15,7).atZone(ist).toInstant(),ist);
        var broker=new Exchange() {
            @Override Signal signal(Index index,LocalDateTime slot,IndexOptionsConfig cfg,VwapEmaV2Config strategy) {
                var candle=new Candle(slot.minusMinutes(5),100,104,98,101,500);
                return new Signal(new V2SignalState.Decision(100d,null,null,true,101d,102d,45d,"WAIT_EMA_CROSSOVER"),101,candle,candle);
            }
        };
        try(var store=new TradingJournal(dir,"PAPER",json)) {
            var engine=new TradingRuntime(Mode.PAPER,cfg,strategy,store,broker,"TEST",45,20,n(1),at1507);
            engine.paperControl(true);engine.poll();engine.poll();
            for(var index:Index.values()) {
                var logs=store.traces(index);
                var observed=logs.stream().filter(e->"OBSERVATION".equals(e.get("stage"))).toList();
                assertEquals(1,observed.size());
                assertEquals("2026-11-23T15:00",observed.getFirst().get("candleOpen"));
                assertNotNull(observed.getFirst().get("proxyCandle"));
                assertTrue(observed.getFirst().get("detail").toString().contains("SQUARE_OFF"));
            }
            assertEquals(0,store.read().entries);assertEquals(0,broker.orders);
        }
    }

    @Test void paperSelectsFourAffordableLotsAndTradesWithoutOrderApi() throws Exception {
        var broker=new Exchange() {
            @Override JsonNode request(String path,Object p) {throw new AssertionError("Paper invoked private order/account API");}
        };
        try (var store=new TradingJournal(dir,"PAPER",json)) {
            var app=runner(Mode.PAPER,store,broker); app.tick();
            assertEquals(260,store.read().quantity);
            assertTrue(store.read().spent.compareTo(n(15000))<0);
            broker.bid=n(48); app.tick();
            assertEquals(0,store.read().quantity);
            assertTrue(store.read().pnl.signum()<0);
            app.tick(); assertEquals(1,store.read().entries);
        }
    }
    @Test void lostAckRestartPartialBuyAndProtectiveStopAreReconciledOnce() throws Exception {
        var broker=new Exchange(); broker.lostAck=true;
        try (var store=new TradingJournal(dir,"LIVE",json)) {
            assertThrows(java.io.IOException.class,() -> runner(Mode.LIVE,store,broker).tick());
            assertNotNull(store.read().pending); assertEquals(1,broker.orders);
        }
        try (var store=new TradingJournal(dir,"LIVE",json)) {
            var app=runner(Mode.LIVE,store,broker);
            broker.filled=65; app.tick(); // cancel outstanding buy, then read its terminal fill
            broker.brokerQty=65; app.tick(); assertEquals(65,store.read().quantity);
            assertEquals("STOPLOSS_LIMIT",broker.last.get("ordertype"));
            assertEquals("SELL",broker.last.get("transactiontype"));
            assertEquals("65",broker.last.get("quantity"));
            broker.status="complete"; broker.filled=65; broker.brokerQty=0; app.tick();
            assertEquals(0,store.read().quantity); assertEquals(1,store.read().entries);
            app.tick(); assertEquals(2,broker.orders);
        }
    }
    @Test void unknownOrderOrWrongAccountCannotTriggerSecondOrder() throws Exception {
        var broker=new Exchange();
        try (var store=new TradingJournal(dir,"LIVE",json)) {
            var app=runner(Mode.LIVE,store,broker); app.tick(); broker.last=Map.of();
            assertThrows(IllegalStateException.class,app::tick);
            assertEquals(1,broker.orders);
        }
    }
    @Test void secondSellWaitsForTerminalStopCancellationAndHandlesRacingFill() throws Exception {
        for (boolean racingFill:List.of(false,true)) {
            var broker=new Exchange();
            try (var store=new TradingJournal(dir.resolve(String.valueOf(racingFill)),"LIVE",json)) {
                var app=runner(Mode.LIVE,store,broker);
                app.tick(); broker.status="complete"; broker.filled=260; broker.brokerQty=260;
                app.tick(); assertEquals("STOPLOSS_LIMIT",broker.last.get("ordertype"));
                broker.bid=n(52); app.tick(); // target requests cancellation
                assertEquals(2,broker.orders);
                if (racingFill) {
                    broker.status="complete"; broker.filled=260; broker.brokerQty=0;
                }
                app.tick(); // capture terminal state before a replacement sell
                assertEquals(2,broker.orders);
                app.tick();
                assertEquals(racingFill ? 2 : 3,broker.orders);
                if (!racingFill) {
                    assertEquals("LIMIT",broker.last.get("ordertype"));
                    broker.status="complete"; broker.filled=260; broker.brokerQty=0;
                    app.tick();
                }
                assertEquals(0,store.read().quantity);
            }
        }
    }

    @Test void brokerCashBlocksEntryEvenWhenYamlBudgetWouldFit() throws Exception {
        var broker=new Exchange() {
            @Override JsonNode request(String path,Object params) throws Exception {
                return path.endsWith("getRMS") ? json.valueToTree(Map.of("availablecash","200")) : super.request(path,params);
            }
        };
        try (var store=new TradingJournal(dir,"LIVE",json)) {
            var app=runner(Mode.LIVE,store,broker); app.tick();
            assertEquals(0,broker.orders); assertEquals(0,store.read().entries);
        }
    }

    @Test void resolvesClosestExpiryAndListedStrikeWithPaiseConversion() {
        List<Map<String,String>> rows=new ArrayList<>();
        for (var expiry:List.of("25NOV2026","02DEC2026")) for (var strike:List.of(100,110)) {
            rows.add(Map.of("name","SENSEX","instrumenttype","OPTIDX","symbol","SENSEX"+strike+"PE",
                    "strike",""+strike*100,"tick_size","5","lotsize","20","expiry",expiry,"exch_seg","BFO","token","11"));
        }
        var c=SmartMarket.select(json.valueToTree(rows),Index.SENSEX,Right.PE,n(109),LocalDate.of(2026,11,23),-1);
        assertEquals(0,c.strike().compareTo(n(100))); assertEquals(LocalDate.of(2026,11,25),c.expiry());
        assertEquals(new BigDecimal("0.05"),c.tick());
    }
}
