package com.hts.order.service.exception;

import com.hts.order.core.protocol.PacketHeader;

public class AuthenticationException extends ServiceException {
    public static final int ERROR_CODE_SESSION_NOT_FOUND = 401;
    public static final int ERROR_CODE_INVALID_SESSION = 402;

    public AuthenticationException(String message, PacketHeader packetHeader, int ErrorCode) {
        super(message, packetHeader, ErrorCode);
    }
    @Override
    public boolean shouldCloseConnection() {
        return true;
    }
}
