package com.hts.order.cache;

import com.hts.order.repository.OrderRepository;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;

/**
 * OrderId → Symbol 인덱스 캐시 (Redis)
 *
 * 목적:
 * - 구 포맷 orderId나 extractShard() 실패 시 fallback
 * - CancelOrderRequest는 orderId만 있고 symbol 없음
 * - Redis에서 빠르게 symbol 조회 후 샤드 계산
 *
 * 운영 필수 사항:
 * - TTL 30일 (거래 취소 기간 충분히 커버)
 * - AOF 활성화 (재시작 시 데이터 유지)
 * - 월 1회 자동 cleanup (90일 이상 주문)
 */
@Singleton
public class OrderIndexCache {
    private static final Logger log = LoggerFactory.getLogger(OrderIndexCache.class);
    private static final int TTL_SECONDS = 86400 * 30; // 30일

    private final RedisClient redisClient;
    private final OrderRepository orderRepository;

    @Inject
    public OrderIndexCache(RedisClient redisClient, OrderRepository orderRepository) {
        this.redisClient = redisClient;
        this.orderRepository = orderRepository;
    }

    /**
     * orderId → symbol 인덱싱 (주문 생성 시)
     *
     * @param orderId 생성된 주문 ID
     * @param symbol 종목 코드
     */
    public void index(long orderId, String symbol) {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisCommands<String, String> sync = connection.sync();
            String key = "order:" + orderId;
            sync.setex(key, TTL_SECONDS, symbol);
        } catch (Exception e) {
            // Redis 장애 시에도 주문 처리는 계속 진행
            log.error("Failed to index orderId={} to Redis", orderId, e);
        }
    }

    /**
     * orderId → symbol 조회 (취소 시 fallback)
     *
     * @param orderId 주문 ID
     * @return symbol or null (캐시 미스 시)
     */
    public String getSymbol(long orderId) {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisCommands<String, String> sync = connection.sync();
            String key = "order:" + orderId;
            return sync.get(key);
        } catch (Exception e) {
            log.error("Failed to get symbol for orderId={} from Redis", orderId, e);
            return null;
        }
    }

    /**
     * 주기적 cleanup (월 1회 권장)
     *
     * - 90일 이상 지난 주문의 Redis 캐시 삭제
     * - TTL로 자동 삭제되지만 명시적 cleanup으로 메모리 최적화
     *
     * Spring의 경우: @Scheduled(cron = "0 0 2 1 * ?") // 매월 1일 02:00
     * Quartz의 경우: CronTrigger 설정
     *
     * 현재는 수동 호출 방식 (스케줄러 통합 필요)
     */
    public void cleanupOldOrders() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(90);

            // DB에서 90일 이상 지난 주문 ID 조회
            List<Long> oldOrderIds = orderRepository.findOrderIdsOlderThan(cutoff);

            if (oldOrderIds.isEmpty()) {
                log.info("No old orders to clean up");
                return;
            }

            try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
                RedisCommands<String, String> sync = connection.sync();

                int deleted = 0;
                for (Long orderId : oldOrderIds) {
                    String key = "order:" + orderId;
                    Long result = sync.del(key);
                    if (result != null && result > 0) {
                        deleted++;
                    }
                }

                log.info("Cleaned up {} old order indices (total scanned: {})",
                         deleted, oldOrderIds.size());
            }

        } catch (Exception e) {
            log.error("Failed to cleanup old orders", e);
        }
    }

    /**
     * 캐시 상태 확인 (헬스체크용)
     *
     * @return Redis 연결 가능 여부
     */
    public boolean isHealthy() {
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisCommands<String, String> sync = connection.sync();
            String pong = sync.ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return false;
        }
    }
}
