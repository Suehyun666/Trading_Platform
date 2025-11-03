package com.hts.order.metrics;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 고빈도 디버그를 메모리에 모았다가 뭉텅이로 던지는 로거
 * - ThreadLocal을 사용하여 워커 스레드별로 버퍼 격리 (경합 방지)
 * - 🌟 외부 플래그로 선택적 트레이싱 지원
 */
public final class BufferedLogger {
    private final Logger delegate;
    private final ThreadLocal<List<String>> buffer = ThreadLocal.withInitial(() -> new ArrayList<>(128));

    // 🌟 ThreadLocal<Boolean>을 사용하여 현재 스레드가 로깅 대상인지 표시
    private final ThreadLocal<Boolean> isTracing = ThreadLocal.withInitial(() -> false);


    public BufferedLogger(Logger delegate) { // flushSize 제거
        this.delegate = delegate;
    }

    /** 🌟 로깅 활성화/비활성화 플래그 */
    public boolean isTracingEnabled() {
        return isTracing.get();
    }

    /** 🌟 현재 스레드에 트레이싱 플래그 설정 */
    public void setTracing(boolean enabled) {
        isTracing.set(enabled);
    }

    /** 🌟 현재 스레드의 버퍼를 강제로 비우고 로그를 출력 */
    public void flushAndClear() {
        List<String> list = buffer.get();
        if (list.isEmpty()) {
            isTracing.set(false); // 플래그 초기화
            return;
        }

        // 🌟 스레드 정보와 함께 로그를 뭉텅이로 출력
        String threadInfo = "[" + Thread.currentThread().getName() + "]\n";
        String joined = threadInfo + String.join("\n", list);
        delegate.info(joined);

        list.clear();
        isTracing.set(false); // 플래그 초기화
    }

    // 🌟 트레이싱이 활성화된 경우에만 버퍼에 추가 (IO 지연 최소화)
    public void add(String line) {
        if (isTracingEnabled()) {
            buffer.get().add(line);
        }
    }
}