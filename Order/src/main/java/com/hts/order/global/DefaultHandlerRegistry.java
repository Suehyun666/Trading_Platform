package com.hts.order.global;

import com.hts.order.service.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기본 핸들러 레지스트리 구현
 * 역할: 핸들러 저장하고 조회
 */

public final class DefaultHandlerRegistry implements HandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultHandlerRegistry.class);

    // serviceId -> Handler
    private final Map<Short, Handler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(short serviceId, Handler handler) {
        handlers.computeIfAbsent(serviceId, k -> handler);
    }

    @Override
    public Handler getHandler(short serviceId){
        return handlers.get(serviceId);
    }
}
