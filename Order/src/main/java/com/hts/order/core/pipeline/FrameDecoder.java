package com.hts.order.core.pipeline;

import com.hts.order.core.exception.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * 프레임 디코더 (Sharable X)
 *
 * 앞 4바이트(프레임 길이) 기준으로 정확히 자르기
 *
 * 프레임 구조:
 * [4B frameLen][24B Header][N Payload]
 */
public class FrameDecoder extends LengthFieldBasedFrameDecoder {
    private static final int MAX_FRAME_LENGTH = 1_048_576; // 1MB
    private static final int LENGTH_FIELD_OFFSET = 0;
    private static final int LENGTH_FIELD_LENGTH = 4;
    private static final int LENGTH_ADJUSTMENT = 0;
    private static final int INITIAL_BYTES_TO_STRIP = 4; // 길이 필드 제거

    public FrameDecoder() {
        super(
            MAX_FRAME_LENGTH,
            LENGTH_FIELD_OFFSET,
            LENGTH_FIELD_LENGTH,
            LENGTH_ADJUSTMENT,
            INITIAL_BYTES_TO_STRIP
        );
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf frame) throws Exception {
        try {
            return super.decode(ctx, frame);
        } catch (Exception e) {
            throw new ProtocolException("Frame decode error", null, ProtocolException.DECODE_ERROR);
        }
    }
}
