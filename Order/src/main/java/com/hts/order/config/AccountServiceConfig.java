package com.hts.order.config;

import com.typesafe.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class AccountServiceConfig {
    private final Config config;

    @Inject
    public AccountServiceConfig(Config config) {
        this.config = config.getConfig("account-service");
    }

    public String getHost() {
        return config.getString("host");
    }

    public int getPort() {
        return config.getInt("port");
    }
}
