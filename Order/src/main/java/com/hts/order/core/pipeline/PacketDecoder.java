package com.hts.order.core.pipeline;

import com.hts.order.core.exception.ProtocolException;
import com.hts.order.core.protocol.Frame;
import com.hts.order.core.protocol.PacketHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 패킷 헤더 디코더 (Sharable 가능)
 *
 * ByteBuf(24B Header + Payload) → Frame
 */
@ChannelHandler.Sharable
public class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(PacketDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {
        if (buf.readableBytes() < PacketHeader.HEADER_SIZE)
            throw new ProtocolException("Invalid packet size", null, ProtocolException.DECODE_ERROR);

        PacketHeader header = PacketHeader.decode(buf);
        int payloadLen = header.getPayloadLen();

        if (buf.readableBytes() != payloadLen)
            throw new ProtocolException("Payload length mismatch", header, ProtocolException.DECODE_ERROR);

        ByteBuf payload = buf.readRetainedSlice(payloadLen);
        out.add(new Frame(header, payload));
    }
}
