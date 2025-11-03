package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class ServerConfig {
    private final Config config;

    @Inject
    public ServerConfig(Config config) {
        this.config = config.getConfig("server");
    }

    public int getPort() {
        return config.getInt("port");
    }

    public int getBossThreads() {
        return config.getInt("netty.boss-threads");
    }

    public int getWorkerThreads() {
        return config.getInt("netty.worker-threads");
    }

    public int getBlockingPoolThreads() {
        return config.getInt("blocking-pool.threads");
    }

    public int getBlockingPoolQueueSize() {
        return config.getInt("blocking-pool.queue-size");
    }
}
