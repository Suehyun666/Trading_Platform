package com.hts.account.utils;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BigDecimal 파싱 최적화 - 자주 사용되는 금액은 캐싱
 * (예: "100.00", "1000.00" 등)
 */
public class MoneyParser {

    private static final int CACHE_SIZE = 1024;
    private static final ConcurrentHashMap<String, BigDecimal> cache = new ConcurrentHashMap<>(CACHE_SIZE);

    /**
     * String → BigDecimal 변환 (캐싱)
     * 캐시 hit 시 new BigDecimal() 호출 생략
     */
    public static BigDecimal parse(String amount) throws NumberFormatException {
        if (amount == null || amount.isBlank()) {
            throw new NumberFormatException("Amount cannot be null or empty");
        }

        // ✅ 캐시 조회 (대부분 "100", "1000" 같은 round number)
        BigDecimal cached = cache.get(amount);
        if (cached != null) {
            return cached;
        }

        // ✅ 파싱 후 캐싱 (LRU 없이 단순 size check)
        BigDecimal value = new BigDecimal(amount);
        if (cache.size() < CACHE_SIZE) {
            cache.putIfAbsent(amount, value);
        }
        return value;
    }

    /**
     * 캐시 초기화 (테스트용)
     */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * 캐시 상태 확인
     */
    public static int getCacheSize() {
        return cache.size();
    }
}
