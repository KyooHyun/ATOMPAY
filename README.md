# AtomPay

카드 결제 처리 코어를 단순화하여 구현한 Spring Boot 백엔드입니다.
승인(authorization) → 매입(capture) → 취소(cancel) / 환불(refund) 의 결제 라이프사이클을,
동시성 정합성 · 멱등성 · 상태 전이 · 보안 네 가지 관점에서 다루는 데 집중했습니다.

화면(프론트엔드)이나 외부 PG 연동 없이, 결제 처리 코어 로직 그 자체의 정확성에 집중한 프로젝트입니다.
"기능이 동작한다"가 아니라 "동시 요청·재시도·중간 실패 상황에서도 금액 정합성이 깨지지 않는다"를 목표로 했습니다.

---

## 한눈에 보기

| 항목 | 내용 |
|------|------|
| 스택 | Spring Boot 3.2 · Spring Security · Spring Data JPA · MySQL (개발 시 H2) · Java 21 |
| 핵심 주제 | 동시성 제어 · 멱등성 · 결제 상태 머신 · JWT 인증 |
| 인증 | Spring Security + JWT (HS256), Stateless 세션 |
| 스키마 관리 | Flyway 버전 마이그레이션 (MySQL 프로파일) |
| API 문서 | Swagger UI — `http://localhost:8080/swagger-ui.html` |
| 검증 | JUnit 단위 테스트 27개(서비스 21 + 레이트리밋 4 + HTTP 레이어 2) + MySQL Testcontainers 동시성/회귀 테스트 4개 |
| 운영 가시성 | MDC 기반 X-Request-Id 추적 · SLF4J 구조적 로깅 · Spring Actuator `/actuator/health` |
| 의도적으로 제외 | 프론트엔드, PG 연동, 정산/대사, 회원 관리 |

---

## 왜 이 프로젝트인가

결제 백엔드에서 가장 어려운 부분은 "기능을 만드는 것"이 아니라
같은 자원에 동시 요청이 몰리거나, 네트워크 재시도로 요청이 중복되거나, 처리 도중 실패했을 때
돈이 틀어지지 않게 하는 것이라고 판단했습니다.

그래서 기능 수를 늘리는 대신, 다음 네 가지 난제에 깊이 들어가는 것을 목표로 잡았습니다.

- **동시성** — 같은 카드/거래에 요청이 동시에 들어올 때 한도·환불 금액이 깨지지 않는가
- **멱등성** — 재시도로 같은 요청이 두 번 들어와도 결제가 한 번만 일어나는가
- **상태 전이** — 승인·매입·취소·환불이 허용된 순서로만 일어나고, 불법 전이는 차단되는가
- **보안** — JWT 인증으로 미인가 접근을 차단하고 금융 API 수준의 인증 기반을 갖추는가

---

## 인증 (Spring Security + JWT)

모든 결제 API는 JWT Bearer 토큰이 필요합니다. 아래 흐름으로 동작합니다.

```
클라이언트                        AtomPay
   │                               │
   │  POST /api/v1/auth/login       │
   │  { username, password }  ───►  │  자격 증명 검증 (BCrypt)
   │                          ◄───  │  { accessToken: "eyJ..." }
   │                               │
   │  Authorization: Bearer eyJ…   │
   │  POST /api/v1/payments/authorize ──► JwtAuthenticationFilter 검증
   │                                       → SecurityContext 주입
   │                                       → PaymentController 처리
```

### 보안 설계 포인트

| 항목 | 내용 |
|------|------|
| 알고리즘 | HS256 (JJWT 0.12) |
| 세션 전략 | STATELESS — 서버에 세션 없음 |
| 토큰 만료 | 1시간 |
| 비밀번호 저장 | BCryptPasswordEncoder |
| CSRF | REST API 특성상 비활성화 |
| 공개 엔드포인트 | `/api/v1/auth/**`, `/swagger-ui/**`, `/actuator/health` |
| 보호 엔드포인트 | 그 외 모든 API |
| 시크릿 관리 | `JWT_SECRET` 환경변수로 주입, 미설정 시 개발용 기본값 폴백 |
| 레이트 리밋 | 결제 API(`/api/v1/payments/**`)는 actor(인증된 username, 미인증 요청은 `anonymous` 버킷)당 10초에 30건, 로그인(`/api/v1/auth/login`)은 remote IP당 분당 10건으로 제한, 초과 시 429 + `Retry-After` |
| 감사 로그 | 승인/매입/취소/환불의 성공·실패를 모두 actor·시각·금액과 함께 기록 (`GET .../audit-log`), 실패 기록은 `REQUIRES_NEW`로 별도 커밋 |

