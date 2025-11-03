package com.hts.order.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import javax.inject.Singleton;

public final class ConfigModule extends AbstractModule {

    @Provides
    @Singleton
    Config provideConfig() {
        return ConfigFactory.load();
    }
}
