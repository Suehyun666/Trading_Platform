

1. # 애플리케이션 실행 (Flyway가 자동으로 마이그레이션)
   
   ./gradlew quarkusDev

2. Kafka 로컬 실행:
   docker run -d --name kafka \
   -p 9092:9092 \
   apache/kafka:latest

4. 멱등성 테스트:
   - 동일한 request_id로 2번 호출
   - 2번째 호출은 DUPLICATE_REQUEST 반환 확인

5. Position 업데이트 테스트:
   grpcurl -plaintext \
   -d '{"account_id": 1, "filled_amount": "500.00", "symbol": "AAPL", 
   
       "fill_price": "150.00", "quantity": "10", "side": "BUY", 
       "request_id": "uuid-456"}' \
   
   localhost:50051 \
   account.AccountService/ApplyFill
   
   Phase 2: 부하 테스트 (1~2주 후)
   
   # Gatling 시나리오
- 동시 1000명이 Reserve 호출

- 타임아웃 재시도 (멱등성 검증)

- DB 락 대기 시간 측정 (p99 < 10ms)
  
  Phase 3: 신뢰성 강화

- Transactional Outbox Pattern (DB 커밋 후 Kafka 발행 보장)

- Circuit Breaker (@CircuitBreaker)

- Read Replica 분리

  ---

  📋 최종 평가 (업데이트)

| 항목      | 이전       | 현재                          | 평가  |
| ------- | -------- | --------------------------- | --- |
| 금액 정확성  | ❌ double | ✅ string (BigDecimal)       | A   |
| 멱등성     | ❌ 없음     | ✅ requestId 기반              | A   |
| 이벤트 일관성 | ❌ 분리됨    | ⚠️ 기본 구현 (Phase 3에서 Outbox) | B   |
| DB 커넥션  | ⚠️ 기본 설정 | ✅ HikariCP 튜닝               | A   |
| 상태 검증   | ❌ 없음     | ✅ Enum 기반                   | A   |
| 포지션 관리  | ❌ 분리됨    | ✅ applyFill 통합              | A   |
| gRPC 구현 | ❌ 없음     | ✅ 완료                        | A   |
| 모니터링    | ❌ 없음     | ⚠️ Prometheus 설정만 (대시보드 필요) | C   |

  ---

1. Kafka 메시지 - trading.account.events 토픽 확인
   
   코드 **"돌아가는 것"**까지 확인하면 완벽합니다. 실행해보고 결과
   보여주세요!
