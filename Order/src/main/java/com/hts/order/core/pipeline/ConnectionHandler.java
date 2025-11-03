package com.hts.order.core.pipeline;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public final class ConnectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConnectionHandler.class);

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("Connection established: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("Connection closed: {}", ctx.channel().remoteAddress());
        // Note: Channel attributes는 Netty가 자동 정리
        // Session 삭제는 명시적 로그아웃 핸들러에서 처리
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Connection error: {}", cause.getMessage());
        ctx.fireExceptionCaught(cause);
    }
}