### 테스트 계정

| username | password | role |
|----------|----------|------|
| admin | password123 | ADMIN |

---

## 결제 상태 머신

결제 로직을 흩어진 if 분기가 아니라 하나의 상태 머신으로 모델링했습니다.
상태 전이 규칙은 서비스 레이어가 아니라 도메인 엔티티 내부(`capture()`, `cancel()`, `refund()` 등)에 두어,
상태 일관성을 한 곳에서 강제합니다.

| 시작 상태 | 이벤트 | 결과 상태 | 비고 |
|-----------|--------|-----------|------|
| AUTHORIZED | capture | CAPTURED | 매입은 승인 금액 전체만 가능 |
| AUTHORIZED | cancel | CANCELLED | 매입 이전에만 가능 |
| CAPTURED | partial refund | PARTIALLY_REFUNDED | 매입 후 일부 환불 |
| PARTIALLY_REFUNDED | partial refund | PARTIALLY_REFUNDED | 누적 환불 금액 관리 |
| CAPTURED | refund | REFUNDED | 전액 환불 |
| PARTIALLY_REFUNDED | refund | REFUNDED | 남은 잔액 전액 환불 |

### 핵심 도메인 규칙

- `cancel`은 `AUTHORIZED`에서만 가능합니다. 매입(`CAPTURED`) 이후에는 취소가 아니라 환불 경로로만 처리됩니다.
- `refund` / `partial refund`는 `CAPTURED` 또는 `PARTIALLY_REFUNDED`에서만 허용됩니다.
- 환불은 `Authorization.refundedAmount`를 누적 관리하며, 누적 환불액이 매입액을 초과할 수 없습니다.
- 금액은 부동소수점 오차를 피하기 위해 `BigDecimal` 기반으로 다루고, `capture액 = 승인액`, `누적 환불 ≤ 매입액` 불변식을 엔티티에서 강제합니다.
- 불법 전이(예: `CAPTURED`에서 `cancel`)는 예외를 던져 차단합니다.
- **부분환불이 잔액을 정확히 소진시키는 경계**: `partialRefund`는 남은 환불 가능액과 같거나 큰 금액을 명시적으로 거부합니다(`Authorization.partialRefund`). 잔액을 전부 비우는 요청은 `PARTIALLY_REFUNDED`가 아니라 `refund` 경로로 유도되어 `REFUNDED`로 귀결되게 했습니다 — "환불액이 매입액과 같아지면 그게 부분환불인가 전액환불인가"라는 애매함을 API 계약(엔드포인트 선택)으로 해소한 것입니다.
- **0원 승인(카드 검증)**: 승인 금액 0은 카드 유효성만 확인하고 한도는 건드리지 않는 별도 도메인 케이스로 취급합니다(카드사가 계좌 검증·토큰화 시 실제로 쓰는 방식). `CardAccount.deductAvailableAmount`/`increaseAvailableAmount`는 여전히 양수만 받으므로, 서비스 레이어(`PaymentService`)에서 금액이 0이면 이 호출 자체를 건너뜁니다 — 카드 상태(`ACTIVE`/`BLOCKED`) 검증은 금액과 무관하게 항상 수행되므로 BLOCKED 카드는 0원 승인도 그대로 거부됩니다.

---

## 설계하며 내린 결정들

이 프로젝트는 처음부터 완성형으로 짠 것이 아니라, 문제를 재현하고 → 해결하고 → 같은 문제가 다른 곳에도 있는지 확인하는 과정을 반복하며 발전시켰습니다.

### 1. 동시성: 먼저 "깨지는 것"을 재현하고 락을 적용

초기 구현에는 락이 없었고, 동시에 들어온 승인 요청이 같은 한도를 각자 읽고 차감해 한도가 음수로 깨지는 현상이 발생했습니다.
이를 테스트로 먼저 재현한 뒤, `PESSIMISTIC_WRITE`(비관적 락)를 적용해 해결했습니다.

