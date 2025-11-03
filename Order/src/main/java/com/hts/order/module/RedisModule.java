package com.hts.order.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.hts.order.config.RedisConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

import javax.inject.Singleton;

public final class RedisModule extends AbstractModule {

    @Provides
    @Singleton
    RedisClient provideRedisClient(RedisConfig config) {
        RedisURI redisUri = RedisURI.Builder
                .redis(config.getHost(), config.getPort())
                .withDatabase(config.getDatabase())
                .build();

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            redisUri.setPassword(config.getPassword().toCharArray());
        }

        return RedisClient.create(redisUri);
    }
}
