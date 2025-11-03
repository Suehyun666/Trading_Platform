# Account Service Architecture

## 📚 References (Toss Engineering Blog Analysis)

### 1. [Kafka 데이터센터 이중화](https://toss.tech/article/kafka-distribution-1)

**문제:**
- 데이터센터 장애 시 Kafka 단일 장애점으로 전체 서비스 마비

**토스의 해결:**
- **Active-Active 구성** 선택 (vs Stretched Cluster)
- 양방향 미러링으로 두 클러스터 동기화
- Split DNS + GSLB로 장애 시 자동 전환
- Consumer Group Offset 동기화 필수

**우리 프로젝트 적용:**
- [ ] Phase 3: Active-Active Kafka 구성 (AWS Multi-AZ)
- [ ] Phase 3: Consumer Group Offset 동기화 메커니즘
- [ ] Phase 2: Kafka 장애 시 Circuit Breaker로 서비스 격리

---

### 2. [토스증권 서버 아키텍처](https://toss.im/career/article/secu_server-chapter-2)

**핵심 설계:**
- **CQRS 아키텍처**: 원본 데이터(Oracle) + 읽기 전용 뷰(별도 DB)
- **Vitess 도입**: MySQL 샤딩 게이트웨이로 API 응답 p95 < 40ms
- **통합 레이어**: 국내/해외 주식 등 여러 상품의 서로 다른 API를 중간 계층에서 통합

**우리 프로젝트 적용:**
- [x] Phase 0: 읽기/쓰기 분리 준비 (JOOQ Repository 계층)
- [ ] Phase 4: Read Replica 분리 (`getBalance()` → Replica)
- [ ] Phase 4: 샤딩 전략 (accountId % 8 → 8개 샤드)

---

### 3. [MSA Observability](https://toss.tech/article/MSA-observability)

**핵심:**
- **ClickHouse 중심 모니터링**: Prometheus 대비 "초당 수십만 건 입수 + SQL join 가능"
- **Kafka 연결 관계 파악**: 10,000개 Pod가 1,000개 Topic 사용 → 어떤 서비스가 어떤 Topic 사용하는지 추적
- **데이터 소스**:
  - Kafka Broker METADATA API 로그
  - Consumer Group Lag Metric
  - Linux conntrack (K8S Node)

**우리 프로젝트 적용:**
- [ ] Phase 2: Prometheus + Grafana 대시보드
  - Reserve RPC latency (p50/p95/p99)
  - DB connection pool 사용률
  - Kafka consumer lag
- [ ] Phase 3: ClickHouse 도입 검토 (TPS > 10,000 시)
- [ ] Phase 2: Jaeger Tracing (Reserve → DB → Kafka 전체 경로)

---

## 🏗️ System Architecture

### High-Level Overview

```
┌─────────────┐       gRPC          ┌──────────────────┐
│   Order     │ ───── Reserve ─────>│  Account Service │
│   Service   │ <──── Reply ────────│   (this repo)    │
└─────────────┘                     └──────────────────┘
                                              │
                                              │ Kafka Producer
                                              ▼
                    ┌────────────────────────────────────────┐
                    │    Kafka: trading.account.events.out  │
                    └────────────────────────────────────────┘
                                              │
                                              │ Consume
                                              ▼
                    ┌────────────────────────────────────────┐
                    │        Order Service (consume)         │
                    │     "계좌 예약 완료" 확인 후 주문 전송    │
                    └────────────────────────────────────────┘


┌─────────────┐    Kafka: trading.fills    ┌──────────────────┐
│  Matching   │ ──────────────────────────>│  Account Service │
│   Engine    │       FillEvent (Proto)    │  (Consumer)      │
└─────────────┘                            └──────────────────┘
                                                     │
                                                     │ DB Update
                                                     ▼
                                           ┌──────────────────┐
                                           │   PostgreSQL     │
                                           │  - accounts      │
                                           │  - positions     │
                                           │  - reserves      │
                                           └──────────────────┘
```

---

## 🎯 Service Responsibilities

### Account Service는 다음만 처리:
1. **Reserve/Unreserve** (gRPC) - Order 서비스가 호출
2. **Fill 이벤트 소비** (Kafka) - Matching Engine이 발행
3. **Position 관리** - 체결 시 수량/평균단가 자동 업데이트

### Account Service가 하지 않는 것:
- ❌ 주문 생성/취소 (Order 서비스 역할)
- ❌ 수수료 계산 (Order 서비스가 Reserve 시 포함)
- ❌ 입금/출금 처리 (Payment 서비스 역할)
- ❌ 매칭/체결 (Matching Engine 역할)

