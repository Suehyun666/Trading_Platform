package com.hts.order.service;

import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.service.order.OrderDto;
import io.netty.channel.Channel;


public interface Handler {
    void handle(Channel channel, PacketHeader header, OrderDto dto);
}