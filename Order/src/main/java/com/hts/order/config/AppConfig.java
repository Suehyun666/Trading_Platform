package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 통합 Config 클래스 (6개 Config를 1개로 통합)
 * - 기존: RedisConfig, DatabaseConfig, AccountServiceConfig 등 6개 클래스
 * - 개선: 단일 AppConfig로 통합, 중복 제거
 */
@Singleton
public final class AppConfig {
    private final Config config;

    @Inject
    public AppConfig(Config config) {
        this.config = config;
    }

    // ==================== Server ====================
    public int getServerPort() {
        return config.getInt("server.port");
    }

    public int getNettyBossThreads() {
        return config.getInt("server.netty.boss-threads");
    }

    public int getNettyWorkerThreads() {
        return config.getInt("server.netty.worker-threads");
    }

    public int getBlockingPoolThreads() {
        return config.getInt("server.blocking-pool.threads");
    }

    public int getBlockingPoolQueueSize() {
        return config.getInt("server.blocking-pool.queue-size");
    }

    // ==================== Database ====================
    public String getDbHost() {
        return config.getString("database.host");
    }

    public int getDbPort() {
        return config.getInt("database.port");
    }

    public String getDbName() {
        return config.getString("database.name");
    }

    public String getDbUser() {
        return config.getString("database.user");
    }

    public String getDbPassword() {
        return config.getString("database.password");
    }

    public String getDbJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s", getDbHost(), getDbPort(), getDbName());
    }

    public int getDbMaxPoolSize() {
        return config.getInt("database.hikari.maximum-pool-size");
    }

    public int getDbMinIdle() {
        return config.getInt("database.hikari.minimum-idle");
    }

    public long getDbConnectionTimeout() {
        return config.getLong("database.hikari.connection-timeout");
    }

    public long getDbIdleTimeout() {
        return config.getLong("database.hikari.idle-timeout");
    }

    public long getDbMaxLifetime() {
        return config.getLong("database.hikari.max-lifetime");
    }

    // ==================== Redis ====================
    public String getRedisHost() {
        return config.getString("redis.host");
    }

    public int getRedisPort() {
        return config.getInt("redis.port");
    }

    public String getRedisPassword() {
        return config.hasPath("redis.password") && !config.getIsNull("redis.password")
                ? config.getString("redis.password")
                : null;
    }

    public int getRedisDatabase() {
        return config.getInt("redis.database");
    }

    // ==================== Account Service ====================
    public String getAccountServiceHost() {
        return config.getString("account-service.host");
    }

    public int getAccountServicePort() {
        return config.getInt("account-service.port");
    }

    // ==================== Order Executor ====================
    public boolean isOrderExecutorEnabled() {
        return config.getBoolean("order-executor.enabled");
    }

    public int getOrderExecutorCanaryPercent() {
        return config.getInt("order-executor.canary-percent");
    }

    public int getOrderExecutorLogicalShards() {
        return config.getInt("order-executor.logical-shards");
    }

    public int getOrderExecutorSubWorkers() {
        return config.getInt("order-executor.sub-workers");
    }

    // ==================== Metrics ====================
    public int getMetricsPort() {
        return config.getInt("metrics.port");
    }
}