- **비관적 락 vs 낙관적 락**: 한도 차감은 동시 충돌이 잦은 경로라, 충돌 시 재시도 비용이 큰 낙관적 락보다 비관적 락이 적합하다고 판단했습니다.

### 2. 같은 레이스가 환불에도 있었다 — 양방향으로 확장

처음에는 승인(`CardAccount` 한도)에만 락을 걸었지만,
부분환불에서 `Authorization.refundedAmount`를 누적 갱신하는 경로에도 동일한 레이스가 있음을 확인했습니다.
승인과 환불 양쪽 모두 락을 적용하고, 각각을 테스트로 검증했습니다.

### 3. 멱등성: "키 조회 후 처리"의 허점을 막기

단순히 "키가 있으면 스킵, 없으면 처리"는 **TOCTOU** 취약점이 있습니다.
`idempotency_key`에 DB unique 제약을 걸어 동시 도착 시 두 번째 요청이 제약 위반으로 걸러지게 하고,
완료된 요청의 응답 페이로드를 함께 저장해 재시도 시 재처리 없이 동일 응답을 반환하도록 했습니다.

### 4. CardAccount 복원 경로의 락 누락 — 발견하고 수정

취소·환불 경로에서 `CardAccount.availableAmount`를 복원할 때 락 없이 읽고 쓰는 문제가 있었습니다.
같은 카드의 서로 다른 두 승인이 동시에 취소되면 한 쪽 복원이 유실됩니다.
`findByCardIdForUpdate`로 교체해 모든 경로에서 CardAccount 락을 일관되게 적용했습니다.

### 5. 동시성 검증 환경: H2가 아니라 MySQL

동시성 테스트를 H2에서 돌리면 MySQL InnoDB의 `SELECT ... FOR UPDATE` 동작 차이 때문에
실제 운영 환경에서의 정합성을 증명하지 못합니다.
MySQL Testcontainers로 실제 InnoDB 위에서 레이스를 재현·검증했습니다.

### 6. 스키마 버전 관리: Flyway

`ddl-auto: update`는 프로덕션에서 예측 불가능한 DDL을 실행할 수 있습니다.
MySQL 프로파일에서는 Flyway를 활성화해 스키마 변경 이력을 버전 파일(`V1__create_schema.sql`, `V2__create_users_table.sql`)로 관리합니다.
H2 개발 환경은 `ddl-auto: create-drop`으로 빠른 개발 사이클을 유지합니다.

### 7. 멱등성 재조회의 스냅샷 함정 — 실제 MySQL에서 발견

부하 테스트 환경을 처음으로 실제 MySQL(Flyway + `ddl-auto: validate`)에 올려 보니, 새 멱등성 키로 보낸 요청이 실제 HTTP 호출 3회 모두 실패했습니다 — 타이밍에 따라 가끔 터지는 레이스가 아니라, InnoDB REPEATABLE READ 스냅샷의 구조적 귀결이라 매 요청마다 재현됩니다.
원인은 `handleIdempotentRequest`가 (1) 트랜잭션 시작 시 키를 한 번 조회하고 → (2) `REQUIRES_NEW`로 분리된 트랜잭션에서 placeholder를 커밋하고 → (3) 같은 바깥 트랜잭션에서 다시 조회하는 구조였는데,
(3)의 재조회가 (1)에서 이미 고정된 스냅샷을 그대로 쓰기 때문에 방금 커밋된 placeholder가 "없는 것"으로 보였던 것입니다.
H2와 MySQL Testcontainers 테스트 어느 쪽도 이 경로를 실제로 검증하지 못해 지금까지 발견되지 않았습니다.
재조회를 `PESSIMISTIC_WRITE` 락 조회(`findByKeyValueForUpdate`)로 바꿔 스냅샷을 우회하고 항상 최신 커밋을 읽도록 수정했고, 회귀 방지용 테스트(`mySqlShouldSucceedOnFreshIdempotencyKey`)를 추가했습니다. (자세한 경위: [docs/loadtest-results.md](docs/loadtest-results.md))

### 8. 보안 재점검: 레이트 리밋 · 감사 로그 · 시크릿 외부화

