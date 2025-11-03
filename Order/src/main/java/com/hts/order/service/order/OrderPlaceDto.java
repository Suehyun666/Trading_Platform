package com.hts.order.service.order;

import com.hts.order.proto.OrderProto;

public record OrderPlaceDto(
        long sessionId,
        String symbol,
        OrderProto.Side side,
        OrderProto.OrderType orderType,
        long quantity,
        long price,
        OrderProto.TimeInForce timeInForce
) implements OrderDto {

    public static OrderPlaceDto fromProto(OrderProto.NewOrderRequest req) {
        return new OrderPlaceDto(
                req.getSecure().getSessionId(),
                req.getSymbol(),
                req.getSide(),
                req.getOrderType(),
                req.getQuantity(),
                req.getPrice(),
                req.getTimeInForce()
        );
    }
}
