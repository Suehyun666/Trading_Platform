package com.hts.order.shard;

/**
 * Symbol을 Shard ID로 매핑하는 전략 인터페이스
 *
 * 구현체:
 * - ModuloShardSelector: 단순 해시 모듈로 (균등 분산 보장)
 * - ConsistentShardSelector: Consistent Hashing (노드 추가/제거 시 유리)
 */
public interface ShardSelector {
    /**
     * Symbol을 논리 샤드 ID로 매핑
     *
     * @param symbol 종목 코드 (e.g. "AAPL", "TSLA")
     * @return 논리 샤드 ID (0~15)
     */
    int selectBySymbol(String symbol);

    /**
     * 논리 샤드 개수 반환
     */
    int getShardCount();
}
