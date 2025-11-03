package com.hts.order.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.hts.order.config.ServerConfig;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;

import javax.inject.Named;
import javax.inject.Singleton;

public final class NettyModule extends AbstractModule {

    @Provides
    @Singleton
    @Named("bossGroup")
    EventLoopGroup provideBossGroup(ServerConfig config) {
        return new EpollEventLoopGroup(config.getBossThreads());
    }

    @Provides
    @Singleton
    @Named("workerGroup")
    EventLoopGroup provideWorkerGroup(ServerConfig config) {
        return new EpollEventLoopGroup(config.getWorkerThreads());
    }
}
