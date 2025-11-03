package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class RedisConfig {
    private final Config config;

    @Inject
    public RedisConfig(Config config) {
        this.config = config.getConfig("redis");
    }

    public String getHost() {
        return config.getString("host");
    }

    public int getPort() {
        return config.getInt("port");
    }

    public String getPassword() {
        return config.hasPath("password") && !config.getIsNull("password")
                ? config.getString("password")
                : null;
    }

    public int getDatabase() {
        return config.getInt("database");
    }
}
