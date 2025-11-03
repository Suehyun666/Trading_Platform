package com.hts.order.service.order;

import com.hts.order.proto.OrderProto;

public record OrderResponseDto(
        long orderId,
        OrderProto.OrderStatus status,
        String message
) {
    public OrderProto.OrderResponse toProto() {
        return OrderProto.OrderResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus(status)
                .setMessage(message)
                .setTimestamp(System.currentTimeMillis())
                .build();
    }
}
