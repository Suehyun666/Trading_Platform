package com.hts.account.application;

import com.hts.account.domain.model.PositionRecord;
import com.hts.account.domain.service.PositionService;
import com.hts.account.infrastructure.repository.PositionJOOQRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Optional;

@ApplicationScoped
public class PositionServiceImpl implements PositionService {

    private static final Logger log = Logger.getLogger(PositionServiceImpl.class);

    @Inject PositionJOOQRepository positionRepo;

    @Override
    public void addOrUpdate(Long accountId, String symbol, BigDecimal quantity, BigDecimal price) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive for BUY");
        }

        if (log.isDebugEnabled()) {
            log.debug("Adding/updating position: accountId=" + accountId + ", symbol=" + symbol +
                     ", qty=" + quantity + ", price=" + price);
        }

        positionRepo.upsertPositionBuy(accountId, symbol, quantity, price);
    }

    @Override
    public void reduce(Long accountId, String symbol, BigDecimal quantity, BigDecimal price) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be positive for SELL");
        }

        if (log.isDebugEnabled()) {
            log.debug("Reducing position: accountId=" + accountId + ", symbol=" + symbol +
                     ", qty=" + quantity + ", price=" + price);
        }

        positionRepo.updatePositionSell(accountId, symbol, quantity, price);

        // 수량이 0이 되면 삭제 (선택적)
        positionRepo.deleteZeroPositions(accountId);
    }

    @Override
    public Optional<PositionRecord> findByAccountAndSymbol(Long accountId, String symbol) {
        PositionRecord record = positionRepo.findByAccountAndSymbol(accountId, symbol);
        if (record == null) {
            return Optional.empty();
        }

        // Record를 Entity로 변환 (필요시)
        // 현재는 PositionRecord를 직접 사용하는 것을 권장
        return Optional.empty();  // TODO: Entity 변환 로직 (필요시)
    }
}
