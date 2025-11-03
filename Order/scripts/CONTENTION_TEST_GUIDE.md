# 동시성 충돌 테스트 가이드

## 📋 개요

기존 `ContinuousLoadTest`는 각 클라이언트가 **독립적인 계좌**를 사용하여 락 경합이 발생하지 않았습니다.
새로운 `ContentionLoadTest`는 **여러 세션이 같은 계좌를 공유**하여 실제 동시성 충돌을 테스트합니다.

## 🔍 문제 인식

### 기존 테스트의 한계
```
클라이언트 #0 → sessionId: 1000 → accountId: 1000
클라이언트 #1 → sessionId: 1001 → accountId: 1001
클라이언트 #2 → sessionId: 1002 → accountId: 1002
...
```
→ **모든 계좌가 독립적** → 락 경합 없음 → 비현실적인 높은 성능

### 새로운 테스트 구조
```
클라이언트 #0-19   → sessionId: 20000-20019 → accountId: 1000 (20명 공유)
클라이언트 #20-39  → sessionId: 20020-20039 → accountId: 1001 (20명 공유)
클라이언트 #40-59  → sessionId: 20040-20059 → accountId: 1002 (20명 공유)
...
```
→ **1000명 클라이언트 → 50개 계좌** → **계좌당 20개 세션 동시 접근**

## 🚀 실행 방법

### 1단계: Redis 세션 설정
```bash
cd /home/suehyun/Desktop/Server
./scripts/create_contention_sessions.sh
```

**출력 예시:**
```
Creating contention test sessions...
Progress: 0/1000 sessions created...
Progress: 100/1000 sessions created...
...
Done! Created 1000 sessions for contention testing.

Session distribution:
  - Session IDs: 20000-20999 (1000 sessions)
  - Account IDs: 1000-1049 (50 accounts)
  - Sessions per account: 20

Verification:
1000
1000
1001
```

### 2단계: 서버 시작
```bash
cd /home/suehyun/Desktop/Server
./scripts/start-order-optimized.sh
```

### 3단계: (선택) DB 모니터링 시작
**별도 터미널**에서 실행:
```bash
cd /home/suehyun/Desktop/Server
./scripts/monitor_db_contention.sh
```

### 4단계: 부하 테스트 실행
```bash
cd /home/suehyun/Desktop/OrderTest
./run_contention_test.sh
```

## 📊 예상 결과

### 기존 테스트 (Baseline)
```
클라이언트: 1000
처리량: 18,000 req/s (예상)
평균 지연: 2-3ms
P99: 5-10ms
에러율: 0%
```

### 동시성 충돌 테스트 (Contention)
```
클라이언트: 1000 (50개 계좌 공유)
처리량: 2,000-5,000 req/s (예상)
평균 지연: 10-30ms
P99: 50-200ms
에러율: 5-20% (Insufficient balance)
```

## 🔬 관찰할 지표

### 1. 처리량 (Throughput)
- **Baseline**: ~18K req/s
- **Contention**: 2-5K req/s
- **감소 원인**: PostgreSQL row-level lock 대기

### 2. 지연 시간 (Latency)
- **P99 증가**: 5ms → 50-200ms
- **원인**: 트랜잭션 대기 큐

### 3. 에러율
- **"Insufficient balance" 증가**
- **원인**: 동시에 20개 세션이 같은 잔고에서 차감 시도

### 4. PostgreSQL Locks
모니터링 스크립트에서 확인:
```sql
--- Lock Waits (blocked queries) ---
 blocked_queries | locktype | mode
-----------------+----------+------------------
              42 | tuple    | ExclusiveLock
              15 | relation | RowExclusiveLock
```

## 🎯 테스트 시나리오 비교

| 항목 | Baseline | Contention |
|------|----------|------------|
| 클라이언트 수 | 1000 | 1000 |
| 계좌 수 | 1000 | 50 |
| 계좌당 동시 접근 | 1 | 20 |
| Throttling | 1ms sleep | 없음 (burst) |
| 예상 처리량 | 18K req/s | 2-5K req/s |
| 예상 P99 | 5-10ms | 50-200ms |
| 락 경합 | 없음 | 심함 |

## 💡 추가 테스트 아이디어

### 1. 경합도 조절
`create_contention_sessions.sh` 수정:
```bash
# 더 심한 경합: 10개 계좌만 사용 (100명/계좌)
account_id=$((1000 + i / 100))

# 약한 경합: 200개 계좌 사용 (5명/계좌)
account_id=$((1000 + i / 5))
```

### 2. Mixed Workload
일부 클라이언트는 경합, 일부는 독립적:
```java
long sessionId = (i < 500) ? (20000 + i) : (1000 + i);
```

### 3. 잔고 초기화
테스트 전 계좌 잔고를 크게 설정:
```sql
UPDATE accounts SET balance = 10000000 WHERE account_id BETWEEN 1000 AND 1049;
```

## 🛠️ 문제 해결

### Redis 연결 실패
```bash
# Redis 상태 확인
redis-cli PING

# Redis 재시작
sudo systemctl restart redis
```

### 세션 확인
```bash
# 특정 세션 조회
redis-cli GET session:20000

# 모든 contention 세션 개수
redis-cli KEYS "session:2*" | wc -l
```

### PostgreSQL 락 조회
```sql
SELECT * FROM pg_locks WHERE NOT granted;
```

## 📈 성능 분석

### 병목 식별
1. **CPU bound**: `top` 명령으로 CPU 사용률 확인
2. **I/O bound**: `iostat -x 1` 명령으로 디스크 대기 확인
3. **Lock bound**: `pg_locks` 테이블로 락 대기 확인

### 최적화 방향
- HikariCP pool size 조정 (application.conf)
- PostgreSQL shared_buffers 조정
- Blocking thread pool 크기 조정
- 트랜잭션 격리 수준 변경 (주의!)

---

## 📝 요약

이 테스트를 통해 확인할 수 있는 것:
- ✅ 실제 동시성 충돌 시 성능 저하
- ✅ PostgreSQL row-level lock 동작
- ✅ 커넥션 풀 / 블로킹 스레드 풀 한계
- ✅ 에러 처리 및 재시도 로직 필요성

**서버 코드는 전혀 수정하지 않고** 순수하게 부하 패턴만 바꿔서 병목을 찾아냅니다.
