package com.hts.order.shard;

import javax.inject.Singleton;

/**
 * 단순 해시 모듈로 기반 샤드 셀렉터
 *
 * - 장점: 완벽한 균등 분산 (symbol.hashCode() % N)
 * - 단점: 샤드 개수 변경 시 전체 재분배
 *
 * 고정된 샤드 개수(16)에서는 Consistent Hashing보다 우수
 */
@Singleton
public final class ModuloShardSelector implements ShardSelector {
    private static final int SHARD_COUNT = 16;

    @Override
    public int selectBySymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return 0; // fallback
        }

        // String.hashCode()는 음수 가능 → Math.abs() + 모듈로
        return Math.abs(symbol.hashCode()) % SHARD_COUNT;
    }

    @Override
    public int getShardCount() {
        return SHARD_COUNT;
    }
}
