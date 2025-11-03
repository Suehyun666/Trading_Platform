package com.hts.order.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import javax.inject.Singleton;

public final class MetricsModule extends AbstractModule {

    @Provides
    @Singleton
    PrometheusMeterRegistry providePrometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Provides
    @Singleton
    MeterRegistry provideMeterRegistry(PrometheusMeterRegistry prometheusRegistry) {
        return prometheusRegistry;
    }
}
