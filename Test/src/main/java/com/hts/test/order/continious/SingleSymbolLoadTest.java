package com.hts.test.order.continious;

public final class SingleSymbolLoadTest extends AbstractLoadTest {

    private static final String FIXED_SYMBOL = "AAPL";
    private static final int clientCount = 500;
    private static final long startAccountId = 1;

    @Override
    protected String selectSymbol() {
        return FIXED_SYMBOL; // 단일 종목 고정
    }

    public static void main(String[] args) throws Exception {
        new SingleSymbolLoadTest().runTest(clientCount, startAccountId);
    }
}