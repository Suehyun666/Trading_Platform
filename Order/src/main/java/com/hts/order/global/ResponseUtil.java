package com.hts.order.global;

import com.hts.order.core.protocol.CommonsProto;
import com.hts.order.core.protocol.Frame;
import com.hts.order.core.protocol.PacketHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

public final class ResponseUtil {
    private ResponseUtil() {}

    public static void sendOk(Channel channel, PacketHeader req, com.google.protobuf.Message proto) {
        byte[] bytes = proto.toByteArray();
        PacketHeader h = new PacketHeader()
                .frameType(PacketHeader.FRAME_TYPE_RESP)
                .serviceId(req.getServiceId())
                .methodId(req.getMethodId())
                .correlationId(req.getCorrelationId())
                .flags(PacketHeader.FLAG_NONE)
                .payloadLen(bytes.length);

        ByteBuf payload = channel.alloc().buffer(bytes.length).writeBytes(bytes);
        Frame frame = new Frame(h, payload);
        ByteBuf encoded = frame.encode(channel.alloc());
        frame.release();
        channel.writeAndFlush(encoded);
    }

    public static void sendError(Channel channel, PacketHeader req, int code, String msg) {
        CommonsProto.ErrorMessage err = CommonsProto.ErrorMessage
                .newBuilder().setCode(code).setMessage(msg).build();
        byte[] bytes = err.toByteArray();

        PacketHeader h = new PacketHeader()
                .frameType(PacketHeader.FRAME_TYPE_RESP)
                .serviceId(req.getServiceId())
                .methodId(req.getMethodId())
                .correlationId(req.getCorrelationId())
                .flags(PacketHeader.FLAG_ERROR)
                .payloadLen(bytes.length);

        ByteBuf payload = channel.alloc().buffer(bytes.length).writeBytes(bytes);
        Frame frame = new Frame(h, payload);
        ByteBuf encoded = frame.encode(channel.alloc());
        frame.release();
        channel.writeAndFlush(encoded);
    }
}
