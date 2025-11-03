package com.hts.order.metrics;

import com.hts.order.config.MetricsConfig;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Prometheus metrics HTTP endpoint
 *
 * GET /metrics → Prometheus 스크랩 포맷 응답
 */
@Singleton
public class PrometheusHttpServer {
    private static final Logger log = LoggerFactory.getLogger(PrometheusHttpServer.class);

    private final PrometheusMeterRegistry registry;
    private final int port;
    private HttpServer server;

    @Inject
    public PrometheusHttpServer(PrometheusMeterRegistry registry, MetricsConfig config) {
        this.registry = registry;
        this.port = config.getPort();
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            server.createContext("/metrics", exchange -> {
                String response = registry.scrape();
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });

            server.setExecutor(null); // Default executor
            server.start();

            log.info("Prometheus HTTP order started on port {}", port);
            log.info("Metrics available at http://localhost:{}/metrics", port);

        } catch (IOException e) {
            log.error("Failed to start Prometheus HTTP order on port {}", port, e);
            throw new RuntimeException("Failed to start Prometheus HTTP order", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Prometheus HTTP order stopped");
        }
    }
}
