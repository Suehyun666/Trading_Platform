package com.hts.order.core.pipeline;

import com.hts.order.core.exception.ServerException;
import com.hts.order.core.protocol.CommonsProto;
import com.hts.order.core.protocol.Frame;
import com.hts.order.core.protocol.PacketHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public final class ExceptionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ExceptionHandler.class);

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ServerException ex) {
            log.warn("WebSocket error: {}", ex.getMessage(), ex);
            sendError(ctx, ex);
            if (ex.shouldCloseConnection())
                ctx.close();
        } else {
            log.error("Unexpected error", cause);
            ctx.close();
        }
    }

    /**
     * ErrorMessage 전송
     */
    private void sendError(ChannelHandlerContext ctx, ServerException ex) {
        CommonsProto.ErrorMessage msg = CommonsProto.ErrorMessage.newBuilder()
                .setCode(ex.getErrorCode())
                .setMessage(ex.getMessage())
                .build();

        byte[] bytes = msg.toByteArray();
        ByteBuf payload = ctx.alloc().buffer(bytes.length).writeBytes(bytes);

        PacketHeader header = ex.getHeader() != null ? ex.getHeader() : new PacketHeader();
        header.flags(PacketHeader.FLAG_ERROR);
        header.payloadLen(bytes.length);

        Frame frame = new Frame(header, payload);
        ByteBuf encoded = frame.encode(ctx.alloc());

        ctx.writeAndFlush(encoded).addListener(f -> encoded.release());
        frame.release();
    }
}

