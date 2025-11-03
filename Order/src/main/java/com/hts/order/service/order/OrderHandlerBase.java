package com.hts.order.service.order;

import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.global.ResponseUtil;
import com.hts.order.metrics.MetricsCollector;
import com.hts.order.repository.SessionRepository;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import org.slf4j.Logger;

/**
 * Order Handler 공통 로직 추상화
 * - Session 검증
 * - 메트릭 기록
 * - 에러 응답 처리
 */
abstract class OrderHandlerBase {
    protected final SessionRepository sessionRepository;
    protected final MetricsCollector metrics;
    protected final Logger log;

    protected OrderHandlerBase(SessionRepository sessionRepository,
                              MetricsCollector metrics,
                              Logger log) {
        this.sessionRepository = sessionRepository;
        this.metrics = metrics;
        this.log = log;
    }

    protected Long validateSessionAndGetAccountId(long sessionId, long correlationId) {
        long t1 = System.nanoTime();
        Long accountId = sessionRepository.getAccountId(sessionId);
        long sessionLookupMs = (System.nanoTime() - t1) / 1_000_000;

        if (accountId == null) {
            log.warn("corrId={} [FAIL] Invalid session: sessionId={}, sessionLookup={}ms",
                     correlationId, sessionId, sessionLookupMs);
        }

        return accountId;
    }

    protected void sendInvalidSessionError(Channel channel, PacketHeader header, short methodId) {
        metrics.recordOrderRequest(methodId, "INVALID_SESSION");
        ResponseUtil.sendError(channel, header, 401, "Invalid session");
    }

    protected void sendInternalError(Channel channel, PacketHeader header, short methodId) {
        metrics.recordOrderRequest(methodId, "ERROR");
        ResponseUtil.sendError(channel, header, 500, "Internal order error");
    }

    protected void recordError(Exception e, long correlationId) {
        log.error("corrId={} Operation failed", correlationId, e);
        metrics.recordError(e.getClass().getSimpleName());
    }

    protected Timer.Sample startTimer() {
        return metrics.startTimer();
    }

    protected void recordLatency(Timer.Sample sample, short methodId) {
        metrics.recordOrderLatency(sample, methodId);
    }
}
