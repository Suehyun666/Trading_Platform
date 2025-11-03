package com.hts.test.order.concurrent;

public final class ConcurrentSingleSymbolTest extends AbstractConcurrentOrderTest {

    private static final String FIXED_SYMBOL = "AAPL";

    @Override
    protected String selectSymbol() {
        // 단일 종목 고정: Exchange Engine의 최대 경합 부하 테스트
        return FIXED_SYMBOL;
    }

    public static void main(String[] args) throws Exception {
        new ConcurrentSingleSymbolTest().runTest();
    }
}
