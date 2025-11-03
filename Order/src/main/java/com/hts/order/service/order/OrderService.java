package com.hts.order.service.order;

import com.hts.order.cache.OrderIndexCache;
import com.hts.order.client.AccountGrpcClient;
import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.core.TransactionExecutor;
import com.hts.order.global.ResponseUtil;
import com.hts.order.metrics.BufferedLogger;
import com.hts.order.metrics.MetricsCollector;
import com.hts.order.metrics.SamplingLogger;
import com.hts.order.outbox.OrderOutboxQueue;
import com.hts.order.proto.OrderProto;
import com.hts.order.repository.OrderRepository;
import com.hts.order.repository.SessionRepository;
import com.hts.order.service.exception.ServiceException;
import com.hts.order.shard.OrderShardExecutor;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * ⚠️ 중요 제약사항:
 * - 이 클래스의 메서드는 반드시 OrderShardExecutor의 워커 스레드에서만 호출되어야 함
 * - Netty I/O 스레드나 blockingPool에서 직접 호출 금지
 * - 이유: gRPC 동기 호출로 인한 스레드 블로킹이 샤드별로 격리되어야 함
 * - 위반 시: gRPC 지연이 전체 서버로 전파되어 순서 보장 깨짐
 */
public class OrderService implements OrderShardExecutor.OrderTaskHandler {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    // 요청 100개마다 1번만 찍는 샘플링 로거
    private static final SamplingLogger slowLog = new SamplingLogger(log, 500);
    // 세부 타임라인은 600줄 쌓이면 한 번에 찍는 버퍼
    private static final BufferedLogger traceLog = new BufferedLogger(log);

    // 🌟 트레이스 샘플링 비율 (1/N) - 500개 요청마다 1개 트레이싱
    private static final int TRACE_SAMPLE_RATE = 500;

    // 🌟 ThreadLocal을 사용하여 워커 스레드별 요청 카운트 기록
    private final ThreadLocal<int[]> requestCounter = ThreadLocal.withInitial(() -> new int[1]);

    private final TransactionExecutor transactionExecutor;
    private final AccountGrpcClient accountClient;
    private final OrderRepository orderRepository;
    private final SessionRepository sessionRepository;
    private final OrderIndexCache orderIndexCache;
    private final OrderOutboxQueue outboxQueue;
    private final MetricsCollector metrics;

    public OrderService(
            TransactionExecutor transactionExecutor,
            AccountGrpcClient accountClient,
            OrderRepository orderRepository,
            SessionRepository sessionRepository,
            OrderIndexCache orderIndexCache,
            OrderOutboxQueue outboxQueue,
            MetricsCollector metrics) {
        this.transactionExecutor = transactionExecutor;
        this.accountClient = accountClient;
        this.orderRepository = orderRepository;
        this.sessionRepository = sessionRepository;
        this.orderIndexCache = orderIndexCache;
        this.outboxQueue = outboxQueue;
        this.metrics = metrics;
    }

    @Override
    public void handle(OrderShardExecutor.OrderTask task, long orderId) {
        int method = task.header().getMethodId();
        if (method == 1) {
            handlePlace(task, orderId);
        } else if (method == 2) {
            handleCancel(task);
        } else {
            throw new ServiceException("Unknown method", task.header(), 400);
        }
    }


