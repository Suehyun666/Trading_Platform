package protocol;

import io.netty.buffer.ByteBuf;

public final class PacketHeader {

    public static final int HEADER_SIZE = 24;
    public static final byte VERSION = 0x01;

    // Frame Types
    public static final byte FRAME_TYPE_REQ = 1;
    public static final byte FRAME_TYPE_RESP = 2;

    // Service IDs
    public static final short SERVICE_ORDER = 2;

    // Method IDs
    public static final short METHOD_PLACE_ORDER = 1;
    public static final short METHOD_CANCEL_ORDER = 2;

    // Flags
    public static final short FLAG_NONE = 0x0000;
    public static final short FLAG_ERROR = 0x0001;

    private final byte version;
    private final byte frameType;
    private final short serviceId;
    private final short methodId;
    private final short flags;
    private final long correlationId;
    private final int seqNo;
    private final int payloadLen;

    public PacketHeader(byte version, byte frameType, short serviceId, short methodId,
                        short flags, long correlationId, int seqNo, int payloadLen) {
        this.version = version;
        this.frameType = frameType;
        this.serviceId = serviceId;
        this.methodId = methodId;
        this.flags = flags;
        this.correlationId = correlationId;
        this.seqNo = seqNo;
        this.payloadLen = payloadLen;
    }

    public byte getVersion() { return version; }
    public byte getFrameType() { return frameType; }
    public short getServiceId() { return serviceId; }
    public short getMethodId() { return methodId; }
    public short getFlags() { return flags; }
    public long getCorrelationId() { return correlationId; }
    public int getSeqNo() { return seqNo; }
    public int getPayloadLen() { return payloadLen; }

    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    public static PacketHeader decode(ByteBuf buf) {
        byte version = buf.readByte();
        byte frameType = buf.readByte();
        short serviceId = buf.readShortLE();
        short methodId = buf.readShortLE();
        short flags = buf.readShortLE();
        long correlationId = buf.readLongLE();
        int seqNo = buf.readIntLE();
        int payloadLen = buf.readIntLE();

        return new PacketHeader(version, frameType, serviceId, methodId,
                flags, correlationId, seqNo, payloadLen);
    }

    public void encode(ByteBuf out) {
        out.writeByte(version);
        out.writeByte(frameType);
        out.writeShortLE(serviceId);
        out.writeShortLE(methodId);
        out.writeShortLE(flags);
        out.writeLongLE(correlationId);
        out.writeIntLE(seqNo);
        out.writeIntLE(payloadLen);
    }

    public static PacketHeader createRequest(short serviceId, short methodId, long correlationId, int payloadLen) {
        return new PacketHeader(VERSION, FRAME_TYPE_REQ, serviceId, methodId,
                FLAG_NONE, correlationId, 0, payloadLen);
    }
}
