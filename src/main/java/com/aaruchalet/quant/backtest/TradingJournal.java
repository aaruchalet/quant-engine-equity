package com.aaruchalet.quant.backtest;

import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import static com.aaruchalet.quant.backtest.SmartMarket.Contract;
import com.aaruchalet.quant.backtest.IndexOptionsReplay.Index;
import java.util.*;

/** Embedded, single-process H2 database. Commit the intent before contacting the broker. */
final class TradingJournal implements AutoCloseable {
    enum Action { BUY, STOP, SELL }
    record Order(Action action, String tag, String id, long quantity, BigDecimal price,
                 BigDecimal trigger, Instant created) {
        Order identified(String value) { return new Order(action,tag,value,quantity,price,trigger,created); }
    }
    static final class State {
        public LocalDate day;
        public String account, exitReason, halted;
        public LocalDateTime attempted;
        public BigDecimal spent = BigDecimal.ZERO, pnl = BigDecimal.ZERO;
        public int entries;
        public Contract contract;
        public long quantity;
        public BigDecimal average, stop, target;
        public Order pending;
        public boolean entryPaused;
    }

    private final Connection db;
    private final JsonMapper json;
    TradingJournal(Path directory,String mode,JsonMapper json) throws Exception {
        this.json=json;
        Files.createDirectories(directory);
        var path=directory.toRealPath().resolve(mode.toLowerCase(java.util.Locale.ROOT));
        if (path.toString().contains(";")) throw new IllegalArgumentException("Invalid state-directory");
        db=DriverManager.getConnection("jdbc:h2:file:" + path + ";WRITE_DELAY=0","sa","");
        try (var stmt=db.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS journal (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, recorded TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, event VARCHAR, snapshot CLOB)");
            stmt.execute("CREATE TABLE IF NOT EXISTS strategy_trace (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, recorded TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, market VARCHAR(8) NOT NULL, detail CLOB NOT NULL)");
            stmt.execute("CREATE INDEX IF NOT EXISTS ix_strategy_trace_market_id ON strategy_trace(market,id)");
        }
    }

    synchronized State read() throws SQLException {
        try (var stmt=db.createStatement(); var r=stmt.executeQuery("SELECT snapshot FROM journal ORDER BY id DESC LIMIT 1")) {
            return r.next() ? json.readValue(r.getString(1),State.class) : new State();
        }
    }
    synchronized void save(String event,State state) throws SQLException {
        try (var stmt=db.prepareStatement("INSERT INTO journal(event,snapshot) VALUES (?,?)")) {
            stmt.setString(1,event); stmt.setString(2,json.writeValueAsString(state)); stmt.executeUpdate();
        }
    }
    synchronized java.util.List<java.util.Map<String,Object>> recent() throws SQLException {
        var result = new java.util.ArrayList<java.util.Map<String,Object>>();
        try (var stmt=db.createStatement(); var r=stmt.executeQuery("SELECT recorded,event,snapshot FROM journal ORDER BY id DESC LIMIT 30")) {
            while (r.next()) {
                State s=json.readValue(r.getString(3),State.class);
                result.add(java.util.Map.of("time",r.getString(1),"event",r.getString(2),"quantity",s.quantity,
                        "spent",s.spent,"realizedPnl",s.pnl,"instrument",s.contract == null ? "" : s.contract.instrument().symbol()));
            }
        }
        return result;
    }
    /** Diagnostic history is stored separately from order intents, and never drives execution. */
    synchronized void trace(Index market, Map<String,Object> detail) throws Exception {
        try (var stmt = db.prepareStatement("INSERT INTO strategy_trace(market,detail) VALUES (?,?)")) {
            stmt.setString(1,market.name());
            stmt.setString(2,json.writeValueAsString(detail));
            stmt.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    synchronized List<Map<String,Object>> traces(Index market) throws Exception {
        var result = new ArrayList<Map<String,Object>>();
        try (var stmt = db.prepareStatement("SELECT recorded, detail FROM strategy_trace WHERE market=? ORDER BY id DESC LIMIT 60")) {
            stmt.setString(1,market.name());
            try (var rows = stmt.executeQuery()) {
                while (rows.next()) {
                    var item = new LinkedHashMap<String,Object>();
                    item.put("recorded",rows.getString(1));
                    item.putAll(json.readValue(rows.getString(2),Map.class));
                    result.add(item);
                }
            }
        }
        return result;
    }
    @Override public void close() throws SQLException { db.close(); }
}
