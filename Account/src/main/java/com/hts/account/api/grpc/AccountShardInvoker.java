package com.hts.account.api.grpc;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@ApplicationScoped
public class AccountShardInvoker {

    private static final Logger log = Logger.getLogger(AccountShardInvoker.class);
    private static final int NUM_SHARDS = 128;
    private final List<ExecutorService> shards = new ArrayList<>(NUM_SHARDS);

    public AccountShardInvoker() {
        for (int i = 0; i < NUM_SHARDS; i++) {
            int idx = i;
            shards.add(Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "account-shard-" + idx)
            ));
        }
    }

    private ExecutorService pick(long accountId) {
        int idx = (int) (Math.abs(accountId) % NUM_SHARDS);
        return shards.get(idx);
    }

    public <T> CompletionStage<T> submit(long accountId, Callable<T> task) {
        long submitTime = System.nanoTime();
        CompletableFuture<T> f = new CompletableFuture<>();

        pick(accountId).submit(() -> {
            long queueTime = (System.nanoTime() - submitTime) / 1_000_000; // ms
            long execStart = System.nanoTime();

            try {
                T result = task.call();
                long execTime = (System.nanoTime() - execStart) / 1_000_000; // ms
                long totalTime = (System.nanoTime() - submitTime) / 1_000_000; // ms

                // 성능 모니터링 로그 (모든 요청)
                if (totalTime > 10) {  // 10ms 이상만
                    log.infof("[PERF] accountId=%d | queue=%dms | exec=%dms | total=%dms",
                        accountId, queueTime, execTime, totalTime);
                }

                f.complete(result);
            } catch (Exception e) {
                long execTime = (System.nanoTime() - execStart) / 1_000_000;
                log.errorf(e, "[ERROR] accountId=%d | queue=%dms | exec=%dms",
                    accountId, queueTime, execTime);
                f.completeExceptionally(e);
            }
        });
        return f;
    }
}


