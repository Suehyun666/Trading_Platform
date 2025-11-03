package com.hts.order.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 프레임 = 헤더(24B) + 페이로드(Protobuf)
 *
 * record 사용 (불변, compact)
 * 주의: payload는 참조 카운팅 객체이므로 사용 후 반드시 release() 호출
 */
public record Frame(PacketHeader header, ByteBuf payload) {

    /**
     * Compact constructor - validation
     */
    public Frame {
        if (header == null) {
            throw new IllegalArgumentException("header must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (header.getPayloadLen() != payload.readableBytes()) {
            throw new IllegalArgumentException(
                String.format(
                    "Payload length mismatch: header=%d actual=%d",
                    header.getPayloadLen(), payload.readableBytes()
                )
            );
        }
    }

    public ByteBuf encode(ByteBufAllocator alloc) {
        int payloadLen = payload.readableBytes();
        int totalLen = PacketHeader.HEADER_SIZE + payloadLen;

        ByteBuf buf = alloc.buffer(4 + totalLen);
        buf.writeInt(totalLen);
        header.encode(buf);
        buf.writeBytes(payload, payload.readerIndex(), payloadLen);
        return buf;
    }

    /**
     * ByteBuf 참조 카운트 해제 (필수!)
     */
    public void release() {
        payload.release();
    }

    /**
     * ByteBuf retain (참조 전달 시)
     */
    public Frame retain() {
        payload.retain();
        return this;
    }

    public long correlationId() {
        return header.getCorrelationId();
    }

    public short serviceId() {
        return header.getServiceId();
    }

    public short methodId() {
        return header.getMethodId();
    }

    public boolean isError() {
        return header.isError();
    }

    @Override
    public String toString() {
        return String.format(
            "Frame[%s payloadBytes=%d]",
            header, payload.readableBytes()
        );
    }
}
