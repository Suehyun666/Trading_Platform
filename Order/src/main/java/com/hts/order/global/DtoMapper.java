package com.hts.order.global;

import com.google.protobuf.Message;
import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.proto.OrderProto;
import com.hts.order.service.exception.ServiceException;
import com.hts.order.service.order.OrderCancelDto;
import com.hts.order.service.order.OrderDto;
import com.hts.order.service.order.OrderPlaceDto;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class DtoMapper {
    private final Map<Long, Function<Message, OrderDto>> registry = new HashMap<>();

    @SuppressWarnings("unchecked")
    public DtoMapper() {
        register(PacketHeader.SERVICE_ORDER, (short) 1,
            msg -> OrderPlaceDto.fromProto((OrderProto.NewOrderRequest) msg));
        register(PacketHeader.SERVICE_ORDER, (short) 2,
            msg -> OrderCancelDto.fromProto((OrderProto.CancelOrderRequest) msg));
    }

    private void register(short serviceId, short methodId, Function<Message, OrderDto> converter) {
        long key = ((long) serviceId << 16) | (methodId & 0xFFFFL);
        registry.put(key, converter);
    }

    public OrderDto toDto(short serviceId, short methodId, Message message) {
        long key = ((long) serviceId << 16) | (methodId & 0xFFFFL);
        Function<Message, OrderDto> converter = registry.get(key);
        if (converter == null) {
            throw new ServiceException(
                    "Unknown message mapping: serviceId=" + serviceId + ", methodId=" + methodId,
                    null,
                    400
            );
        }
        return converter.apply(message);
    }
}
