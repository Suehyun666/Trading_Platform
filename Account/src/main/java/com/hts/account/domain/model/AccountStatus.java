package com.hts.account.domain.model;

public enum AccountStatus {
    ACTIVE,      // 정상 계좌
    SUSPENDED,   // 정지 (입출금/거래 불가)
    CLOSED;      // 폐쇄 (모든 작업 불가)

    public boolean canReserve() {
        return this == ACTIVE;
    }

    public boolean canUnreserve() {
        return this == ACTIVE || this == SUSPENDED;
    }

    public boolean canApplyFill() {
        return this == ACTIVE;
    }

    public boolean canDeposit() {
        return this == ACTIVE;
    }

    public boolean canWithdraw() {
        return this == ACTIVE;
    }

    public static AccountStatus fromString(String status) {
        try {
            return AccountStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid account status: " + status);
        }
    }
}
