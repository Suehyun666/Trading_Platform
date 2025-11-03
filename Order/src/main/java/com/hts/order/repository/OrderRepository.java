package com.hts.order.repository;

import com.hts.order.service.order.OrderEntity;
import org.jooq.DSLContext;
import org.jooq.Record;

import javax.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public final class OrderRepository {

    private final DSLContext dsl;

    @Inject
    public OrderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insertOrder(DSLContext tx, OrderEntity order) {
        tx.execute("""
            INSERT INTO orders(order_id, account_id, symbol, side, order_type, quantity, price, time_in_force, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        order.orderId(),
        order.accountId(),
        order.symbol(),
        order.side().name(),
        order.orderType().name(),
        order.quantity(),
        order.price(),
        order.timeInForce().name(),
        order.status().name());
    }

    public void insertOutbox(DSLContext tx, OrderEntity order, String eventType) {
        tx.execute("""
           INSERT INTO outbox(event_type, aggregate_id, payload, status)
           VALUES (?, ?, ?, 'PENDING')
        """, eventType, order.orderId(), order.serializeForOutbox());
    }

    public boolean markCancelRequested(DSLContext tx, long orderId, long accountId) {
        int rows = tx.execute("""
           UPDATE orders SET status = 'CANCEL_REQUESTED'
           WHERE order_id = ? AND account_id = ? AND status IN ('RECEIVED', 'ACCEPTED')
        """, orderId, accountId);
        return rows == 1;
    }

    /**
     * Get order amount (price * quantity) for unreserve
     * @return order amount or null if not found
     */
    public Long getOrderAmount(DSLContext tx, long orderId, long accountId) {
        Record record = tx.fetchOne("""
            SELECT price * quantity as amount
            FROM orders
            WHERE order_id = ? AND account_id = ?
        """, orderId, accountId);

        if (record == null) {
            return null;
        }
        return record.get("amount", Long.class);
    }

    /**
     * Get symbol by orderId (fallback용 - DispatchHandler에서 사용)
     *
     * ⚠️ 트랜잭션 외부 조회 - Fallback 경로에서만 사용 (느림 ~10ms)
     *
     * @return symbol or null if not found
     */
    public String getSymbolByOrderId(long orderId) {
        Record record = dsl.fetchOne("""
            SELECT symbol
            FROM orders
            WHERE order_id = ?
        """, orderId);

        if (record == null) {
            return null;
        }
        return record.get("symbol", String.class);
    }

    /**
     * Find order IDs older than cutoff date (cleanup용)
     *
     * OrderIndexCache 정리 스케줄러에서 사용
     * 오래된 주문의 Redis 캐시 삭제 대상 조회
     *
     * @param cutoff 기준 일시
     * @return old order IDs
     */
    public List<Long> findOrderIdsOlderThan(LocalDateTime cutoff) {
        return dsl.fetch("""
            SELECT order_id
            FROM orders
            WHERE created_at < ?
            ORDER BY created_at ASC
            LIMIT 1000
        """, cutoff)
        .stream()
        .map(r -> r.get("order_id", Long.class))
        .collect(Collectors.toList());
    }
}