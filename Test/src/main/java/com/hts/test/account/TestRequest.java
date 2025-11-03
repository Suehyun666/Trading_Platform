package com.hts.test.account;

import com.hts.generated.grpc.ResultCode;

import java.math.BigDecimal;

public record TestRequest(
        boolean isReserve,
        Long accountId,
        BigDecimal amount,
        String requestId,
        ResultCode expectedCode) {}
