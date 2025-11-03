package com.hts.order.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.hts.order.config.ServerConfig;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ExecutorModule extends AbstractModule {

    @Provides
    @Singleton
    @Named("blockingPool")
    ExecutorService provideBlockingPool(ServerConfig config) {
        int threads = config.getBlockingPoolThreads();
        int queueSize = config.getBlockingPoolQueueSize();

        java.util.concurrent.atomic.AtomicInteger threadCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("blocking-" + threadCounter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
