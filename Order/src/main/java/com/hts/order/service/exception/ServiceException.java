package com.hts.order.service.exception;

import com.hts.order.core.exception.ServerException;
import com.hts.order.core.protocol.PacketHeader;

public class ServiceException extends ServerException {
    public ServiceException(String message, PacketHeader packetHeader, int ErrorCode) {
        super(message, packetHeader, ErrorCode);
    }
}
