package com.aaruchalet.quant.backtest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** New history client / cache / controller must be creatable without configured broker secrets. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HistorySpringContextTest {
    @Autowired private AngelOneHistoricalClient client;
    @Autowired private HistoricalMarketDataService service;
    @Autowired private AngelOneBacktestController controller;

    @Test void createsAllReadOnlyHistoryBeans() {
        assertNotNull(client);
        assertNotNull(service);
        assertNotNull(controller);
    }
}
