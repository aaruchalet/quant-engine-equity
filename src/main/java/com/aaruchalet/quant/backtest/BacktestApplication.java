package com.aaruchalet.quant.backtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BacktestApplication {
    public static void main(String[] args) throws Exception {
        var context = SpringApplication.run(BacktestApplication.class, args);
        if (context.getEnvironment().getProperty("trading.preflight-only",Boolean.class,false)) {
            try (context) { context.getBean(TradingRuntime.class).preflight(); }
        }
    }
}
