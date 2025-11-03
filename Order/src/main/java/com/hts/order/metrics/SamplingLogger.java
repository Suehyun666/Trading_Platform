package com.hts.order.metrics;

import org.slf4j.Logger;

/**
 * 고빈도 구간에서 로그 폭주 막기 위한 샘플러
 */
public final class SamplingLogger {
    private final Logger delegate;
    private final int sampleRate;
    private final ThreadLocal<int[]> counter = ThreadLocal.withInitial(() -> new int[1]);

    public SamplingLogger(Logger delegate, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0");
        }
        this.delegate = delegate;
        this.sampleRate = sampleRate;
    }

    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    public void info(String msg, Object... args) {
        int[] c = counter.get();
        c[0]++;
        if (c[0] >= sampleRate) {
            c[0] = 0;
            delegate.info(msg, args);
        }
    }

    public void warn(String msg, Object... args) {
        // warn, error는 샘플 안 태움
        delegate.warn(msg, args);
    }

    public void error(String msg, Object... args) {
        delegate.error(msg, args);
    }
}
