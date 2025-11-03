package com.hts.order.service.order;

import com.hts.order.proto.OrderProto;

public record OrderEntity(
        long orderId,
        long accountId,
        String symbol,
        OrderProto.Side side,
        OrderProto.OrderType orderType,
        long quantity,
        long price,
        OrderProto.TimeInForce timeInForce,
        OrderProto.OrderStatus status
) {
    public static OrderEntity from(OrderPlaceDto dto, long orderId, long accountId) {
        return new OrderEntity(
                orderId,
                accountId,
                dto.symbol(),
                dto.side(),
                dto.orderType(),
                dto.quantity(),
                dto.price(),
                dto.timeInForce(),
                OrderProto.OrderStatus.RECEIVED
        );
    }

    public byte[] serializeForOutbox() {
        return String.format(
                "{\"orderId\":%d,\"accountId\":%d,\"symbol\":\"%s\",\"side\":\"%s\",\"quantity\":%d,\"price\":%d}",
                orderId, accountId, symbol, side, quantity, price
        ).getBytes();
    }
}
