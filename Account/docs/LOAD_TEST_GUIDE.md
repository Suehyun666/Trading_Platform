# Load Test Guide - Phase 2

## 🎯 Test Objectives

| Metric | Target | Critical |
|--------|--------|----------|
| Reserve RPC TPS | 1000 | ✅ |
| Reserve p99 latency | < 10ms | ✅ |
| Success Rate | > 95% | ✅ |
| DB Connection Pool | Max 30 | ✅ |
| Kafka Consumer Lag | < 100ms | ⚠️ |

---

## 📦 Prerequisites

### 1. PostgreSQL 실행
```bash
docker run -d --name postgres-hts \
  -e POSTGRES_USER=hts \
  -e POSTGRES_PASSWORD=hts \
  -e POSTGRES_DB=hts_account \
  -p 5432:5432 \
  postgres:15

# 테스트 데이터 삽입 (1000개 계좌)
for i in {1..1000}; do
  psql -h localhost -U hts -d hts_account -c \
    "INSERT INTO accounts (account_id, account_no, balance, reserved, currency, status) \
     VALUES ($i, 'ACC-$i', 100000.00, 0, 'USD', 'ACTIVE');"
done
```

### 2. Kafka 3.9.x 실행
```bash
# Kafka 시작 (이미 설치됨)
bin/kafka-server-start.sh config/server.properties

# 토픽 생성
bin/kafka-topics.sh --create \
  --topic trading.fills \
  --bootstrap-server localhost:9092 \
  --partitions 8 \
  --replication-factor 1

bin/kafka-topics.sh --create \
  --topic trading.account.events \
  --bootstrap-server localhost:9092 \
  --partitions 8 \
  --replication-factor 1
```

### 3. Prometheus 실행
```bash
# monitoring/prometheus.yml 사용
prometheus --config.file=monitoring/prometheus.yml

# 확인
curl http://localhost:9090/targets
```

### 4. Grafana 실행
```bash
# Grafana 실행 (이미 설치됨)
# http://localhost:3000 접속

# 대시보드 import
# monitoring/grafana-dashboard.json 업로드
```

---

## 🚀 Test Execution

### 1️⃣ Account Service 시작
```bash
./gradlew quarkusDev

# 메트릭 확인
curl http://localhost:8080/q/metrics | grep account_reserve
```

### 2️⃣ gRPC 부하 테스트 (1000 TPS)
```bash
cd src/test/java/com/hts/account/loadtest

# 컴파일 & 실행
./gradlew test --tests GrpcLoadTest

# 예상 출력:
# === Test Results ===
# Total Requests: 60000
# Success: 57000
# Failure: 3000
# Success Rate: 95.00%
# Actual TPS: 1000.23
# Duration: 60000ms
#
# === Latency (ms) ===
# Average: 5
# p50: 4
# p95: 8
# p99: 9
# Max: 15
#
# === Goal Achievement ===
# Target TPS 1000: ✅ PASS
# p99 < 10ms: ✅ PASS
# Success Rate > 95%: ✅ PASS
```

### 3️⃣ 멀티스레드 병목 테스트
```bash
./gradlew test --tests MultithreadedBottleneckTest

# 예상 결과:
# --- Testing with 10 threads ---
# TPS: 950, p99: 5ms
#
# --- Testing with 30 threads ---
# TPS: 980, p99: 7ms
#
# --- Testing with 50 threads ---
# TPS: 850, p99: 15ms  ⚠️ DB 병목 시작
# WARNING: High latency detected!
#
# --- Testing with 100 threads ---
# TPS: 600, p99: 45ms  ❌ 심각한 병목
```

**해석:**
- 30 스레드까지: DB 커넥션 풀 (30개) 내에서 정상 동작
- 50+ 스레드: 커넥션 대기 시간 증가 → p99 상승
- 100+ 스레드: 커넥션 풀 고갈 → TPS 하락

---

## 📊 Grafana Dashboard 확인

### 1. Reserve RPC Throughput
- 목표: 1000 TPS 유지
- 실패 시: gRPC 서버 스레드 부족 또는 DB 병목

### 2. Reserve RPC Latency
- p50: 3~5ms
- p95: 7~9ms
- p99: < 10ms ✅

### 3. DB Connection Pool
- Active: 25~30 (최대)
- Idle: 0~5
- Max: 30

**병목 감지:**
- Active가 30에 고정 → 커넥션 풀 고갈
- Acquire Timeout 증가 → 커넥션 대기 발생

### 4. Kafka Consumer Lag
- trading.fills: < 100ms
- 실패 시: Consumer 처리 속도 부족

---

## 🔧 Tuning Parameters