"보안"을 프로젝트의 네 기둥 중 하나로 내세운 이상, 실제로 뚫어보고 구멍을 메우는 과정이 필요하다고 판단했습니다.

- **레이트 리밋 부재**: 직전 부하 테스트로 954 req/s가 그대로 들어간다는 걸 스스로 증명해버렸습니다. `/api/v1/payments/**`에 actor(인증된 username)당 고정 윈도우 카운터를 적용해 무차별 승인 시도를 차단합니다. 단일 인스턴스 인메모리 구조라 다중 인스턴스 배포 시에는 Redis 같은 공유 스토어가 필요하다는 한계를 그대로 남겨뒀습니다.
- **감사 로그 부재**: `X-Request-Id` 상관관계 추적은 있었지만, "누가 승인/취소했는가"라는 금융 감사 관점의 기록은 없었습니다. 비즈니스 원장(`payment_transaction`)과는 별도로 `audit_log` 테이블에 actor·action·금액·요청ID를 기록하고, 조회 API(`GET .../audit-log`)로 노출했습니다.
- **JWT 시크릿 하드코딩**: `JWT_SECRET` 환경변수로 주입하고, 미설정 시에만 기존 개발용 기본값으로 폴백하도록 바꿨습니다.
- **덤으로 발견한 버그**: 이 작업을 실제 HTTP로 검증하는 과정에서, `@PathVariable` 엔드포인트 전체(`getPayment`, `capture`, `cancel`, `refund`, 신규 `audit-log` 등)가 Maven 빌드에 `-parameters` 컴파일 옵션이 없어 파라미터 이름을 못 읽고 400을 던지는 걸 발견했습니다. 서비스 계층 테스트만으로는 절대 안 잡히는 종류의 버그입니다. `pom.xml`에 `<parameters>true</parameters>`를 추가해 해결했습니다.

### 9. 8번을 다시 뜯어보고 남은 구멍 세 개를 마저 메움

8번을 끝내고 다시 읽어보니 스스로 앞뒤가 안 맞는 부분들이 있었습니다.

- **로그인에는 레이트 리밋이 없었다**: `/api/v1/payments/**`만 막아뒀는데, 무차별 대입의 표준 표적은 결제 API가 아니라 로그인입니다. 결제 API는 JWT 없이는 애초에 401이라 토큰 없는 공격자는 진입도 못 하는데, 인증을 뚫으려는 시도 자체는 무제한으로 열려 있었던 셈입니다. `RateLimitFilter`가 `/api/v1/auth/login`도 함께 보도록 확장하고, actor를 알 수 없는 시점이라 remote IP 기준으로 분당 10건 제한을 별도로 걸었습니다(같은 `RateLimiter`를 재사용, 인스턴스만 분리).
- **감사 로그에 성공 사례만 남았다**: 한도 초과, BLOCKED 카드, 불법 상태 전이 같은 실패 시도가 이상거래 관점에서는 오히려 더 중요한데 하나도 안 남고 있었습니다. `AuditLog`에 `success`/`failureReason`을 추가하고, 실패 시에는 `AuditLogService.recordFailure`를 `REQUIRES_NEW`로 별도 커밋해 기록합니다 — 실패로 비즈니스 트랜잭션이 롤백돼도 그 시도 자체의 감사 기록은 살아남아야 하기 때문입니다(성공 기록은 반대로 비즈니스 트랜잭션에 그대로 참여시켜, 결제와 원자적으로 커밋되게 유지했습니다).
- **회귀 테스트가 없었다**: 7번(멱등성 스냅샷 버그)과 8번의 `-parameters` 버그 둘 다, 고쳤다는 사실만 적어두고 재발 방지 테스트는 없었습니다. 전자는 `PaymentServiceMySqlConcurrencyTest.mySqlShouldSucceedOnFreshIdempotencyKey`로, 후자는 서비스 계층이 아니라 실제 Spring MVC 디스패치를 태우는 `PaymentControllerHttpTest`(MockMvc)로 각각 추가했습니다. 서비스 계층 테스트만으로는 원래 두 버그 다 못 잡는 종류였기 때문에, 검증 계층 자체를 하나 늘린 셈입니다.

