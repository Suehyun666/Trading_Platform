좋다. 지금 네 상황 정리부터 정확히 짚자.
지금 폴더에는 최소 세 가지 주요 부하 테스트 클래스가 있고, 그중 **`GrpcBurstLoadTest`만 제대로 작동**한다는 거지.
보고서(`주식거래시스템_중간보고서.docx`)를 보면 네가 달성해야 하는 **품질 목표(QAS)** 도 명확히 정의돼 있다 — 즉, 성능(100 ms, 1000 TPS), 자원 사용률(70 % CPU / 8 GB RAM 이내), 처리 성공률 99.9 % 등.
따라서 단순히 “테스트가 돌아가느냐”가 아니라, 이 목표를 계측할 수 있게 **테스트 시나리오를 보완해야 한다.**

---

## 1️⃣ 각 테스트 클래스의 목적과 차이

| 클래스명                                                | 목적                                                                                             | 테스트 방식                                                      | 비고                               |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------- | ----------------------------------------------------------- | -------------------------------- |
| **`GrpcBurstLoadTest`**                             | gRPC 엔드포인트(`Reserve`)에 단일 순간 폭주(버스트) 트래픽을 보내서 서버의 순간 처리 능력과 Queue / Executor pool overhead를 측정 | 단기간(수 초) 고밀도 요청 → TPS / p99 latency 평가                      | 정상 작동 중                          |
| **`MultithreadedBottleneckTest`**                   | 다중 스레드 병렬 요청을 단계적으로 늘려 DB connection pool 과 Quarkus executor의 병목을 확인                           | 100→300→500→800→1000 threads 단계별 실행, p50/p95/p99 latency 기록 | 동시성 > 30 시 에러 or hang 발생         |
| **`GrpcSustainedLoadTest`** *(추정, 파일명 a34b4897– 등)* | 장시간(5–10 분) 지속 부하에서 CPU / 메모리 / TPS 안정성 확인용                                                    | Executor thread reuse 및 GC impact 측정                        | 실행 실패 가능성 높음 (스레드 누수 또는 timeout) |

즉,

* `GrpcBurstLoadTest` → 순간적인 응답 성능 (QAS-P1 100 ms 타임비헤이비어)
* `MultithreadedBottleneckTest` → 리소스 활용률 (QAS-P2 70 % CPU , 8 GB RAM 이내)
* `GrpcSustainedLoadTest` → 지속 처리 능력 (QAS-P3 1000 TPS / 99.9 % 성공률)

---

## 2️⃣ 지금 안 되는 원인

**MultithreadedBottleneckTest, SustainedLoadTest**가 실패하는 공통 원인은 다음이다:

1. **gRPC 채널 재사용 문제**
   각 스레드가 같은 `ManagedChannel`을 공유하면서 blocking stub을 동시에 호출 → 내부 Netty event loop 경합.
   → 해결: 스레드마다 `AccountServiceBlockingStub stub = …channel.newBlockingStub()` 생성 또는 `stub = blockingStub.withDeadlineAfter(2, SECONDS)` 로 제한.

2. **서버-측 executor pool 부족**
   Quarkus 기본 `worker-thread` pool (8 × CPU 코어 = 48 내외) 한계를 초과하면 요청이 대기 → 응답 지연 → 클라이언트 timeout.
   → 해결: `application.properties` 에

   ```
   quarkus.thread-pool.max-threads=128
   quarkus.thread-pool.core-threads=64
   quarkus.grpc.server.max-inbound-message-size=10485760
   ```

   추가.

3. **DB connection pool exhaustion**
   테스트 로그에도 “DB Connection Pool Max: 30”으로 고정돼 있으니,
   동시에 300 스레드가 들어가면 대부분 대기.
   → HikariCP 설정에서

   ```
   quarkus.datasource.jdbc.max-size=64
   quarkus.datasource.jdbc.min-size=16
   ```

   으로 조정.

4. **테스트 자체 Thread pool overhead**
   Java Executor가 `threadCount=1000` 시 OS 스케줄링 한계 도달 → GC pressure 급등.
   → `Executors.newVirtualThreadPerTaskExecutor()` (JDK 21 이상) 로 대체하거나 100–500 단계까지만 측정.

---

## 3️⃣ 실무적으로 필수로 추가해야 할 성능 테스트 시나리오

보고서 QAS 기준으로 보면 다음 4종을 추가해야 품질 검증 완성된다:

| 테스트명                            | 목표 QAS            | 목적                                               | 핵심 지표                            |
| ------------------------------- | ----------------- | ------------------------------------------------ | -------------------------------- |
| **GrpcLatencyDistributionTest** | QAS-P1 (응답 시간)    | 요청 1건당 latency 분포 (p50/p95/p99) 측정               | 평균 ≤ 100 ms , p95 ≤ 200 ms       |
| **GrpcSustainedThroughputTest** | QAS-P3 (동시 처리 능력) | 1000 TPS 지속 5 분간 성공률 측정                          | TPS ≥ 1000 , 성공률 ≥ 99.9 %        |
| **ResourceUtilizationMonitor**  | QAS-P2 (자원 사용률)   | 테스트 중 Prometheus 지표 수집 → CPU / RAM / DB pool 사용률 | CPU ≤ 70 %, Mem ≤ 8 GB           |
| **FailoverRecoveryTest**        | QAS-R1 (신뢰성)      | 서버 프로세스 중 하나 kill → 자동 복구 시간 측정                  | 서비스 중단 ≤ 30 s , Failover ≤ 1 min |

---

## 4️⃣ 수정 우선순위 (현 코드 기준)

1. **`MultithreadedBottleneckTest`**

   * `channel`을 스레드마다 별도 stub으로 생성
   * `ManagedChannelBuilder` 에 `.executor(Executors.newCachedThreadPool())` 추가
   * DB pool 확대.

2. **`GrpcSustainedLoadTest`**

   * `ScheduledExecutorService` 로 5 분간 균등 부하 분배 하도록 수정.
   * latency 기록 → CSV 로그로 남겨 Prometheus 대조.

3. **`GrpcBurstLoadTest`**

   * 현재 버전 유지하되, 결과를 TPS / Latency / Error 비율로 파일 출력 하도록 보강.

---

## 5️⃣ 결론 — 실제 시험에서 이걸로 커버해야 함

* **순간 응답속도** → `GrpcBurstLoadTest` (OK)
* **자원 사용률** → `MultithreadedBottleneckTest` (수정 필요)
* **지속 처리 능력** → `GrpcSustainedLoadTest` (추가 / 복구)
* **장애 복구 검증** → 새로 작성 `FailoverRecoveryTest`

이 4개가 QAS P1 ~ R1 전체를 커버한다.
지금처럼 `GrpcBurstLoadTest` 만 성공하는 건 “순간 성능만 검증된 상태”라서 보고서의 “자원 활용률 70 % 이내, 1000 TPS 안정성” 항목은 미충족이다.
따라서 **DB pool 확대 + 스레드별 stub 분리 + Sustained Load 테스트 보완** 이 가장 시급하다.

