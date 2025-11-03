 📊 Account 프로젝트 분석 완료

  전체적으로 매우 잘 설계되어 있습니다! 특히:
  - ✅ 샤드 워커 패턴 (32개)
  - ✅ 멱등성 처리 (request_id)
  - ✅ BigDecimal 캐싱 (MoneyParser)
  - ✅ Batch 쿼리 최적화
  - ✅ TransactionalEventPublisher

  하지만 1000 TPS + 100-150ms 목표를 위해 몇 가지 개선점을 발견했습니다:

  ---
  🎯 개선 제안 (우선순위별)

  [HIGH] 1. 멱등성 체크 DB 조회 최적화 ⚡

  현재 문제:
  // AccountApplicationService.java:41
  if (repo.isDuplicateRequest(requestId)) { // ← 매번 DB SELECT
      return AccountResult.fail(...);
  }

  // AccountJOOQRepository.java:24-29
  public boolean isDuplicateRequest(String requestId) {
      Integer count = dsl.fetchOne("""
          SELECT COUNT(*) FROM request_history
           WHERE request_id = ?
      """, requestId).into(Integer.class);  // ← DB 조회!
      return count != null && count > 0;
  }

  성능 영향:
  - 1000 TPS → 약 2000 SELECT 쿼리/초 (Reserve + Unreserve)
  - DB 부하의 주범
  - 레이턴시: 매 요청마다 +5~15ms

  개선 방안: Redis 캐시 레이어 추가

  @ApplicationScoped
  public class IdempotencyCache {

      @Inject RedisClient redis;
      @Inject AccountJOOQRepository repo;

      private static final String PREFIX = "idempotency:";
      private static final Duration TTL = Duration.ofHours(1);

      /**
       * 3-tier 체크:
       * 1. Redis 조회 (대부분 여기서 처리) - ~1ms
       * 2. DB 조회 (Cache miss) - ~10ms
       * 3. Redis에 저장
       */
      public boolean isDuplicateRequest(String requestId) {
          String key = PREFIX + requestId;

          // 1. Redis 조회
          String cached = redis.get(key);
          if (cached != null) {
              return "1".equals(cached);
          }

          // 2. DB 조회 (fallback)
          boolean isDuplicate = repo.isDuplicateRequest(requestId);

          // 3. Redis에 캐싱
          redis.setex(key, TTL.getSeconds(), isDuplicate ? "1" : "0");

          return isDuplicate;
      }

      /**
       * 요청 처리 후 즉시 Redis에 마킹
       */
      public void markProcessed(String requestId) {
          redis.setex(PREFIX + requestId, TTL.getSeconds(), "1");
      }
  }

  예상 효과:
  - Cache hit rate 95%+ (재시도는 드묾)
  - DB SELECT 2000/s → 100/s (20배 감소)
  - 평균 레이턴시 -10ms 개선

  ---
  [MEDIUM] 2. findById() 쿼리 최적화

  현재:
  // AccountApplicationService.java:46, 83, 114
  var acc = repo.findById(accountId);  // 매번 전체 컬럼 SELECT

  // AccountJOOQRepository.java:204-209
  public AccountRecord findById(long accountId) {
      return dsl.fetchOne("""
          SELECT account_id, account_no, balance, reserved, currency, status, updated_at
            FROM accounts
           WHERE account_id = ?
      """, accountId).into(AccountRecord.class);
  }

  문제:
  - Reserve/Unreserve에서는 status만 필요한데 전체 컬럼 조회
  - 불필요한 데이터 전송

  개선:
  // 상태만 확인하는 경량 메서드 추가
  public AccountStatus getStatus(long accountId) {
      String status = dsl.fetchOne("""
          SELECT status FROM accounts WHERE account_id = ?
      """, accountId).into(String.class);
      return AccountStatus.fromString(status);
  }

  // Service 레이어
  AccountStatus status = repo.getStatus(accountId);
  if (!status.canReserve()) {
      return AccountResult.fail(...);
  }

  예상 효과:
  - 네트워크 I/O 감소
  - 레이턴시 -2~5ms

  ---
  [MEDIUM] 3. Batch 쿼리 Prepared Statement 재사용

  현재:
  // AccountJOOQRepository.java:62-88
  var batchQueries = new ArrayList<Query>();
  batchQueries.add(dsl.query("""INSERT INTO request_history...""", ...));
  batchQueries.add(dsl.query("""UPDATE accounts...""", ...));
  batchQueries.add(dsl.query("""INSERT INTO account_reserves...""", ...));
  int[] results = dsl.batch(batchQueries).execute();

  문제:
  - 매번 새로운 Query 객체 생성
  - Prepared Statement 플랜 캐싱 불가능

  개선:
  @ApplicationScoped
  public class AccountJOOQRepository {

      @Inject DSLContext dsl;

      // Prepared Statement를 필드로 선언 (재사용)
      private final String RESERVE_REQUEST_SQL =
          "INSERT INTO request_history (...) VALUES (?, 'RESERVE', ?, ?, 'PROCESSING', NULL, now()) ON CONFLICT DO NOTHING";

      private final String RESERVE_ACCOUNT_SQL =
          "UPDATE accounts SET balance = balance - ?, reserved = reserved + ?, updated_at = now() WHERE account_id = ? AND balance >= ? AND status = 'ACTIVE'";

      public boolean tryReserve(long accountId, BigDecimal amount, String requestId) {
          // Batch with prepared statements
          int[] results = dsl.batch(
              dsl.query(RESERVE_REQUEST_SQL, requestId, accountId, amount),
              dsl.query(RESERVE_ACCOUNT_SQL, amount, amount, accountId, amount),
              dsl.query(RESERVE_HISTORY_SQL, accountId, requestId, amount)
          ).execute();

          boolean updated = results.length >= 2 && results[1] == 1;
          if (updated) {
              markRequestProcessed(requestId, "SUCCESS", "RESERVED");
          }
          return updated;
      }
  }

  예상 효과:
  - DB 플랜 캐싱 활용
  - 레이턴시 -3~7ms

  ---
  [LOW] 4. 디렉토리 구조 개선

  현재 구조:
  src/main/java/com/hts/account/
  ├── grpc/
  ├── service/
  ├── repository/
  ├── valueobject/
  ├── infrastructure/
  └── exception/

  개선 제안:
  src/main/java/com/hts/account/
  ├── config/                       # ← NEW
  │   ├── GrpcConfig.java          # gRPC executor 명시적 설정
  │   ├── DatabaseConfig.java      # JOOQ, HikariCP 설정
  │   └── CacheConfig.java         # Redis 설정
  ├── domain/                       # ← RENAME from valueobject
  │   ├── entity/                  # ← NEW (JPA 엔티티, 변경 가능)
  │   └── vo/                      # ← RENAME (불변 Value Object)
  │       ├── AccountRecord.java
  │       ├── AccountResult.java
  │       └── PositionRecord.java
  ├── application/                  # ← RENAME from service
  │   └── AccountApplicationService.java
  ├── grpc/
  ├── repository/
  └── infrastructure/

  이점:
  - 도메인 주도 설계(DDD) 패턴 명확화
  - 설정 파일 중앙화

  ---
  [OPTIONAL] 5. gRPC Executor 명시적 설정

  현재:
  # application.properties:43
  quarkus.grpc.server.virtual-threads.enabled=false

  Quarkus는 기본적으로 Vert.x event-loop를 사용하지만, @Blocking이 있으면 워커 풀로 위임합니다.

  개선 (선택):
  @Configuration
  public class GrpcConfig {

      @Bean
      public Executor grpcBlockingExecutor() {
          ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
          executor.setCorePoolSize(32);   // 샤드 워커 수와 동일
          executor.setMaxPoolSize(64);
          executor.setQueueCapacity(10000);
          executor.setThreadNamePrefix("grpc-blocking-");
          executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
          executor.initialize();
          return executor;
      }
  }

  참고: Quarkus는 quarkus.thread-pool.* 설정으로 이미 워커 풀을 관리하므로, 현재 설정으로도 충분할 수 있습니다.

  ---
  [INFO] Reactive (Mono/Flux) 전환 불가 확인

  결론: 현재 구조가 최선입니다.

  - ❌ JOOQ는 Reactive API 미제공 (R2DBC 사용 시 가능하지만 JOOQ 포기해야 함)
  - ❌ JTA @Transactional은 동기식만 지원
  - ✅ CompletionStage + Uni 조합으로 비동기성 확보 중

  대안:
  - R2DBC + Spring Data R2DBC로 전환 → 하지만 JOOQ의 강력한 쿼리 빌더 포기
  - 현재 구조 유지 권장

  ---
  🚀 즉시 적용 가능한 Quick Win

  1. Redis 멱등성 캐시 추가 (HIGH)

  - 예상 작업 시간: 1시간
  - 효과: 레이턴시 -10ms, DB 부하 95% 감소

  2. getStatus() 경량 메서드 추가 (MEDIUM)

  - 예상 작업 시간: 30분
  - 효과: 레이턴시 -3ms

  3. Prepared Statement 재사용 (MEDIUM)

  - 예상 작업 시간: 1시간
  - 효과: 레이턴시 -5ms

  총 예상 개선: 현재 레이턴시 -15~20ms

  ---

● 어떤 개선부터 시작하시겠습니까?

  추천 순서:
  1. Redis 멱등성 캐시 (가장 큰 효과)
  2. getStatus() 경량 메서드
  3. Prepared Statement 재사용

  또는 다른 부분 (디렉토리 구조, gRPC 설정 등)도 가능합니다.

  코드 작성을 도와드릴까요, 아니면 특정 부분에 대한 더 자세한 설명이 필요하신가요?
  
  
    1. Outbox 테이블 추가
  2. OutboxPoller 구현
  3. account_reserves는 비즈니스 이력으로 유지
  