### 10. 기술적 예외만 있고 카드 결제 도메인 예외는 없었다

동시성 락, 멱등성 재조회, 트랜잭션 경계 — 지금까지 다룬 예외는 전부 "어떤 도메인이든 똑같이 생기는" 기술적 예외였습니다. 카드 결제라는 도메인 자체에서만 나오는 판단은 없었습니다.

- **0원 승인(카드 검증)**: 카드사는 실제로 금액 0인 승인을 카드 유효성 검증(계좌 검증, 토큰화)에 씁니다 — 돈은 안 움직이지만 카드 상태(BLOCKED 여부 등)는 그대로 검증해야 하는, 일반적인 "금액 검증" 로직과는 다른 케이스입니다. 기존 코드는 `@Positive` 검증으로 0을 음수와 똑같이 걷어차 이 케이스 자체가 존재할 수 없었습니다. `@PositiveOrZero`로 바꾸고, `PaymentService`에서 금액이 0이면 `CardAccount`의 한도 차감/복원 호출 자체를 건너뛰도록(그 메서드들은 여전히 양수만 받음) 분기했습니다. 카드 상태 검증은 금액과 무관하게 항상 먼저 실행되므로, BLOCKED 카드는 0원 승인도 그대로 거부됩니다.
- **부분환불/전액환불 경계는 이미 해결되어 있었다**: `partialRefund`가 남은 환불 가능액과 같거나 큰 금액을 명시적으로 거부하고, 전액 환불은 `refund` 엔드포인트로만 가능하게 되어 있어 "환불 후 잔액이 0이면 REFUNDED인가 PARTIALLY_REFUNDED인가"라는 경계가 API 계약 수준에서 이미 정리돼 있었습니다. 새로 구현할 게 아니라, 왜 이렇게 설계했는지를 이 문서에 명시하는 게 남은 일이었습니다.

---

## 검증 (테스트)

| 테스트 | 검증 내용 |
|--------|-----------|
| 동시성 재현 (MySQL Testcontainers) | 락 제거 시 동시 요청으로 한도/환불 금액이 깨지는 것을 재현하고, 락 적용 시 최종 금액이 정확함을 검증 |
| 멱등성 재시도 | 같은 키 재시도 시 중복 처리 없이 동일 응답 반환, 다른 본문 동일 키는 거부 |
| 상태 전이 | 허용된 전이만 성공하고, capture 후 cancel 등 불법 전이 4종은 예외로 차단됨 |
| 카드 상태 검사 | BLOCKED 카드로 승인 시도 시 거부 |
| 0원 승인(카드 검증) | 0원 승인은 한도를 차감하지 않고 성공하며, BLOCKED 카드는 금액이 0이어도 그대로 거부됨을 검증. 0원 승인의 capture(0)·cancel도 한도 필드를 건드리지 않고 정상 동작함을 검증 |
| 원장(ledger) 조회 | 거래 기록을 수정 없이 append-only로 쌓고, 히스토리로 조회 |
| 감사 로그 | 인증된 actor로 결제를 수행하면 actor가 정확히 기록되고, 미인증 컨텍스트에서는 `system`으로 폴백. 한도 초과·불법 상태 전이 같은 실패 시도도 `success=false`와 사유가 함께 기록됨을 검증 |
| 레이트 리밋 | 고정 윈도우 카운터가 한도 도달 후 거부하고 윈도우 경과 후 리셋됨을 순수 유닛 테스트로, `RateLimitFilter`가 실제로 결제/로그인 경로에 걸려 429를 돌려주는지는 MockMvc로 각각 검증 |
| HTTP 레이어 회귀 (MockMvc) | 서비스 계층 테스트로는 못 잡는 종류의 버그(예: `-parameters` 누락으로 인한 `@PathVariable` 400) 방지용, 실제 Spring MVC 디스패치로 승인→조회→감사로그 흐름을 검증 |
| 멱등성 스냅샷 회귀 (MySQL Testcontainers) | 7번에서 고친 REPEATABLE READ 스냅샷 버그가 재발하지 않는지, 새 멱등성 키로 승인이 성공하는지 검증 |

