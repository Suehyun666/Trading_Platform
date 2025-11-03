package com.hts.order;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.hts.order.core.ServerBootstrap;
import com.hts.order.metrics.MetricsReporter;
import com.hts.order.metrics.PrometheusHttpServer;
import com.hts.order.module.*;
import com.hts.order.scheduler.CacheCleanupScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Initializing application...");

        Injector injector = Guice.createInjector(
                new ConfigModule(),
                new DatabaseModule(),
                new ExecutorModule(),
                new NettyModule(),
                new RedisModule(),
                new MetricsModule(),
                new ServiceModule()
        );

        ServerBootstrap server = injector.getInstance(ServerBootstrap.class);
        MetricsReporter metricsReporter = injector.getInstance(MetricsReporter.class);
        PrometheusHttpServer prometheusServer = injector.getInstance(PrometheusHttpServer.class);
        CacheCleanupScheduler cacheCleanupScheduler = injector.getInstance(CacheCleanupScheduler.class);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            cacheCleanupScheduler.shutdown();
            prometheusServer.stop();
            metricsReporter.stop();
            server.stop();
        }));

        prometheusServer.start();
        //metricsReporter.start();
        cacheCleanupScheduler.start();
        server.start();
    }
}