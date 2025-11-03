package com.hts.order.core.exception;

import com.hts.order.core.protocol.PacketHeader;

public abstract class ServerException extends RuntimeException {
    private final PacketHeader header;
    private final int errorCode;

    protected ServerException(String message, PacketHeader header, int errorCode) {
        super(message);
        this.header = header;
        this.errorCode = errorCode;
    }

    public PacketHeader getHeader() { return header; }
    public int getErrorCode() { return errorCode; }

    // 연결 유지 여부 — 하위 클래스에서 오버라이드
    public boolean shouldCloseConnection() { return false; }
}