> **해결된 실패 (2026-08-25)**: `PaymentServiceMySqlConcurrencyTest`의 `mySqlShouldPreventConcurrentPartialRefundOverRefund`와 `mySqlShouldPreserveAllRestoresOnConcurrentCancels` 2건이 15초 타임아웃으로 실패하던 문제를 수정했습니다.
> **원인이었던 것**: 실제 동시성 버그가 아니라 테스트 설계 문제였습니다. `@DataJpaTest`가 테스트 메서드 전체를 하나의 미커밋 트랜잭션으로 감싸는데, 이 두 테스트는 메인 스레드에서 사전 승인/매입을 먼저 실행합니다 — 그 트랜잭션이 아직 열려 있는 채로 백그라운드 스레드 2개를 띄우니, 백그라운드 스레드가 메인 스레드가 쥐고 있는(그리고 절대 커밋하지 않는) 행 락을 영원히 기다리게 됩니다.
> **수정**: 테스트 클래스에 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`를 적용해 테스트 자체가 트랜잭션을 감싸지 않도록 바꾸고, 트랜잭션 시작 전에만 실행되던 `@BeforeTransaction` 셋업을 매 테스트 전에 실행되는 `@BeforeEach`로 교체했습니다. 이제 메인 스레드의 사전 호출도 실제로 커밋되어 락이 정상적으로 풀리고, 4개 테스트 모두 통과합니다(약 12초, 이전엔 실패 2건이 각 15초 타임아웃으로 소요).

---

## 부하 테스트: 락이 처리량에 미치는 영향

"비관적 락을 걸면 처리량이 떨어지지 않는가?"에 실측으로 답하기 위해 k6로 벤치마크했습니다. (전체 방법론과 원자료: [docs/loadtest-results.md](docs/loadtest-results.md))

> **측정 시점**: 아래 수치는 레이트 리밋 도입 **이전** 커밋 기준입니다. 지금 코드로 그대로 재현하면 actor당 10초 30건 제한에 막혀 대부분 429가 뜹니다. 재현하려면 `SecurityConfig`에서 `RateLimitFilter` 등록을 잠시 빼거나, VU마다 다른 계정으로 로그인해 서로 다른 레이트 리밋 버킷을 쓰게 하세요.

| 시나리오 | 락 | 처리량 | 평균 지연 |
|---|---|---|---|
| 같은 카드에 요청 집중 (최악의 경합) | 있음 (현재 코드) | 138 req/s | 457ms |
| 카드 100개에 요청 분산 (현실적 분산) | 있음 (현재 코드) | 954 req/s | 70ms |
| 같은 카드에 요청 집중 | 없음 (로컬 실험용, 커밋 안 함) | 193 req/s | 342ms |

- 카드를 분산하면 락을 켠 채로도 **약 6.9배** 처리량이 나옵니다 — 실제 카드사 트래픽은 카드 수가 많아 한 로우에 경합이 몰릴 일이 드뭅니다.
- 최악의 경합 상황에서 락 제거가 벌어주는 처리량은 **약 40%**로, 커넥션 풀 크기를 늘렸을 때의 개선(경합 시나리오 기준 1.75 → 138 req/s, 약 79배)에 비하면 작습니다. 즉 병목은 락이 아니라 풀 크기였고, 락은 그 다음 문제였습니다.
- 락을 빼면 `PaymentServiceMySqlConcurrencyTest`가 검증하는 바로 그 레이스(한도 정합성 붕괴)가 재현되므로, 이 40%는 의도적으로 지불하는 비용입니다.

---

## API

### 인증

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/auth/login` | 로그인 → JWT 토큰 발급 |

### 결제 (JWT 필요)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/payments/authorize` | 결제 승인 |
| POST | `/api/v1/payments/{authorizationId}/capture` | 매입 |
| POST | `/api/v1/payments/{authorizationId}/cancel` | 승인 취소 (매입 전) |
| POST | `/api/v1/payments/{authorizationId}/partial-refund` | 부분 환불 (매입 후) |
| POST | `/api/v1/payments/{authorizationId}/refund` | 전액 환불 (매입 후) |
| GET | `/api/v1/payments/{authorizationId}` | 상태·승인 금액·누적 환불액 조회 |
| GET | `/api/v1/payments/{authorizationId}/transactions` | 거래 히스토리 조회 |
| GET | `/api/v1/payments/{authorizationId}/audit-log` | 감사 로그 조회 (누가·언제·무엇을) |

