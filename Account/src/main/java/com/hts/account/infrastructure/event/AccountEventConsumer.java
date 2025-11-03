package com.hts.account.infrastructure.event;

import com.hts.account.application.AccountServiceImpl;
import com.hts.account.domain.model.ServiceResult;
import com.hts.generated.events.DepositEvent;
import com.hts.generated.events.FillEvent;
import com.hts.generated.events.WithdrawEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * 계좌에 영향을 주는 외부 이벤트 소비
 *
 * 설계 원칙:
 * 1. 1 Topic = 1 Event Type = 1 @Incoming 메서드
 * 2. Protobuf 직렬화 (byte[])
 * 3. 멱등성 보장 (event_id 기반)
 * 4. 순서 보장 (Partition Key = account_id)
 */
@ApplicationScoped
public class AccountEventConsumer {

    private static final Logger log = Logger.getLogger(AccountEventConsumer.class);

    @Inject
    AccountServiceImpl accountService;

    /**
     * 체결 이벤트 소비
     * Topic: trading.fills
     * Producer: Matching Engine
     */
    @Incoming("trading-fills")
    @Blocking  // DB 트랜잭션이 있으므로 블로킹 필요
    public void onFill(byte[] message) {
        try {
            FillEvent event = FillEvent.parseFrom(message);

            String eventId = event.getEventId();
            Long accountId = event.getAccountId();
            String symbol = event.getSymbol();
            String side = event.getSide();
            BigDecimal fillPrice = new BigDecimal(event.getFillPrice());
            BigDecimal quantity = new BigDecimal(event.getQuantity());
            BigDecimal filledAmount = new BigDecimal(event.getFilledAmount());

            log.infof("Fill event: eventId=%s, accountId=%d, symbol=%s, side=%s, qty=%s",
                     eventId, accountId, symbol, side, quantity);
            // eventId를 requestId로 사용 (멱등성 보장)
            ServiceResult result = accountService.applyFill(
                    accountId,
                    filledAmount,
                    symbol,
                    fillPrice,
                    quantity,
                    side,
                    eventId
            );

            if (result.isSuccess()) {
                log.infof("Fill processed: eventId=%s", eventId);
            } else {
                log.errorf("Fill failed: eventId=%s, code=%s",
                          eventId, result.code());
                // Phase 2: DLQ로 전송
            }

        } catch (Exception e) {
            log.errorf(e, "Failed to parse FillEvent");
            throw new RuntimeException("Fill event processing failed", e);
        }
    }

    /**
     * 입금 이벤트 소비
     * Topic: payment.deposits
     * Producer: Payment Service
     */
    @Incoming("payment-deposits")
    @Blocking
    public void onDeposit(byte[] message) {
        try {
            DepositEvent event = DepositEvent.parseFrom(message);

            log.infof("Deposit event: eventId=%s, accountId=%d, amount=%s",
                     event.getEventId(), event.getAccountId(), event.getAmount());

            // TODO: Phase 2에서 구현
            // accountService.deposit(event.getAccountId(), amount, event.getEventId());

        } catch (Exception e) {
            log.errorf(e, "Failed to parse DepositEvent");
            throw new RuntimeException("Deposit event processing failed", e);
        }
    }

    /**
     * 출금 이벤트 소비
     * Topic: payment.withdrawals
     * Producer: Payment Service
     */
    @Incoming("payment-withdrawals")
    @Blocking
    public void onWithdraw(byte[] message) {
        try {
            WithdrawEvent event = WithdrawEvent.parseFrom(message);

            log.infof("Withdraw event: eventId=%s, accountId=%d, amount=%s",
                     event.getEventId(), event.getAccountId(), event.getAmount());

            // TODO: Phase 2에서 구현
            // accountService.withdraw(event.getAccountId(), amount, event.getEventId());

        } catch (Exception e) {
            log.errorf(e, "Failed to parse WithdrawEvent");
            throw new RuntimeException("Withdraw event processing failed", e);
        }
    }
}