---

## 📦 Event Design (Protobuf)

### Kafka Topic 설계 원칙

```
✅ 1 Topic = 1 Event Type = 1 @Incoming 메서드
✅ Namespace는 도메인 기준 (trading.*, payment.*)
✅ Protobuf 사용 (타입 안정성 + 성능)
✅ Partition Key = account_id (같은 계좌는 순서 보장)
❌ oneof 패턴 사용 안 함 (모든 메시지 읽고 필터링 = 비효율)
```

### 우리 프로젝트 토픽 구조

| Topic | Producer | Consumer | Event Type | Partition Key |
|-------|----------|----------|------------|---------------|
| `trading.fills` | Matching Engine | Account Service | FillEvent | account_id |
| `trading.account.events` | Account Service | Order Service | RESERVED/UNRESERVED | account_id |
| `payment.deposits` | Payment Service | Account Service | DepositEvent | account_id |
| `payment.withdrawals` | Payment Service | Account Service | WithdrawEvent | account_id |

### Consumer 설계 패턴

**한 클래스에 여러 @Incoming:**
```java
@ApplicationScoped
public class AccountEventConsumer {

    @Incoming("trading-fills")
    @Blocking
    public void onFill(byte[] message) {
        FillEvent event = FillEvent.parseFrom(message);
        // 처리 로직
    }

    @Incoming("payment-deposits")
    @Blocking
    public void onDeposit(byte[] message) {
        DepositEvent event = DepositEvent.parseFrom(message);
        // 처리 로직
    }
}
```

**왜 클래스 분리 안 하나?**
- 토픽마다 클래스 만들면 100개 토픽 = 100개 클래스 → 유지보수 지옥
- 도메인(Account) 기준으로 하나로 묶음

### events.proto 설계

```protobuf
syntax = "proto3";
package events;

// 체결 이벤트 (Matching Engine → Account)
// Kafka Topic: trading.fills
message FillEvent {
  string event_id = 1;        // 멱등성 키 (UUID)
  int64 account_id = 2;       // Partition Key
  string order_id = 3;
  string symbol = 4;          // AAPL, TSLA
  string side = 5;            // BUY, SELL
  string fill_price = 6;      // "150.00"
  string quantity = 7;        // "10"
  string filled_amount = 8;   // "1500.00"
  int64 timestamp = 9;
}

// 입금 이벤트 (Payment → Account)
// Kafka Topic: payment.deposits
message DepositEvent {
  string event_id = 1;
  int64 account_id = 2;       // Partition Key
  string amount = 3;
  string currency = 4;
  string source = 5;
  int64 timestamp = 6;
}

// 출금 이벤트 (Payment → Account)
// Kafka Topic: payment.withdrawals
message WithdrawEvent {
  string event_id = 1;
  int64 account_id = 2;       // Partition Key
  string amount = 3;
  string currency = 4;
  string destination = 5;
  int64 timestamp = 6;
}
```

**Protobuf 직렬화:**
```java
// Producer (발행)
FillEvent event = FillEvent.newBuilder()
    .setEventId(UUID.randomUUID().toString())
    .setAccountId(accountId)
    .build();
byte[] bytes = event.toByteArray();
emitter.send(bytes);

// Consumer (소비)
FillEvent event = FillEvent.parseFrom(message);
```

---

## 🔐 Idempotency (멱등성)

### 문제: gRPC 타임아웃 재시도
```
Order Service → Reserve(1000원, requestId=uuid-123)
             ← [timeout, 응답 없음]
             → Reserve(1000원, requestId=uuid-123)  // 재시도
```

### 해결: request_id 기반 중복 체크

```sql
-- request_history 테이블
CREATE TABLE request_history (
    request_id VARCHAR(64) PRIMARY KEY,
    request_type VARCHAR(32),  -- RESERVE, UNRESERVE, APPLY_FILL
    account_id BIGINT,
    amount NUMERIC(18,4),
    status VARCHAR(16),        -- SUCCESS, FAILED
    created_at TIMESTAMP
);

-- 중복 요청 체크
SELECT COUNT(*) FROM request_history WHERE request_id = ?;
IF count > 0 THEN RETURN "DUPLICATE_REQUEST";
```

### Kafka 이벤트 멱등성
- `FillEvent.event_id`를 `requestId`로 사용
- Matching Engine이 같은 체결을 2번 발행해도 Account는 1번만 처리

---

## 🔥 Critical Issues Fixed (Phase 0)