    /**
     * 주문 접수 처리
     * @param task DispatchHandler에서 샤드 라우팅된 작업
     * @param orderId 워커별 generator에서 생성된 주문 ID
     */
    private void handlePlace(OrderShardExecutor.OrderTask task, long orderId) {
        OrderPlaceDto dto = (OrderPlaceDto) task.dto();
        PacketHeader header = task.header();
        Channel channel = task.channel();

        long startTime = System.nanoTime();
        Timer.Sample sample = metrics.startTimer();
        long correlationId = header.getCorrelationId();

        // ------------------------- 🌟 핵심 변경 로직: 트레이싱 활성화 -------------------------
        boolean traceEnabled = false;
        int[] count = requestCounter.get();
        count[0]++;
        if (count[0] >= TRACE_SAMPLE_RATE) {
            count[0] = 0;
            traceLog.setTracing(true); // 현재 워커 스레드에서 트레이싱 시작
            traceEnabled = true;
        }
        // ---------------------------------------------------------------------------------

        // 🌟 isTracingEnabled()를 사용하여 로그 기록 여부를 확인
        if (traceEnabled) {
            traceLog.add("corrId=" + correlationId + " [0.START] Symbol=" + dto.symbol() +
                    ", shard=" + task.shardId() + ", subKey=" + task.subKey());
        }

        slowLog.info("corrId={} [START] PlaceOrder sessionId={}", correlationId, dto.sessionId());
        try {
            // 1. Session validation & get accountId
            long t1 = System.nanoTime();
            Long accountId = sessionRepository.getAccountId(dto.sessionId());
            long sessionLookupMs = (System.nanoTime() - t1) / 1_000_000;

            if (accountId == null) {
                log.warn("corrId={} [FAIL] Invalid session: sessionId={}, sessionLookup={}ms",
                         correlationId, dto.sessionId(), sessionLookupMs);
                metrics.recordOrderRequest(header.getMethodId(), "INVALID_SESSION");
                ResponseUtil.sendError(channel, header, 401, "Invalid session");
                return;
            }
            traceLog.add("corrId=" + correlationId + " [1.SESSION] accountId=" + accountId +
                    ", lookup=" + sessionLookupMs + "ms");

            // 2. OrderId는 이미 워커에서 생성됨 (lock-free)
            int shardId = task.shardId();
            traceLog.add("corrId=" + correlationId + " [2.ID_GEN] orderId=" + orderId +
                    ", shardId=" + shardId + ", gen=0ms (pre-generated)");

            // 3. Calculate total cost (price * quantity)
            long totalCost = dto.price() * dto.quantity();
            BigDecimal reserveAmount = BigDecimal.valueOf(totalCost);

            // 4. Reserve funds via gRPC call to Account service
            long t3 = System.nanoTime();

            boolean reserved = accountClient.reserve(accountId, reserveAmount);
            // boolean reserved = true;
            long grpcMs = (System.nanoTime() - t3) / 1_000_000;

            if (!reserved) {
                log.warn("corrId={} [FAIL] Reserve failed: accountId={}, orderId={}, cost={}, grpc={}ms",
                        correlationId, accountId, orderId, totalCost, grpcMs);
                ResponseUtil.sendError(channel, header, 400, "Insufficient balance");
                metrics.recordOrderRequest(header.getMethodId(), "INSUFFICIENT_BALANCE");
                return;
            }
            traceLog.add("corrId=" + correlationId + " [3.GRPC] ok accountId=" + accountId +
                    ", amount=" + reserveAmount + ", grpc=" + grpcMs + "ms");

            // 5. 비동기 DB 저장 (Outbox Queue)
            OrderEntity order = OrderEntity.from(dto, orderId, accountId);
            boolean queued = outboxQueue.offer(order);

            if (!queued) {
                // 큐 풀 - 예약 해제 후 에러 응답
                log.error("corrId={} Outbox queue full, releasing reserve: accountId={}, orderId={}",
                         correlationId, accountId, orderId);
                //accountClient.unreserve(accountId, reserveAmount);
                ResponseUtil.sendError(channel, header, 503, "Server overloaded");
                metrics.recordOrderRequest(header.getMethodId(), "QUEUE_FULL");
                return;
            }

            // ✅ Redis 인덱싱 (즉시, fallback용)
            orderIndexCache.index(orderId, dto.symbol());

            // 6. 즉시 응답 반환 (DB 커밋 대기 X)
            OrderResponseDto response = new OrderResponseDto(
                    orderId,
                    OrderProto.OrderStatus.RECEIVED,
                    "Order received"
            );
            ResponseUtil.sendOk(channel, header, response.toProto());
            metrics.recordOrderRequest(header.getMethodId(), "OK");

        } catch (Exception e) {
            log.error("corrId={} Order placement failed", correlationId, e);
            metrics.recordError(e.getClass().getSimpleName());
            metrics.recordOrderRequest(header.getMethodId(), "ERROR");
            ResponseUtil.sendError(channel, header, 500, "Internal order error");
        } finally {
            if (traceEnabled) {
                traceLog.flushAndClear();
            }
            long endTime = System.nanoTime();
            double latencyMs = (endTime - startTime) / 1_000_000.0;
            slowLog.info("corrId={} [END] PlaceOrder {}ms", correlationId, String.format("%.2f", latencyMs));
            metrics.recordOrderLatency(sample, header.getMethodId());
        }
    }
    /**
     * 주문 취소 처리
     * @param task DispatchHandler에서 샤드 라우팅된 작업
     */
    private void handleCancel(OrderShardExecutor.OrderTask task) {
        OrderCancelDto dto = (OrderCancelDto) task.dto();
        PacketHeader header = task.header();
        Channel channel = task.channel();

        Timer.Sample sample = metrics.startTimer();
        long correlationId = header.getCorrelationId();

        slowLog.info("corrId={} [START] CancelOrder sessionId={}", correlationId, dto.sessionId());
        try {
            // 1. Session validation & get accountId
            Long accountId = sessionRepository.getAccountId(dto.sessionId());
            if (accountId == null) {
                log.warn("corrId={} Invalid session: sessionId={}", correlationId, dto.sessionId());
                metrics.recordOrderRequest(header.getMethodId(), "INVALID_SESSION");
                ResponseUtil.sendError(channel, header, 401, "Invalid session");
                return;
            }
            traceLog.add("corrId=" + correlationId + " [1.SESSION] accountId=" + accountId);

            // 2. DB Transaction: get order amount & mark cancel requested
            Timer.Sample dbSample = metrics.startTimer();
            Long orderAmount = transactionExecutor.execute(tx -> {
                // Get order amount for unreserve
                Long amount = orderRepository.getOrderAmount(tx, dto.orderId(), accountId);
                if (amount == null) {
                    log.warn("corrId={} Order not found: orderId={}, accountId={}",
                            correlationId, dto.orderId(), accountId);
                    return null;
                }

                // Mark cancel requested (atomic check + update)
                boolean marked = orderRepository.markCancelRequested(tx, dto.orderId(), accountId);
                if (!marked) {
                    log.warn("corrId={} Cancel failed: orderId={}, accountId={} (already filled or cancelled)",
                            correlationId, dto.orderId(), accountId);
                    return null;
                }

                // TODO: Insert outbox for gateway cancel request
                // orderRepository.insertCancelOutbox(tx, dto);

                log.info("corrId={} Cancel requested: orderId={}, accountId={}, amount={}",
                        correlationId, dto.orderId(), accountId, amount);
                return amount;
            });
            metrics.recordDbTxDuration(dbSample, header.getServiceId());

            // 3. Unreserve funds if cancel succeeded
            if (orderAmount != null) {
                BigDecimal unreserveAmount = BigDecimal.valueOf(orderAmount);
                boolean unreserved = accountClient.unreserve(accountId, unreserveAmount);
                // boolean unreserved = true;

                traceLog.add("corrId=" + correlationId + " [2.UNRESERVE] ok=" + unreserved + " amount=" + unreserveAmount);
                if (!unreserved) {
                    log.error("corrId={} Unreserve failed after cancel: orderId={}, accountId={}, amount={}",
                            correlationId, dto.orderId(), accountId, orderAmount);
                    // TODO: 보상 트랜잭션 또는 재시도 로직 필요
                }

                OrderResponseDto response = new OrderResponseDto(
                        dto.orderId(),
                        OrderProto.OrderStatus.CANCEL_REQUESTED,
                        "Cancel request received"
                );
                ResponseUtil.sendOk(channel, header, response.toProto());
                metrics.recordOrderRequest(header.getMethodId(), "OK");
            } else {
                ResponseUtil.sendError(channel, header, 404, "Order not found or cannot be cancelled");
                traceLog.add("corrId=" + correlationId + " [FAIL] cancel rejected");
                metrics.recordOrderRequest(header.getMethodId(), "NOT_FOUND");
            }

        } catch (Exception e) {
            log.error("corrId={} Order cancel failed", correlationId, e);
            metrics.recordError(e.getClass().getSimpleName());
            metrics.recordOrderRequest(header.getMethodId(), "ERROR");
            ResponseUtil.sendError(channel, header, 500, "Internal order error");
        } finally {
            slowLog.info("corrId={} [END] CancelOrder", correlationId);
            metrics.recordOrderLatency(sample, header.getMethodId());
        }
    }
    // 주문 정정 - 추후 구현
    private void amend(Channel channel, OrderDto dto){}
}


//# 5) 비동기/동기, 응답·로그 순서의 원칙
//
//* **DB 트랜잭션 동기** (필수 구간)
//* **Outbox 커밋 이후 클라 응답** (동기)
//* **게이트웨이 발행 비동기** (재시도/배압 대응)
//* **게이트웨이 응답 이벤트 수신 → 상태 업데이트** (비동기)
//* **신뢰 로그(WAL/감사로그)** : 트랜잭션 내 append. 운영 로그는 비동기.