package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class MetricsConfig {
    private final int port;

    @Inject
    public MetricsConfig(Config config) {
        this.port = config.getInt("metrics.port");
    }

    public int getPort() {
        return port;
    }
}
