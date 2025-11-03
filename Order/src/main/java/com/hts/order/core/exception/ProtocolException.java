package com.hts.order.core.exception;

import com.hts.order.core.protocol.PacketHeader;

public final class ProtocolException extends ServerException {
    public static final int DECODE_ERROR = 1001; // protocol decode failure
    public static final int UNSUPPORTED_VERSION = 1002;
    public static final int INVALID_LENGTH = 1003;

    public ProtocolException(String message, PacketHeader header, int code) {
        super(message, header, code);
    }

    @Override
    public boolean shouldCloseConnection() {
        return true;  // 프로토콜 예외 → 무조건 close
    }
}
