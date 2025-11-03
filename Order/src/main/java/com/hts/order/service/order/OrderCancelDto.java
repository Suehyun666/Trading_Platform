package com.hts.order.service.order;

import com.hts.order.proto.OrderProto;

public record OrderCancelDto(
        long sessionId,
        long orderId
) implements OrderDto {

    public static OrderCancelDto fromProto(OrderProto.CancelOrderRequest req) {
        return new OrderCancelDto(
                req.getSecure().getSessionId(),
                req.getOrderId()
        );
    }
}
