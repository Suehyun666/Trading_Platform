package com.hts.test.order.concurrent;

import java.util.concurrent.ThreadLocalRandom;

public final class ConcurrentEqualDistributionTest extends AbstractConcurrentOrderTest {

    // 16개의 심볼 리스트 (샤딩 키가 될 심볼의 개수만큼)
    private static final String[] SYMBOLS = {
            "AAPL", "MSFT", "GOOG", "AMZN", "TSLA", "NVDA", "META", "AMD",
            "NFLX", "INTC", "ORCL", "IBM", "BABA", "NKE", "DIS", "QCOM"
    };

    @Override
    protected String selectSymbol() {
        // 16개 심볼 중 랜덤 선택 (균등 분산)
        return SYMBOLS[ThreadLocalRandom.current().nextInt(SYMBOLS.length)];
    }

    public static void main(String[] args) throws Exception {
        new ConcurrentEqualDistributionTest().runTest();
    }
}