package com.hts.test.order;

import com.hts.test.order.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SingleOrderTest {
    private static final Logger log = LoggerFactory.getLogger(SingleOrderTest.class);

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 8082;
        long accountId = 1000;

        log.info("Starting single order test for account {}", accountId);

        OrderClient client = new OrderClient(host, port, accountId);

        try {
            // Connect to server
            client.connect();
            log.info("Connected successfully");

            // Place order: 100 won = 10000 cents (price per unit)
            // Quantity = 1, Price = 10000 cents (total: 100 won)
            log.info("Placing order...");
            long startTime = System.nanoTime();

            OrderClient.ServerResponse response = client.placeOrder(
                    "AAPL",      // symbol
                    "BUY",       // side
                    1L,          // quantity
                    10000L       // price (100 won in cents)
            ).get();

            long endTime = System.nanoTime();
            long latencyMs = (endTime - startTime) / 1_000_000;

            log.info("========================================");
            log.info("Response received (latency: {}ms):", latencyMs);
            log.info("{}", response);

            if (response.isError()) {
                log.error("Order failed with error code {}: {}",
                        response.errorCode(), response.message());
            } else {
                log.info("Order placed successfully!");
                log.info("  CorrelationId: {}", response.correlationId());
                log.info("  OrderId: {}", response.orderId());
                log.info("  Status: {}", response.status());
                log.info("  Message: {}", response.message());
                log.info("  Total latency: {}ms", latencyMs);
            }
            log.info("========================================");

        } catch (Exception e) {
            log.error("Order failed with exception", e);
        } finally {
            client.disconnect();
            log.info("Disconnected");
        }
    }
}
