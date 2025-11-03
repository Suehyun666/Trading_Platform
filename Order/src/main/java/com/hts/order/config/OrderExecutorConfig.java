package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * OrderShardExecutor 설정
 *
 * - enabled: ORDER 샤딩 활성화 여부
 * - canaryPercent: Canary 배포 트래픽 비율 (0~100)
 * - logicalShards: 논리 샤드 개수 (16 고정)
 * - subWorkers: 샤드당 sub-worker 개수
 */
@Singleton
public final class OrderExecutorConfig {
    private final boolean enabled;
    private final int canaryPercent;
    private final int logicalShards;
    private final int subWorkers;

    @Inject
    public OrderExecutorConfig(Config config) {
        this.enabled = config.getBoolean("order-executor.enabled");
        this.canaryPercent = config.getInt("order-executor.canary-percent");
        this.logicalShards = config.getInt("order-executor.logical-shards");
        this.subWorkers = config.getInt("order-executor.sub-workers");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCanaryPercent() {
        return canaryPercent;
    }

    public int getLogicalShards() {
        return logicalShards;
    }

    public int getSubWorkers() {
        return subWorkers;
    }
}