- 모든 상태 변경 요청에는 `Idempotency-Key` 헤더가 필요합니다.
- 외부 식별자(`authorizationId`)는 UUID를 사용해 IDOR를 방지합니다.
- `/api/v1/payments/**`는 actor당 10초에 30건, `/api/v1/auth/login`은 remote IP당 분당 10건으로 레이트 리밋됩니다 (초과 시 429).
- 요청마다 `X-Request-Id` 헤더를 응답으로 반환합니다.

---

## 아키텍처

```
controller   # REST 엔드포인트, Bean Validation(@Valid), OpenAPI 문서
filter       # CorrelationIdFilter — X-Request-Id MDC 주입
             # JwtAuthenticationFilter — Bearer 토큰 검증 및 SecurityContext 주입
             # RateLimitFilter — 결제 API actor별 고정 윈도우 레이트 리밋
security     # JwtTokenProvider — 토큰 생성/검증
             # RateLimiter — 순수 카운터 로직 (Spring 비의존, 단위 테스트 용이)
config       # SecurityConfig — 필터 체인, 공개/보호 경로 구성
             # OpenApiConfig — Swagger bearerAuth 스키마 설정
             # DataInitializer — 초기 데이터 시딩 (CARD-001, admin 계정)
service      # 트랜잭션 경계, 락 획득, 멱등성 처리 조율, 감사 로그 기록(AuditLogService)
repository   # JPA, 비관적 락 조회(findByAuthorizationIdForUpdate 등)
domain
 ├─ entity   # 상태 전이 메서드(capture/cancel/refund)와 불변식을 보유
 └─ enum     # 결제 상태 / 카드 상태 / 사용자 역할 정의
dto
exception    # GlobalExceptionHandler 기반 일관된 예외 응답
db/migration # Flyway 스키마 버전 파일
```

---

## 실행

### 개발용 (H2, Flyway 비활성화)

```bash
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

기본 주소: `http://localhost:8080`  
Swagger UI: `http://localhost:8080/swagger-ui.html`  
Health 체크: `http://localhost:8080/actuator/health`

### Swagger UI에서 결제 API 테스트하기

1. `POST /api/v1/auth/login` 실행 (`admin` / `password123`)
2. 반환된 `accessToken` 복사
3. 우상단 **Authorize** 버튼 클릭 후 토큰 입력
4. 결제 API 테스트

### MySQL (Flyway 활성화)

`src/main/resources/application-mysql.properties`에 연결 정보를 설정한 뒤 실행합니다.

```bash
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql
```

애플리케이션 기동 시 Flyway가 자동으로 스키마를 생성합니다.

### 테스트

```bash
# 전체 테스트 (커밋 전 항상 이걸로 — 개별 -Dtest= 필터만 돌리면
# 다른 테스트 클래스의 @Import 누락 같은 컨텍스트 로딩 실패를 놓칠 수 있음)
.\mvnw.cmd clean test

# 단위 테스트만 (H2, Docker 불필요, 빠른 반복용)
.\mvnw.cmd test -Dtest=PaymentServiceTest

# 동시성 테스트만 (MySQL Testcontainers, Docker 필요)
.\mvnw.cmd test -Dtest=PaymentServiceMySqlConcurrencyTest
```

`PaymentServiceMySqlConcurrencyTest`를 포함해 전체 27개 테스트가 통과합니다 — 이전에 실패했던 2건의 원인과 수정 내역은 [검증 (테스트)](#검증-테스트) 섹션 참고.

---

## 의도적으로 다루지 않은 것

프로젝트의 초점을 흐리지 않기 위해 다음은 범위에서 제외했습니다.
프론트엔드, 실제 PG/카드사 망 연동, 정산·대사, 회원 관리.
이들 없이도 결제 처리 코어의 정합성이라는 주제를 온전히 다룰 수 있다고 판단했습니다.

---

## 포트폴리오 맥락

- **AtomPay** — 결제가 정확하게 처리되는가 (트랜잭션 코어: 동시성·멱등성·정합성·보안)
- **InfraPulse** — 수상한 거래를 탐지·분석하는가 (룰 + ML 이상탐지, 금융 규제 매핑)
