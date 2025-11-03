    package com.hts.account.domain.model;

    import com.hts.account.infrastructure.repository.OperationStatus;
    import com.hts.generated.grpc.ResultCode;

    public record ServiceResult(ResultCode code) {
        public static ServiceResult from(OperationStatus status) {
            return switch (status) {
                case UPDATED  -> new ServiceResult(ResultCode.SUCCESS);
                case DUPLICATE -> new ServiceResult(ResultCode.DUPLICATE_REQUEST);
                case NOT_FOUND -> new ServiceResult(ResultCode.ACCOUNT_NOT_FOUND);
                case INVALID_STATE -> new ServiceResult(ResultCode.ACCOUNT_SUSPENDED);
                case INSUFFICIENT_BALANCE -> new ServiceResult(ResultCode.INSUFFICIENT_FUNDS);
                case INTERNAL_ERROR -> new ServiceResult(ResultCode.INTERNAL_ERROR);
            };
        }
        public static ServiceResult of(ResultCode code) {
            return new ServiceResult(code);
        }

        public boolean isSuccess() {
            return code == ResultCode.SUCCESS;
        }
    }
