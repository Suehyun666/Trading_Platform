package com.hts.order.global;

import com.hts.order.service.Handler;

/**
 * 메시지 핸들러 레지스트리
 * 역할: (serviceId) → Handler 등록 및 조회만
 */
public interface HandlerRegistry {

    /**
     * 핸들러 등록
     *
     * @param serviceId 서비스 ID
     * @param handler 메시지 핸들러
     */
    void register(short serviceId, Handler handler);

    /**
     * 핸들러 조회
     *
     * @param serviceId 서비스 ID
     * @return 등록된 핸들러, 없으면 null
     */
    Handler getHandler(short serviceId);
}