### 1. double → string (금액 타입)
**문제:**
```protobuf
double amount = 2;  // ❌ 0.1 + 0.2 ≠ 0.3
```

**해결:**
```protobuf
string amount = 2;  // ✅ "123.4567" → BigDecimal 파싱
```

회계 감사에서 1원 오차도 허용 안 됨.

### 2. 멱등성 보장
**문제:**
- Reserve 타임아웃 재시도 시 잔고 2번 차감

**해결:**
```java
if (repo.isDuplicateRequest(requestId)) {
    return AccountResult.fail("DUPLICATE_REQUEST", "이미 처리됨");
}
```

### 3. 이벤트 일관성
**문제:**
- DB 커밋 성공 → Kafka publish 실패 → 데이터 불일치

**해결 (Phase 3):**
- Transactional Outbox Pattern
- DB 커밋과 동시에 `outbox` 테이블에 INSERT
- 별도 스케줄러가 outbox → Kafka 전송 (재시도 보장)

---

## 📊 Performance Requirements (Phase 2 목표)

| Metric | Target | Measurement |
|--------|--------|-------------|
| Reserve RPC latency | p99 < 10ms | Prometheus |
| DB connection pool | Max 30 | HikariCP |
| Kafka consumer lag | < 100ms | Burrow |
| Throughput | 1000 TPS | Gatling |

---

## 🛠️ Technology Stack

- **Language**: Java 17
- **Framework**: Quarkus 3.20.3
- **RPC**: gRPC (Protobuf)
- **DB**: PostgreSQL 15 + JOOQ
- **Messaging**: Kafka + Smallrye Reactive Messaging
- **Monitoring**: Prometheus + Grafana + Jaeger (Phase 2)
- **Connection Pool**: HikariCP (max 30)

---

## 🚀 Implementation Phases

### Phase 0: 긴급 수정 ✅
- [x] Proto double → string
- [x] requestId 멱등성
- [x] AccountStatus enum
- [x] Kafka EventPublisher
- [x] HikariCP 설정

### Phase 1: 기본 동작 (현재)
- [x] gRPC 서비스 구현
- [x] Position 업데이트 (매수/매도)
- [ ] Kafka Consumer (FillEvent)
- [ ] 통합 테스트

### Phase 2: 부하 테스트 (1~2주)
- [ ] Gatling 시나리오 (1000 TPS)
- [ ] Prometheus + Grafana 대시보드
- [ ] Jaeger 분산 추적
- [ ] DLQ (Dead Letter Queue) 추가

### Phase 3: 신뢰성 강화 (3~4주)
- [ ] Transactional Outbox Pattern
- [ ] Circuit Breaker (`@CircuitBreaker`)
- [ ] Kafka Active-Active 구성
- [ ] Read Replica 분리

### Phase 4: 운영 준비
- [ ] account_reserves 테이블 감사
- [ ] Rate Limiting
- [ ] DB 샤딩 전략
- [ ] ClickHouse 도입 (TPS > 10k)

---

## 🔍 Lessons from Toss Engineering

### 1. 단순함이 승리한다
- Active-Active vs Stretched Cluster → Active-Active (운영 간단)
- CQRS로 읽기/쓰기 분리 → 성능 향상
- 1 Topic = 1 Event Type → Consumer 로직 단순

### 2. 관찰 가능성은 필수
- ClickHouse로 초당 수십만 건 메트릭 입수
- Kafka Producer-Consumer 연결 관계 추적
- 구간별 latency 로깅 (Reserve → DB → Kafka)

### 3. 장애는 반드시 발생한다
- 데이터센터 이중화 (Active-Active)
- Consumer Group Offset 동기화
- Circuit Breaker로 장애 격리

### 4. 성능 = 아키텍처 설계
- Vitess 샤딩으로 p95 < 40ms
- Read Replica 분리 (조회 부하 분산)
- HikariCP 커넥션 풀 튜닝 (max 30)

---

## 📝 TODO: Open Questions

1. **DB 샤딩 전략은?**
   - accountId % 8 → 8개 샤드?
   - 계좌 생성 시 샤드 결정 로직?

2. **Kafka 파티션 전략은?**
   - Key = accountId → 같은 계좌는 순서 보장
   - Partition 개수 = Consumer 개수?

3. **장애 복구 시나리오는?**
   - Kafka 장애 시 Reserve 호출 실패 → Order 서비스에 어떻게 전달?
   - DB 장애 시 Circuit Breaker 동작?

---

**Last Updated:** 2025-01-15
**Author:** Account Service Team
