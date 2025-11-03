package com.hts.order.shard;

import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consistent Hash 기반 샤드 셀렉터
 *
 * - 논리 샤드 16개 (고정)
 * - 가상 노드 8개/샤드 (총 128개 포인트)
 * - FNV-1a 해시 (충돌률 최소화)
 *
 * 장점: 샤드 추가/제거 시 일부만 재분배
 * 단점: 완벽한 균등 분산 보장 안 됨 (일부 샤드에 편향 가능)
 */
@Singleton
public final class ConsistentShardSelector implements ShardSelector {
    private static final int SHARD_COUNT = 16;
    private static final int VIRTUALS_PER_SHARD = 8;

    private final TreeMap<Integer, Integer> ring = new TreeMap<>();

    public ConsistentShardSelector() {
        // 가상 노드 생성: 각 샤드당 8개
        for (int shard = 0; shard < SHARD_COUNT; shard++) {
            for (int v = 0; v < VIRTUALS_PER_SHARD; v++) {
                String virtualKey = "shard-" + shard + "-v" + v;
                int hash = fnv1aHash(virtualKey);
                ring.put(hash, shard);
            }
        }
    }

    @Override
    public int selectBySymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return 0; // fallback
        }

        int hash = fnv1aHash(symbol);

        // Consistent Hash: hash 이상의 첫 번째 가상 노드 찾기
        Map.Entry<Integer, Integer> entry = ring.ceilingEntry(hash);

        // Ring 끝까지 갔으면 처음으로
        if (entry == null) {
            entry = ring.firstEntry();
        }

        return entry.getValue(); // 0~15
    }

    /**
     * FNV-1a 32bit hash
     *
     * - prefix 유사 symbol (AAPL, AAPL1, AAPL2) 충돌 방지
     * - 성능: ~10ns (String.hashCode()와 동일)
     * - 분포: 충돌률 1% 미만
     *
     * @param key 해시할 문자열
     * @return 양수 해시값
     */
    private static int fnv1aHash(String key) {
        final int FNV_PRIME = 0x01000193;
        final int FNV_OFFSET = 0x811C9DC5;

        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int hash = FNV_OFFSET;

        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }

        return hash & 0x7FFFFFFF; // 양수 보장 (MSB 제거)
    }

    @Override
    public int getShardCount() {
        return SHARD_COUNT;
    }

    /**
     * 논리 샤드 개수 반환 (고정값) - 하위 호환성
     * @deprecated Use getShardCount() instead
     */
    @Deprecated
    public static int getLogicalShardCount() {
        return SHARD_COUNT;
    }

    /**
     * 가상 노드 개수 (디버깅용)
     */
    public int getVirtualNodeCount() {
        return ring.size();
    }
}
