package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class DatabaseConfig {
    private final Config config;

    @Inject
    public DatabaseConfig(Config config) {
        this.config = config.getConfig("database");
    }

    public String getHost() {
        return config.getString("host");
    }

    public int getPort() {
        return config.getInt("port");
    }

    public String getName() {
        return config.getString("name");
    }

    public String getUser() {
        return config.getString("user");
    }

    public String getPassword() {
        return config.getString("password");
    }

    public String getJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s", getHost(), getPort(), getName());
    }

    public int getMaximumPoolSize() {
        return config.getInt("hikari.maximum-pool-size");
    }

    public int getMinimumIdle() {
        return config.getInt("hikari.minimum-idle");
    }

    public long getConnectionTimeout() {
        return config.getLong("hikari.connection-timeout");
    }

    public long getIdleTimeout() {
        return config.getLong("hikari.idle-timeout");
    }

    public long getMaxLifetime() {
        return config.getLong("hikari.max-lifetime");
    }
}
