package com.hts.order.core;

import org.jooq.DSLContext;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.Function;

@Singleton
public final class TransactionExecutor {
    private final DSLContext dsl;

    @Inject
    public TransactionExecutor(DSLContext dsl) {
        this.dsl = dsl;
    }

    public <T> T execute(Function<DSLContext, T> work) {
        return dsl.transactionResult(cfg -> work.apply(org.jooq.impl.DSL.using(cfg)));
    }

    public void executeVoid(java.util.function.Consumer<DSLContext> work) {
        dsl.transaction(cfg -> work.accept(org.jooq.impl.DSL.using(cfg)));
    }
}
