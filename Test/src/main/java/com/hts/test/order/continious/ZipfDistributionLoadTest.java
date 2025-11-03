package com.hts.test.order.continious;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ZipfDistributionLoadTest extends AbstractLoadTest {

    // 심볼 및 가중치 정의: AAPL 40%, MSFT 20%, NVDA 10%, TSLA 5%, GOOG 5%, 나머지 10개 2%씩 (총 100%)
    private static final String[] WEIGHTED_SYMBOLS = createWeightedSymbolList();
    private static final int clientCount = 500;
    private static final long startAccountId = 2000;

    // 가중치에 따라 심볼 배열을 미리 생성하여 O(1) 시간에 랜덤 심볼을 뽑을 수 있도록 준비
    private static String[] createWeightedSymbolList() {
        List<String> list = new ArrayList<>();
        // 비율 설정: 40, 20, 10, 5, 5, (나머지 10개 * 2) = 총 100
        addSymbolNTimes(list, "AAPL", 40);
        addSymbolNTimes(list, "MSFT", 20);
        addSymbolNTimes(list, "NVDA", 10);
        addSymbolNTimes(list, "TSLA", 5);
        addSymbolNTimes(list, "GOOG", 5);
        // 나머지 10개 심볼에 각각 2%씩 (20%)
        String[] others = {"AMZN", "META", "AMD", "NFLX", "INTC", "ORCL", "IBM", "BABA", "NKE", "DIS"};
        for (String symbol : others) {
            addSymbolNTimes(list, symbol, 2);
        }
        return list.toArray(new String[0]);
    }

    private static void addSymbolNTimes(List<String> list, String symbol, int count) {
        for (int i = 0; i < count; i++) {
            list.add(symbol);
        }
    }

    @Override
    protected String selectSymbol() {
        // 미리 가중치가 반영된 배열에서 랜덤 선택
        return WEIGHTED_SYMBOLS[ThreadLocalRandom.current().nextInt(WEIGHTED_SYMBOLS.length)];
    }

    public static void main(String[] args) throws Exception {
        new ZipfDistributionLoadTest().runTest(clientCount, startAccountId);
    }
}