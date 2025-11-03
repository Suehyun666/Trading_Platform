package com.hts.order.service.order;

/**
 * ORDER 요청 DTO의 타입 안전한 sealed interface
 *
 * - OrderShardExecutor에서 타입 안정성 보장
 * - 컴파일 타임에 허용된 타입만 사용 가능
 */
public sealed interface OrderDto permits OrderPlaceDto, OrderCancelDto {
    /**
     * 모든 Order 요청은 sessionId를 포함
     */
    long sessionId();
}
