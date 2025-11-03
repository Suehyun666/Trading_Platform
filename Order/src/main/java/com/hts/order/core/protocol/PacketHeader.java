package com.hts.order.core.protocol;

import com.hts.order.core.exception.ProtocolException;
import io.netty.buffer.ByteBuf;

/**
 * 24바이트 고정 패킷 헤더 (C++17 packed struct 호환)
 *
 * 구조:
 * - version (1B): 프로토콜 버전 (0x01)
 * - frameType (1B): 1=REQ, 2=RESP, 3=STREAM, 4=EVENT
 * - serviceId (2B): 서비스 ID (Auth=1, Order=2, MarketData=3...)
 * - methodId (2B): 메서드 ID (Login=1, PlaceOrder=2...)
 * - flags (2B): 압축, 암호화, 에러 등
 * - correlationId (8B): 요청-응답 매칭 ID
 * - seqNo (4B): Reserved (WebSocket용, 현재 0)
 * - payloadLen (4B): Payload 길이 (바이트)
 */
public final class PacketHeader {
    public static final int HEADER_SIZE = 24;
    private static final int MAX_FRAME_LENGTH = 1_048_576; // 1MB

    public static final byte VERSION = 0x01;

    // Frame Types
    public static final byte FRAME_TYPE_REQ = 1;
    public static final byte FRAME_TYPE_RESP = 2;
    public static final byte FRAME_TYPE_STREAM = 3;
    public static final byte FRAME_TYPE_EVENT = 4;

    // Service IDs
    public static final short SERVICE_AUTH = 1;
    public static final short SERVICE_ORDER = 2;
    public static final short SERVICE_MARKET_DATA = 3;

    // Flags
    public static final short FLAG_NONE = 0x0000;
    public static final short FLAG_ERROR = 0x0001;

    private byte version;
    private byte frameType;
    private short serviceId;
    private short methodId;
    private short flags;
    private long correlationId;
    private int seqNo;
    private int payloadLen;

    public PacketHeader() {
        this.version = VERSION;
        this.seqNo = 0; // Reserved
    }

    /**
     * ByteBuf에서 헤더 읽기 (24바이트 소비)
     */
    public static PacketHeader decode(ByteBuf buf) {
        if (buf.readableBytes() < HEADER_SIZE) {
            throw new IllegalArgumentException("Insufficient bytes for header: " + buf.readableBytes());
        }

        try {
            PacketHeader header = new PacketHeader();
            header.version = buf.readByte();
            header.frameType = buf.readByte();
            header.serviceId = buf.readShortLE();
            header.methodId = buf.readShortLE();
            header.flags = buf.readShortLE();
            header.correlationId = buf.readLongLE();
            header.seqNo = buf.readIntLE();
            header.payloadLen = buf.readIntLE();

            if (header.version != VERSION) {
                throw new ProtocolException("Invalid version: " + header.version, header, ProtocolException.DECODE_ERROR);
            }

            if (header.payloadLen < 0 || header.payloadLen > MAX_FRAME_LENGTH) {
                throw new ProtocolException("Invalid payloadLen: " + header.payloadLen, header, ProtocolException.DECODE_ERROR);
            }

            return header;
        } catch (IndexOutOfBoundsException e) {
            throw new ProtocolException("Malformed header (buffer underflow)", null, ProtocolException.DECODE_ERROR);
        }
    }

    /**
     * ByteBuf에 헤더 쓰기 (24바이트 추가)
     */
    public void encode(ByteBuf buf) {
        buf.writeByte(version);
        buf.writeByte(frameType);
        buf.writeShortLE(serviceId);
        buf.writeShortLE(methodId);
        buf.writeShortLE(flags);
        buf.writeLongLE(correlationId);
        buf.writeIntLE(seqNo);
        buf.writeIntLE(payloadLen);
    }

    // Builder-style setters
    public PacketHeader frameType(byte frameType) {
        this.frameType = frameType;
        return this;
    }

    public PacketHeader serviceId(short serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    public PacketHeader methodId(short methodId) {
        this.methodId = methodId;
        return this;
    }

    public PacketHeader flags(short flags) {
        this.flags = flags;
        return this;
    }

    public PacketHeader correlationId(long correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public PacketHeader payloadLen(int payloadLen) {
        this.payloadLen = payloadLen;
        return this;
    }

    // Getters
    public byte getVersion() { return version; }
    public byte getFrameType() { return frameType; }
    public short getServiceId() { return serviceId; }
    public short getMethodId() { return methodId; }
    public short getFlags() { return flags; }
    public long getCorrelationId() { return correlationId; }
    public int getSeqNo() { return seqNo; }
    public int getPayloadLen() { return payloadLen; }

    // Utility
    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    @Override
    public String toString() {
        return String.format(
            "Header[v=%d type=%d svc=%d method=%d flags=0x%04x corrId=%d len=%d]",
            version, frameType, serviceId, methodId, flags, correlationId, payloadLen
        );
    }
}