### DB Connection Pool
```properties
# 현재 설정
quarkus.datasource.jdbc.max-size=30
quarkus.datasource.jdbc.min-size=5

# 부하 테스트 후 조정
# TPS 1500+ 필요 시: max-size=50
# 단, DB 서버 max_connections 확인 필수
```

### gRPC Server Thread Pool
```properties
# Quarkus 기본값: CPU * 2
# 명시적 설정 (필요 시)
quarkus.grpc.server.executor-pool-size=200
```

### Kafka Consumer
```properties
# 처리량 증가 필요 시
mp.messaging.incoming.trading-fills.max.poll.records=500
mp.messaging.incoming.trading-fills.fetch.min.bytes=1048576
```

---

## 🐛 Troubleshooting

### 문제 1: TPS가 500 미만
**원인:**
- DB 쿼리 느림 (인덱스 누락)
- 커넥션 풀 고갈

**해결:**
```sql
-- 쿼리 실행 계획 확인
EXPLAIN ANALYZE SELECT * FROM accounts WHERE account_id = 1;

-- 인덱스 확인
SELECT tablename, indexname FROM pg_indexes WHERE tablename = 'accounts';

-- 느린 쿼리 로깅
ALTER DATABASE hts_account SET log_min_duration_statement = 100;
```

### 문제 2: p99 > 50ms
**원인:**
- DB 커넥션 대기
- GC Pause

**해결:**
```bash
# GC 로그 확인
./gradlew quarkusDev -Dquarkus.log.category."java.lang.management".level=DEBUG

# Heap 크기 조정 (필요 시)
-Xms2g -Xmx2g
```

### 문제 3: Kafka Consumer Lag 증가
**원인:**
- Consumer 처리 속도 < Producer 발행 속도
- DB 병목으로 처리 지연

**해결:**
```bash
# Consumer Group 확인
bin/kafka-consumer-groups.sh --describe \
  --group account-service \
  --bootstrap-server localhost:9092

# Partition 수 증가 (재생성 필요)
bin/kafka-topics.sh --alter \
  --topic trading.fills \
  --partitions 16 \
  --bootstrap-server localhost:9092
```

---

## 📈 Expected Results

### ✅ PASS Criteria
```
TPS: 1000+
p50: < 5ms
p95: < 9ms
p99: < 10ms
Success Rate: > 95%
DB Connection Pool: < 30 (no exhaustion)
Kafka Lag: < 100ms
```

### 🎉 Excellent Results
```
TPS: 1500+
p99: < 8ms
Success Rate: 99%+
DB Connection Pool: 20~25 (healthy margin)
```

### ❌ FAIL Scenarios
```
TPS: < 800
p99: > 20ms
Success Rate: < 90%
DB Connection Pool: 30 (constantly maxed out)
Kafka Lag: > 1000ms
```

---

## 🔍 Post-Test Analysis

### 1. Prometheus PromQL Queries
```promql
# Average TPS (last 5 minutes)
rate(account_reserve_total[5m])

# p99 latency
histogram_quantile(0.99, rate(account_reserve_duration_seconds_bucket[5m]))

# Success rate
(account_reserve_total - account_reserve_failed_total) / account_reserve_total

# DB connection usage
hikaricp_connections_active / hikaricp_connections_max
```

### 2. Export Metrics
```bash
# Prometheus 데이터 export
curl 'http://localhost:9090/api/v1/query_range?query=rate(account_reserve_total[1m])&start=2025-01-15T10:00:00Z&end=2025-01-15T11:00:00Z&step=15s' > results.json
```

### 3. Generate Report
```bash
# 테스트 결과 요약
cat > test-report.md <<EOF
# Load Test Report - $(date)

## Test Configuration
- Target TPS: 1000
- Duration: 60 seconds
- Threads: 100

## Results
- Actual TPS: 1023
- p99 Latency: 9ms
- Success Rate: 96.5%

## Bottlenecks
- None detected

## Recommendations
- Increase max-size to 40 for 1500 TPS target
- Monitor GC pause time under sustained load
EOF
```

---

## 🚧 Next Steps (Phase 3)

1. **Circuit Breaker 추가:**
   - DB 장애 시 빠른 실패 (fail-fast)
   - `@CircuitBreaker` 적용

2. **Rate Limiting:**
   - 계정당 초당 100건 제한
   - Redis 기반 토큰 버킷

3. **Read Replica 분리:**
   - `getBalance()` → Replica
   - Reserve → Primary

4. **Transactional Outbox:**
   - Kafka 발행 실패 시 재시도 보장

---

**Last Updated:** 2025-01-15
**Tested By:** Account Service Team
