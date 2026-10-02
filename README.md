# AtomPay

카드 결제 처리 코어를 단순화하여 구현한 Spring Boot 백엔드입니다.
승인(authorization) → 매입(capture) → 취소(cancel) / 환불(refund) 의 결제 라이프사이클을,
동시성 정합성 · 멱등성 · 상태 전이 · 보안 네 가지 관점에서 다루는 데 집중했습니다.

화면(프론트엔드)이나 외부 PG 연동 없이, 결제 처리 코어 로직 그 자체의 정확성에 집중한 프로젝트입니다.
"기능이 동작한다"가 아니라 "동시 요청·재시도·중간 실패 상황에서도 금액 정합성이 깨지지 않는다"를 목표로 했습니다.

### 핵심 3줄 요약

1. **동시성** — 락 획득 순서를 `멱등성 키 → Authorization → CardAccount`로 고정하고, 행 락 데드락과 **커넥션 풀 교착**까지 MySQL 위에서 재현한 뒤 테스트로 막았습니다. ([15번](#15-락-획득-순서와-데드락--행-락-말고-커넥션-풀도-락이었다))
2. **멱등성** — "처리 중인 키"를 상태 컬럼이나 타임아웃이 아니라 **DB 행 락의 생존 여부**로 판단합니다. 커밋 실패나 프로세스 사망 뒤에도 키가 잠기지 않고, 재시도는 정확히 한 번만 실행됩니다. ([13번](#13-멱등성-키가-영구히-잠기는-구멍--정리-코드가-아니라-락으로-소유권을-표현))
3. **검증 인프라** — 운영 프로파일(MySQL + Flyway + `ddl-auto=validate`)을 CI에서 매 push마다 실제로 띄우고 HTTP로 결제 전 과정을 검증합니다. 회귀 테스트는 "수정을 되돌리면 실패하는가"로 확인합니다. ([14번](#14-운영-프로파일을-ci에서-매번-실제로-띄운다))

---

## 한눈에 보기

| 항목 | 내용 |
|------|------|
| 스택 | Spring Boot 3.2 · Spring Security · Spring Data JPA · MySQL (개발 시 H2) · Java 21 |
| 핵심 주제 | 동시성 제어 · 멱등성 · 결제 상태 머신 · JWT 인증 |
| 인증 | Spring Security + JWT (HS256), Stateless 세션 |
| 스키마 관리 | Flyway 버전 마이그레이션 (MySQL 프로파일) |
| API 문서 | Swagger UI — `http://localhost:8080/swagger-ui.html` |
| 검증 | 자동 테스트 58개 = 단위 47개(서비스 38 + 레이트리밋 4 + HTTP 레이어 5) + MySQL Testcontainers 11개(`PaymentServiceMySqlConcurrencyTest` 10 + `ConnectionPoolStarvationMySqlTest` 1), 별도로 운영 프로파일 HTTP 스모크 테스트 |
| 배포 · CI | Dockerfile + Docker Compose(MySQL), GitHub Actions에서 전체 테스트 + 운영 프로파일 기동 스모크 테스트를 매 push마다 실행 |
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
| 시크릿 관리 | `JWT_SECRET` 환경변수로 주입. 개발(H2) 프로파일만 개발용 기본값으로 폴백하고, 운영(`mysql`) 프로파일은 미설정 시 **기동 자체를 실패**시킴 — 공개 저장소에 있는 기본 키로 서명된 토큰이 운영에서 통과하는 일을 막기 위해 |
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
| AUTHORIZED | capture | CAPTURED | 매입액 ≤ 승인액 (부분 매입 가능, 상세는 아래 참고) |
| AUTHORIZED | cancel | CANCELLED | 매입 이전에만 가능 |
| CAPTURED | partial refund | PARTIALLY_REFUNDED | 매입 후 일부 환불 |
| PARTIALLY_REFUNDED | partial refund | PARTIALLY_REFUNDED | 누적 환불 금액 관리 |
| CAPTURED | refund | REFUNDED | 전액 환불 |
| PARTIALLY_REFUNDED | refund | REFUNDED | 남은 잔액 전액 환불 |

### 핵심 도메인 규칙

- `cancel`은 `AUTHORIZED`에서만 가능합니다. 매입(`CAPTURED`) 이후에는 취소가 아니라 환불 경로로만 처리됩니다.
- `refund` / `partial refund`는 `CAPTURED` 또는 `PARTIALLY_REFUNDED`에서만 허용됩니다.
- 환불은 `Authorization.refundedAmount`를 누적 관리하며, 누적 환불액이 매입액을 초과할 수 없습니다.
- 금액은 부동소수점 오차를 피하기 위해 `BigDecimal` 기반으로 다루고, `capture액 ≤ 승인액`, `누적 환불 ≤ 매입액` 불변식을 엔티티에서 강제합니다.
- 불법 전이(예: `CAPTURED`에서 `cancel`)는 예외를 던져 차단합니다.
- **부분 매입(partial capture)**: 매입액이 승인액보다 적을 수 있습니다. 매입되지 않은 차액은 `CardAccount`로 즉시 복원되고, 한 번 매입된 승인은 다시 매입할 수 없습니다. 배경과 설계 판단은 "설계하며 내린 결정들" 11번 참고.
- **부분환불이 잔액을 정확히 소진시키는 경계**: `partialRefund`는 남은 환불 가능액과 같거나 큰 금액을 명시적으로 거부합니다(`Authorization.partialRefund`). 잔액을 전부 비우는 요청은 `PARTIALLY_REFUNDED`가 아니라 `refund` 경로로 유도되어 `REFUNDED`로 귀결되게 했습니다 — "환불액이 매입액과 같아지면 그게 부분환불인가 전액환불인가"라는 애매함을 API 계약(엔드포인트 선택)으로 해소한 것입니다.
- **0원 승인(카드 검증)**: 승인 금액 0은 카드 유효성만 확인하고 한도는 건드리지 않는 별도 도메인 케이스로 취급합니다(카드사가 계좌 검증·토큰화 시 실제로 쓰는 방식). `CardAccount.deductAvailableAmount`/`increaseAvailableAmount`는 여전히 양수만 받으므로, 서비스 레이어(`PaymentService`)에서 금액이 0이면 이 호출 자체를 건너뜁니다 — 카드 상태(`ACTIVE`/`BLOCKED`) 검증은 금액과 무관하게 항상 수행되므로 BLOCKED 카드는 0원 승인도 그대로 거부됩니다.
- **감면 0원 승인(예: 국가유공자 감면)**: 위 검증용 0원 승인과 이름은 비슷하지만 다른 케이스입니다 — 검증 승인은 거래가 아니지만, 감면 0원은 실제 거래인데 청구액만 0입니다. `AuthorizationKind`(`STANDARD`/`VERIFICATION`/`DISCOUNTED_ZERO`)로 명시적으로 구분하고, 원장에 원금액·감면액·사유 코드를 보존합니다. 감면 자격 판정은 가맹점의 책임이고 결제 코어는 정합성만 검증합니다. 배경과 설계 판단은 "설계하며 내린 결정들" 12번 참고.

---

## 설계하며 내린 결정들

이 프로젝트는 처음부터 완성형으로 짠 것이 아니라, 문제를 재현하고 → 해결하고 → 같은 문제가 다른 곳에도 있는지 확인하는 과정을 반복하며 발전시켰습니다.
아래 본문은 그 과정을 **시간순**으로 남긴 기록이라, 주제별로 보려면 이 색인을 먼저 보세요. 먼저 읽을 것은 **굵게** 표시했습니다.

| 주제 | 결정 |
|------|------|
| 동시성·락 | 1 비관적 락 도입 · 2 환불 경로 확장 · 4 복원 경로 락 누락 · 11 부분 매입 복원 경로 · **15 락 순서·데드락·커넥션 풀 교착** |
| 멱등성 | 3 unique 제약 + 응답 저장 · 7 REPEATABLE READ 스냅샷 함정 · **13 키 소유권을 행 락으로** |
| 보안 | 8 레이트 리밋·감사 로그·시크릿 · 9 로그인 리밋·실패 감사 로그 |
| 카드 결제 도메인 | 10 0원 승인(카드 검증) · 11 부분 매입 · 12 감면 0원 승인 |
| 검증 인프라 | 5 MySQL Testcontainers · 6 Flyway · **14 운영 프로파일 CI 기동** |

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
(이 설계에는 "키가 영구히 잠기는" 구멍이 남아 있었고, 13번에서 재설계했습니다.)

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
MySQL 프로파일에서는 Flyway를 활성화해 스키마 변경 이력을 버전 파일로 관리합니다. 현재 V1(스키마) · V2(사용자) · V3(감사 로그) · V4(감면 필드, 12번) · V5(멱등성 키 상태·actor 스코프, 13번)까지 있으며, 운영 프로파일은 `ddl-auto=validate`로 엔티티와 마이그레이션 결과가 어긋나면 기동을 거부합니다.
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
- **JWT 시크릿 하드코딩**: `JWT_SECRET` 환경변수로 주입하고, 미설정 시에만 기존 개발용 기본값으로 폴백하도록 바꿨습니다. (이후 14번에서 운영 프로파일은 폴백 없이 기동 실패하도록 강화)
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

> **참고**: 0원 승인은 "국가유공자 0원 결제"류의 결제금액 감면(비즈니스 규칙)과는 다릅니다. 결제금액 자체가 규칙에 의해 0이 되는 게 아니라, 카드망이 실제로 쓰는 "금액을 잡지 않고 카드 유효성만 확인하는" 별도 거래 유형입니다. 이름은 같아 보여도 성격이 다른 케이스라 구분해 둡니다.

### 11. 부분 매입(partial capture) — capture액 = 승인액 불변식을 깨고 다시 검증

10번에서 다룬 예외들은 이미 있던 로직의 경계를 확인하거나(부분환불), 검증 규칙 하나를 완화하는 수준(0원 승인)이었습니다. 부분 매입은 더 근본적입니다 — `capture액 = 승인액`이라는, 이 프로젝트의 상태 머신을 떠받치던 불변식 자체를 깨야 하는 케이스입니다.

실제 카드 결제에서는 승인액과 매입액이 다른 경우가 흔합니다. 주문 후 일부 품목이 품절돼 축소 매입되거나, 호텔·렌터카처럼 예상 금액으로 먼저 승인하고 실제 이용액으로 매입하는 경우입니다. 여기서 핵심은 "매입액이 승인액보다 작을 때 남는 차액을 어떻게 처리하느냐"입니다 — 취소도 환불도 아닌 제3의 경로가 필요합니다.

- **불변식 재검증**: `capture액 = 승인액`을 `capture액 ≤ 승인액`으로 완화하면서, `Authorization.amount`를 실제 매입액으로 갱신하도록 바꿨습니다. 이렇게 해야 이후 환불 한도(`getRemainingRefundableAmount = amount - refundedAmount`)가 원래 승인액이 아니라 실제로 돈이 움직인 매입액을 기준으로 계산됩니다 — 승인액 기준으로 놔뒀다면 매입되지 않은 금액까지 환불 가능하다고 착각하는 버그가 생겼을 것입니다.
  - `Authorization.amount`를 덮어쓰면 원 승인액이 사라지는 것 아니냐는 감사 관점의 우려가 있었습니다. 승인액과 매입액의 괴리(30,000원 승인 후 10,000원만 매입 등)는 이상거래 탐지에서 그 자체로 의미 있는 신호이기 때문입니다. 확인해보니 `payment_transaction`(원장)과 `audit_log`는 애초에 이벤트마다 새 행을 `save(new ...)`로 추가만 하는 append-only 구조라, capture 시점에 넘기는 `amount` 파라미터(엔티티 필드가 아니라 인자 값)를 그대로 기록합니다 — AUTHORIZATION 행은 원 승인액을, CAPTURE 행은 실제 매입액을 각각 영구히 보존하므로 엔티티의 현재 상태(`amount`)가 갱신되어도 이력은 그대로 남습니다. `partialCaptureShouldPreserveOriginalAuthorizedAmountInLedgerAndAuditLog` 테스트로 이를 명시적으로 검증했습니다. 별도 필드 분리는 불필요하다고 판단했습니다.
- **동시성**: 매입 차액을 `CardAccount`에 복원하는 경로에도 락이 필요합니다. 취소/환불 복원 경로에서 락 누락을 뒤늦게 발견했던 것(4번)과 같은 종류의 실수를 반복하지 않도록, 처음부터 기존의 `findByCardIdForUpdate`(비관적 락) 경로를 그대로 재사용했습니다. 같은 레이스가 여기에도 있다는 걸 증명하기 위해 `mySqlShouldPreserveAllPartialCaptureReleasesOnConcurrentCaptures` 테스트를 추가해, 같은 카드의 서로 다른 두 승인을 동시에 부분 매입해도 두 복원분이 모두 반영됨을 MySQL Testcontainers로 검증했습니다.
- **상태 머신 판단**: 부분 매입 후 별도 상태를 두지 않고 기존 `CAPTURED`를 그대로 씁니다. 잔여 승인액을 추가로 매입할 수 있게 할지도 검토했지만, 그러면 부분환불처럼 누적 매입액을 관리해야 해서 매입 횟수 관리라는 별개의 복잡도가 하나 더 생깁니다. 이 케이스로 증명하려는 건 매입 횟수 관리가 아니라 "매입되지 않은 차액이 정확히 한 번만 복원되는가"라는 차액 복원의 정합성이므로, 매입되지 않은 잔여분은 그 자리에서 소멸(즉시 한도 복원)시키고 한 번 매입된 승인은 다시 매입할 수 없게 하는 쪽으로 범위를 좁혔습니다.

### 12. 감면 0원 승인(예: 국가유공자 감면) — 감면 "판정"이 아니라 "검증"

**감면 자격 판정은 결제 코어의 책임이 아닙니다.** 누가 국가유공자이고 얼마를 감면받는지는 가맹점·정책 시스템이 결정할 일이지, 카드 결제 코어가 알 이유가 없습니다. 여기에 자격 판정 로직을 넣었다면 "이게 왜 결제 코어에 있죠?"라는 질문에 답을 못 했을 것이고, 그건 예외 케이스를 다룬 게 아니라 결제 코어와 가맹점 사이의 경계를 흐린 것이 됩니다.

**그럼 결제 코어는 뭘 해야 하는가.** 가맹점을 신뢰하지 않는 것이 결제 코어의 기본 자세라는 관점에서, 가맹점이 보내온 감면 정보가 내적으로 일관되는지 검증하는 역할을 맡깁니다. 요청에 원금액(`originalAmount`)·감면액(`discountAmount`)·감면 사유 코드(`discountReasonCode`)가 함께 오면, `PaymentService.validateAndResolveKind`가 세 필드가 모두 있는지, `원금액 − 감면액 = 청구액`이 맞는지, 감면액이 원금액을 초과하지 않는지, 사유 코드가 `DiscountReasonCode`(현재 `NATIONAL_MERIT_RECIPIENT` 하나)에 정의된 값인지를 검사합니다. 자격 판정은 가맹점의 몫, 정합성 검증은 결제 코어의 몫입니다.

**0원이 두 종류였다.** 이미 구현한 카드 검증용 0원 승인(11번 이전, `VERIFICATION`)과 감면으로 청구액이 0이 된 이번 케이스(`DISCOUNTED_ZERO`)는 금액만 같을 뿐 성격이 다릅니다. 검증 승인은 거래가 아니라 확인이라 원장에 남길 실적이 없지만, 감면 0원은 실제 거래이고 금액만 0일 뿐이라 원장에 남아야 합니다 — 유공자 이용 실적은 정산·감사 대상이기 때문입니다. 이 둘을 같은 코드로 처리하면 안 된다는 걸 명시적으로 드러내기 위해 `AuthorizationKind`(`STANDARD`/`VERIFICATION`/`DISCOUNTED_ZERO`) 필드를 두어 구분했고, `Authorization`·`payment_transaction` 양쪽에 원금액·감면액·사유 코드를 보존해 감면 0원 승인이 검증 0원 승인과 다르게 기록됨을 `verificationAndDiscountedZeroAuthorizationsShouldBeDistinguishableInTheLedger` 테스트로 검증했습니다.

**환불에도 파장이 갔다.** 감면 거래를 환불하면 뭘 돌려주나요? 청구액이 0이니 돌려줄 돈은 없지만, 거래 자체는 취소돼야 하고 감면 실적도 되돌려야 합니다. `Authorization.refund(ZERO)`를 `kind == DISCOUNTED_ZERO`에서만 허용해 상태를 `REFUNDED`로 되돌리는 별도 경로를 추가했고(환불 금액이 없으니 `refundedAmount`는 그대로 둡니다), `CardAccount` 복원 호출은 금액이 0이면 건너뛰도록 가드를 추가했습니다. 검증용 0원 승인에는 이 경로를 열어주지 않아 `refund(0)`이 여전히 거부됨을 `verificationAuthorizationShouldRejectZeroAmountRefund`로 확인했습니다.

**검증 과정에서 찾은 별개의 버그.** 이 기능이 실제 MySQL·Flyway 경로(`mysql` 프로파일)를 새 마이그레이션(`V4__add_discount_fields.sql`)과 함께 처음으로 정말 기동시켜보는 계기가 됐는데, `application-mysql.properties`에 `spring.datasource.driver-class-name`이 애초에 커밋된 적이 없어서 base `application.properties`의 H2 드라이버를 그대로 물려받아 기동 자체가 실패했습니다. 이전 세션 기록에는 이 버그를 고쳤다고 적혀 있었지만 실제로는 파일에 반영되지 않았던 것 — MySQL 프로파일이 Testcontainers 테스트(자체적으로 드라이버를 지정)로만 검증되고 `spring-boot:run`으로 직접 기동된 적은 없었기 때문에 이 괴리가 지금까지 드러나지 않았습니다. `spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver`를 추가하고, 로컬 MySQL 컨테이너에 대해 `spring-boot:run -Dspring-boot.run.profiles=mysql`로 기동 → Flyway 마이그레이션 → 실제 HTTP로 감면 0원 승인·매입·환불까지 직접 호출해 확인한 뒤 커밋했습니다.

### 13. 멱등성 키가 영구히 잠기는 구멍 — "정리 코드"가 아니라 "락"으로 소유권을 표현

**구멍.** 3번·7번의 구조는 placeholder를 `REQUIRES_NEW`로 먼저 커밋해 두고, 연산이 실패하면 `catch` 블록에서 지우는 방식이었습니다. 그런데 `catch`가 잡을 수 있는 건 *연산 중* 실패뿐입니다. 연산이 끝난 뒤 **바깥 트랜잭션의 커밋 자체가 실패**하거나(데드락 희생자 선정, 커넥션 끊김) 처리 도중 **프로세스가 죽으면**, 결제 데이터는 롤백되는데 이미 커밋된 빈 placeholder는 그대로 남습니다. 이후 그 키로 오는 재시도는 전부 "already being processed"(400)를 받습니다 — 일시적인 것도 아니고 영원히. 클라이언트 입장에선 결제가 된 건지 안 된 건지 확인할 방법도, 다시 시도할 방법도 없어지는 상태입니다.

**재현 먼저.** 커밋 실패는 "`authorize()`가 정상 반환된 직후 바깥 트랜잭션을 롤백"으로 정확히 모델링됩니다(예외가 연산 밖에서 나므로 `catch`를 타지 않음). 이 테스트(`mySqlShouldAllowRetryAfterOuterTransactionRollsBackPastTheOperation`)는 수정 전 코드에서 재시도가 400으로 실패했습니다.

**재현하다 찾은 두 번째 버그.** 같은 키 동시 중복 요청 테스트를 수정 전 코드에 돌려보니, 패자가 400도 아니라 **500**(`UnexpectedRollbackException`)을 받았습니다. `tryReservePlaceholder`가 `REQUIRES_NEW` 트랜잭션 *안에서* 제약 위반 예외를 잡고 `false`를 반환했는데, 그 시점엔 리포지토리 프록시가 이미 트랜잭션을 rollback-only로 표시해 둬서 커밋 단계에서 터진 것입니다. 즉 "동시 요청이면 false 반환" 경로는 처음부터 한 번도 동작한 적이 없었습니다. 예외를 트랜잭션 경계 **밖**(호출자)에서 잡도록 고쳤습니다.

**재설계: 행 락이 곧 "처리 중"이라는 신호.** 정리 코드를 더 꼼꼼히 짜는 대신, 정리가 필요 없는 구조로 바꿨습니다.

1. 완료된(`COMPLETED`) 키면 캐시된 응답을 replay
2. 행이 없으면 `IN_PROGRESS` placeholder를 별도 트랜잭션으로 커밋 (동시 요청에 지면 그냥 넘어감 — 어차피 행은 있음)
3. **비즈니스 트랜잭션을 열고 그 행을 `SELECT ... FOR UPDATE`로 잠금** — 다른 요청이 소유 중이면 그 트랜잭션이 끝날 때까지 대기
4. 락을 얻은 뒤 `COMPLETED`면 동시 중복 요청이 먼저 끝난 것 → replay. `IN_PROGRESS`면 아무도 완료하지 못한 것 — 방금 내가 만들었거나, 이전 소유자의 트랜잭션이 롤백됐다는 뜻이고, 롤백됐다면 그 시도의 결제 효과도 함께 사라졌으므로 **넘겨받아 실행해도 안전**
5. 연산 결과와 `COMPLETED` 표시를 같은 트랜잭션에서 커밋 — 결제와 캐시된 응답은 함께 커밋되거나 함께 롤백

"처리 중인가"를 상태 컬럼이나 타임아웃으로 판단하지 않고 **DB가 관리하는 락의 생존 여부**로 판단하므로, 소유자가 커밋에 실패하든 죽든 DB가 트랜잭션을 정리하는 순간 키는 자동으로 회수 가능해집니다. 타임아웃 기반(예: "30초 지난 IN_PROGRESS는 회수")은 처리가 30초를 넘기면 **이중 결제**를 낼 수 있어 택하지 않았습니다. 실패 시 placeholder를 지우는 코드도 아예 없앴는데, 이 구조에서 `REQUIRES_NEW`로 지우려 하면 자기 바깥 트랜잭션이 쥔 락을 기다리는 자기 데드락이 되기 때문입니다.

**트레이드오프.** 동시 중복 요청은 이제 즉시 거절되지 않고 원 요청이 끝날 때까지 키 락을 기다립니다. 5초는 원 요청의 처리 시간이 아니라 이 **락 대기**의 상한이며(`innodb_lock_wait_timeout`, 15번), 그 안에 원 요청이 끝나지 않으면 409를 받습니다. 대신 결과가 "잠시 후 재시도하라"는 에러가 아니라 원 요청의 응답 그대로라, 클라이언트 재시도 로직이 단순해집니다.

**같이 고친 것.**
- **키를 호출자 단위로 스코프** — 키는 클라이언트가 만드는 값이라 서로 다른 호출자가 같은 키를 고를 수 있습니다. 전역 unique였을 땐 B가 A와 같은 키·같은 본문을 보내면 A의 응답(승인 정보)을 그대로 받았습니다. Stripe가 계정 단위로 키를 구분하듯 `(actor, key_value)` unique로 바꿨습니다(`V5` 마이그레이션 — 기존의 빈 placeholder는 `IN_PROGRESS`로 변환돼 이번 배포와 함께 회수 가능해짐. V4 상태의 DB에 데이터를 넣고 V5를 적용해 확인).
- **`100` vs `100.00`** — 요청 해시가 `BigDecimal.toString()`이라 같은 금액을 다르게 직렬화한 재시도가 "다른 요청"으로 거부됐습니다. 값 기준(`stripTrailingZeros().toPlainString()`)으로 정규화.
- **HTTP 의미** — 같은 키를 다른 본문에 재사용하면 422(IETF Idempotency-Key 초안 권고), 컬럼 길이(64자)를 넘는 키는 insert 시 500이 아니라 400으로 사전 차단.
- **capture vs cancel 경합 테스트** — 둘 다 `AUTHORIZED`에서 출발하는 상호 배타적 전이라 경합 시 정확히 하나만 이겨야 합니다. 기존 `Authorization` 락으로 이미 안전했고(수정 전 코드에서도 통과), 이를 증명하는 테스트로 추가했습니다.

### 14. 운영 프로파일을 CI에서 매번 실제로 띄운다

`mysql` 프로파일은 지금까지 두 번(드라이버 누락 — 7번·12번) 깨진 채로 아무 테스트에도 걸리지 않았습니다. MySQL을 쓰는 유일한 테스트인 Testcontainers가 자체 datasource 설정을 주입해서 이 파일을 전혀 읽지 않기 때문입니다. 사람이 기억해서 수동으로 띄워보는 방식으로는 같은 일이 또 생긴다고 보고, **구조적으로** 막았습니다.

- `Dockerfile`(멀티 스테이지, non-root 실행) + `docker-compose.yml`(MySQL 포함)로 운영 프로파일을 한 줄로 기동
- GitHub Actions에서 매 push마다 ① `mvn clean verify`(Testcontainers 포함 전체 테스트) ② compose로 운영 프로파일을 띄워 `scripts/smoke-test.sh`가 실제 HTTP로 라이프사이클 전체와 멱등성 계약을 검증
- 이걸 만들다 발견한 것: Spring Boot parent POM 없이 BOM만 import하는 구조라 `repackage` goal이 바인딩돼 있지 않아 `mvn package` 결과물이 **실행 불가능한 jar**였습니다(지금까지 `spring-boot:run`으로만 띄워서 드러나지 않음). goal을 명시적으로 바인딩.
- 운영 프로파일은 DB 접속 정보를 환경변수로 받고, `JWT_SECRET`에 **기본값을 두지 않아** 미설정 시 기동이 실패합니다. 개발용 기본 키는 공개 저장소에 있으므로, 운영에서 그 키로 폴백하면 누구나 유효한 토큰을 위조할 수 있습니다.

### 15. 락 획득 순서와 데드락 — 행 락 말고 커넥션 풀도 락이었다

13번에서 멱등성 키 행 락이 추가되면서 한 요청이 잡는 락이 최대 세 개가 됐습니다. 데드락은 "두 트랜잭션이 같은 두 자원을 반대 순서로 잡을 때" 생기므로, 순서를 고정하고 모든 경로가 그 순서를 지키는지 확인했습니다.

| 경로 | 락 획득 순서 |
|------|------|
| authorize | 멱등성 키 → CardAccount (새 Authorization은 insert라 락 대상 아님) |
| capture | 멱등성 키 → Authorization → CardAccount (부분 매입 차액이 있을 때만) |
| cancel / partial-refund / refund | 멱등성 키 → Authorization → CardAccount |

어떤 경로도 CardAccount를 잡은 뒤 Authorization을 잡지 않습니다. 멱등성 키는 요청마다 다르고(같은 키의 중복 요청은 다른 락을 쥐지 않은 채 키 락만 기다림), Authorization 락은 unique 인덱스 동등 조회라 갭 락이 걸리지 않습니다.

**순서를 지킨다는 걸 테스트로.** 같은 승인에 capture와 cancel을 동시에 보내는 경합 테스트를 데드락 감지기로 겸하게 했습니다. 이 둘은 같은 Authorization·CardAccount 쌍을 잡으므로, 한쪽이라도 순서가 뒤집히면 바로 이 조합에서 데드락이 납니다. 15라운드를 반복하면서 패자가 **도메인 규칙**("AUTHORIZED만 가능")으로 져야 하고 데드락 희생자로 지면 안 된다고 단언합니다. cancel이 CardAccount를 먼저 잡도록 일부러 뒤집어 보니 InnoDB가 실제로 `Deadlock found`를 내며 테스트가 실패했습니다 — 이 테스트가 실제로 데드락을 잡는다는 확인입니다.

**행 락보다 먼저 터지는 교착: 커넥션 풀.** 락 순서를 점검하다 보니 행이 아닌 자원이 하나 더 순환에 끼어 있었습니다. 결제 요청은 바깥 트랜잭션이 커넥션 하나를 쥔 채로 `REQUIRES_NEW`(키 예약, 실패 감사 로그)용 커넥션을 **하나 더** 풀에 요청했습니다. 풀 크기만큼의 요청이 동시에 첫 커넥션을 쥐면 전원이 두 번째를 기다리며 아무도 진행하지 못하고, Hikari `connectionTimeout`(30초)에서 일제히 실패합니다. 풀 3개 + 서로 다른 카드 12건으로 재현하니 **12건 전부 실패**했습니다 — 행 경합이 없고 요청 하나가 수 ms라 단순 대기열이었다면 전부 성공했어야 합니다. 기존 부하 테스트의 "풀 10에서 성공률 30%, 지연 28~30초"를 당시엔 "풀 대기열"로 해석했는데, 30초라는 숫자 자체가 대기열이 아니라 이 교착의 흔적이었습니다.

수정은 중첩을 없애는 것입니다. 결제 연산에서 바깥 `@Transactional`을 걷어내고, 키 예약 → 락을 잡는 비즈니스 트랜잭션(`TransactionTemplate`) → (실패 시) 감사 로그를 **차례로 각자의 트랜잭션에서** 실행해 한 요청이 동시에 커넥션을 둘 이상 쥐지 않게 했습니다. 실패 감사 로그는 롤백 이후에 기록되므로, 이전 구조의 트랜잭션 안 `catch`로는 잡을 수 없던 **커밋 실패**도 이제 기록됩니다. 같은 이유로 결제 연산은 이미 열린 트랜잭션 안에서 호출하면 안 된다는 제약을 코드 주석에 명시했습니다. `ConnectionPoolStarvationMySqlTest`가 이를 고정하며, `authorize`에 `@Transactional`을 다시 붙이면 12건 전부 실패로 돌아갑니다.

**락을 못 얻었을 때 클라이언트가 받는 것.** InnoDB 기본 락 대기는 50초라, 그동안 요청 스레드와 커넥션이 묶입니다. 운영 프로파일에서 `innodb_lock_wait_timeout`을 5초로 낮추고(`connection-init-sql`), 락 대기 타임아웃과 데드락 희생자는 500이 아니라 **409 + `Retry-After`**로 응답하게 했습니다. 두 경우 모두 해당 트랜잭션이 통째로 롤백되므로, 13번 구조에서는 같은 `Idempotency-Key`로 재시도하는 것이 안전하다는 게 설계상의 주장입니다. 이 주장은 두 경우 각각 **실제 MySQL에서** 확인했습니다.

- **락 대기 타임아웃**: 다른 트랜잭션이 키 락을 쥔 상태에서 요청을 보내, `PessimisticLockingFailureException` 계열로 나오고 결제가 실행되지 않으며, 락이 풀린 뒤 같은 키로 성공하는 것을 확인.
- **데드락 희생자**: 상대 트랜잭션이 cancel과 반대 순서(CardAccount → Authorization)로 락을 잡게 해서 진짜 InnoDB 데드락을 만들고, cancel이 희생되게 했습니다(InnoDB는 한 일이 적은 쪽을 롤백하므로 상대가 먼저 행을 써 둠). 희생된 cancel이 같은 예외 계열로 나오는지, 상태·한도·키에 아무것도 남지 않는지, 같은 키 재시도가 정확히 한 번 취소하는지를 확인.

**확인하다 찾은 버그.** 처음 돌린 데드락 희생자 테스트는 409가 아니라 **500**으로 실패했습니다. 실패 감사 로그가 예외 메시지를 `failure_reason`(VARCHAR 255)에 넣는데, MySQL 데드락 메시지는 SQL 문 전체를 포함해 255자를 넘습니다. 그래서 감사 로그 insert가 `DataIntegrityViolationException`으로 터지며 **원래 예외를 덮어썼고**, 실패 기록도 남지 않았습니다(메시지가 긴 실패라면 데드락이 아니어도 똑같았습니다). 사유를 컬럼 길이에 맞춰 자르고, 감사 로그 기록이 실패해도 원래 예외를 대신 던지지 않도록(로그 + suppressed로 첨부) 두 겹으로 고쳤습니다. 핸들러 매핑만 단위 테스트로 확인해 두고 "데드락도 409"라고 썼다면 놓쳤을 버그입니다 — 13번의 `UnexpectedRollbackException`과 같은 계열(부수 작업의 실패가 본래 응답을 500으로 바꿈)입니다.

**개발(H2) 프로파일은 다릅니다.** 5초 락 대기 설정은 MySQL 전용 세션 변수라 `mysql` 프로파일에만 있고, H2 개발 프로파일은 H2 자체의 락 타임아웃을 따릅니다. 409 동작은 MySQL에서만 검증했습니다.

---

## 검증 (테스트)

| 테스트 | 검증 내용 |
|--------|-----------|
| 동시성 재현 (MySQL Testcontainers) | 락 제거 시 동시 요청으로 한도/환불 금액이 깨지는 것을 재현하고, 락 적용 시 최종 금액이 정확함을 검증 |
| 멱등성 재시도 | 같은 키 재시도 시 중복 처리 없이 동일 응답 반환, 다른 본문 동일 키는 거부(HTTP 422). `100`과 `100.00`처럼 표현만 다른 같은 금액의 재시도는 replay됨. 같은 키라도 actor가 다르면 서로의 응답을 공유하지 않음. 64자 초과 키는 400 |
| 멱등성 키 영구 잠김 회귀 (MySQL Testcontainers) | 13번에서 고친 버그 — 바깥 트랜잭션이 연산 반환 **이후** 롤백(커밋 실패 모델링)돼도 같은 키 재시도가 성공하고, 첫 시도의 효과는 남지 않아 한도가 한 번만 차감됨을 검증. 같은 키 동시 중복 요청 2건은 둘 다 성공하고 같은 `authorizationId`를 받으며 실제 승인은 1건임을 검증 |
| capture vs cancel 경합 · 데드락 감지 (MySQL Testcontainers) | 같은 승인에 매입과 취소가 동시에 들어오면 정확히 하나만 성공하고, 카드 한도·최종 상태·원장이 이긴 쪽과 일치함을 15라운드 반복 검증. 패자는 도메인 규칙으로 져야 하며 데드락 희생자면 실패 — 락 순서를 뒤집으면 실제 InnoDB 데드락으로 실패함을 확인 |
| 락 대기 타임아웃 · 데드락 희생자 (MySQL Testcontainers + MockMvc) | 다른 트랜잭션이 키 락을 쥐고 있으면 결제를 실행하지 않고 재시도 가능한 락 실패로 끝나며, 락이 풀린 뒤 같은 키로 성공함을 검증. 실제 InnoDB 데드락으로 cancel을 희생시켜 상태·한도·키에 흔적이 없고 실패 감사 로그는 남으며 같은 키 재시도가 정확히 한 번 반영됨을 검증. 두 예외 계열이 HTTP 409 + `Retry-After`로 응답함을 검증 |
| 커넥션 풀 교착 (MySQL Testcontainers) | 풀 3개에 서로 다른 카드로 12건을 동시에 보내도 전부 성공함을 검증 (중첩 트랜잭션 구조에서는 12건 전부 실패) |
| 상태 전이 | 허용된 전이만 성공하고, capture 후 cancel 등 불법 전이 4종은 예외로 차단됨 |
| 카드 상태 검사 | BLOCKED 카드로 승인 시도 시 거부 |
| 0원 승인(카드 검증) | 0원 승인은 한도를 차감하지 않고 성공하며, BLOCKED 카드는 금액이 0이어도 그대로 거부됨을 검증. 0원 승인의 capture(0)·cancel도 한도 필드를 건드리지 않고 정상 동작함을 검증 |
| 부분 매입(partial capture) | 승인액보다 적은 매입 시 차액이 한도로 즉시 복원됨을 검증. 매입 이후 환불 한도는 원 승인액이 아니라 실제 매입액을 기준으로 계산됨(초과 환불 거부)을 검증. 승인액 초과 매입, 0원 매입(0원 승인이 아닌 경우), 재매입 시도는 각각 예외로 차단됨을 검증. 엔티티의 현재 상태(`amount`)는 매입액으로 갱신되지만 원 승인액은 원장·감사로그에 그대로 남아 있음을 검증. 동시에 서로 다른 두 승인을 부분 매입해도 두 복원분이 모두 반영되는지는 MySQL Testcontainers로 별도 검증 |
| 감면 0원 승인(예: 국가유공자 감면) | 원금액−감면액=청구액 정합성, 감면액이 원금액을 초과하지 않는지, 세 필드가 함께 오는지, 사유 코드가 유효한지를 각각 위반 케이스로 검증. 검증용 0원 승인과 감면 0원 승인이 `kind`와 원장 기록 양쪽에서 서로 다르게 구분됨을 검증. 감면 0원 승인은 capture(0)·refund(0)로 한도를 건드리지 않고 정상 완결되지만, 검증용 0원 승인은 refund(0)이 거부됨을 검증 |
| 원장(ledger) 조회 | 거래 기록을 수정 없이 append-only로 쌓고, 히스토리로 조회 |
| 감사 로그 | 인증된 actor로 결제를 수행하면 actor가 정확히 기록되고, 미인증 컨텍스트에서는 `system`으로 폴백. 한도 초과·불법 상태 전이 같은 실패 시도도 `success=false`와 사유가 함께 기록됨을 검증 |
| 레이트 리밋 | 고정 윈도우 카운터가 한도 도달 후 거부하고 윈도우 경과 후 리셋됨을 순수 유닛 테스트로, `RateLimitFilter`가 실제로 결제/로그인 경로에 걸려 429를 돌려주는지는 MockMvc로 각각 검증 |
| HTTP 레이어 회귀 (MockMvc) | 서비스 계층 테스트로는 못 잡는 종류의 버그(예: `-parameters` 누락으로 인한 `@PathVariable` 400) 방지용, 실제 Spring MVC 디스패치로 승인→조회→감사로그 흐름을 검증 |
| 멱등성 스냅샷 회귀 (MySQL Testcontainers) | 7번에서 고친 REPEATABLE READ 스냅샷 버그가 재발하지 않는지, 새 멱등성 키로 승인이 성공하는지 검증 |
| 운영 프로파일 스모크 (`scripts/smoke-test.sh`, CI) | Docker Compose로 `mysql` 프로파일(Flyway + `ddl-auto=validate`)을 실제 기동한 뒤, 로그인 → 승인 → 같은 키 재시도(`10000.00`) replay → 다른 본문 422 → 부분 매입 → 부분환불 → 환불 → 원장 순서를 실제 HTTP로 검증 |

> **회귀 테스트 검증 방식**: 13·15번의 회귀 테스트들은 "통과한다"가 아니라 "수정을 되돌리면 실패한다"로 확인했습니다 — 금액 정규화를 `toString()`으로, actor 스코프를 고정값으로, 잠금 조회를 일반 조회로, `authorize`에 바깥 `@Transactional`을 다시, cancel의 락 순서를 반대로, 409 핸들러를 제거로, 감사 사유 길이 자르기와 원래 예외 보존을 제거로 각각 되돌려 해당 테스트가 실패하는 것을 확인한 뒤 원복했습니다. 수정 전 코드에서도 두 MySQL 회귀 테스트가 각각 400("already being processed")과 500(`UnexpectedRollbackException`)으로 실패하는 것을 먼저 확인했습니다.

> **해결된 실패 (2026-08-25)**: `PaymentServiceMySqlConcurrencyTest`의 `mySqlShouldPreventConcurrentPartialRefundOverRefund`와 `mySqlShouldPreserveAllRestoresOnConcurrentCancels` 2건이 15초 타임아웃으로 실패하던 문제를 수정했습니다.
> **원인이었던 것**: 실제 동시성 버그가 아니라 테스트 설계 문제였습니다. `@DataJpaTest`가 테스트 메서드 전체를 하나의 미커밋 트랜잭션으로 감싸는데, 이 두 테스트는 메인 스레드에서 사전 승인/매입을 먼저 실행합니다 — 그 트랜잭션이 아직 열려 있는 채로 백그라운드 스레드 2개를 띄우니, 백그라운드 스레드가 메인 스레드가 쥐고 있는(그리고 절대 커밋하지 않는) 행 락을 영원히 기다리게 됩니다.
> **수정**: 테스트 클래스에 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`를 적용해 테스트 자체가 트랜잭션을 감싸지 않도록 바꾸고, 트랜잭션 시작 전에만 실행되던 `@BeforeTransaction` 셋업을 매 테스트 전에 실행되는 `@BeforeEach`로 교체했습니다. 이제 메인 스레드의 사전 호출도 실제로 커밋되어 락이 정상적으로 풀리고, `PaymentServiceMySqlConcurrencyTest`의 테스트(현재 10개 — 11번의 부분 매입 동시성, 13·15번의 멱등성·경합·락 타임아웃·데드락 희생자 테스트 포함) 모두 통과합니다(10초대, 이전엔 실패 2건이 각 15초 타임아웃으로 소요).

---

## 부하 테스트: 락이 처리량에 미치는 영향

"비관적 락을 걸면 처리량이 떨어지지 않는가?", "13번에서 락이 하나 늘었는데 그 비용은?"에 실측으로 답하기 위해, **세 빌드를 같은 조건에서** k6로 비교했습니다 (2026-10-02, 원자료: [docs/loadtest-2026-10-02/](docs/loadtest-2026-10-02/), 방법론: [docs/loadtest-results.md](docs/loadtest-results.md)).

**조건**: MySQL 8.1 + 앱 + k6를 같은 Docker 네트워크에서 실행(한 머신) · `POST /authorize` · 50 VU · 30초 측정(직전 10초 워밍업은 버림) · HikariCP `maximumPoolSize` 60 또는 10 · `mysql,loadtest` 프로파일, 레이트 리밋은 측정용으로 해제, SQL 로깅 끔 · 매 측정마다 DB 새로 생성 · 각 1회 측정

| 빌드 | 같은 카드 집중 (pool 60) | 카드 100개 분산 (pool 60) | 카드 100개 분산 (**pool 10**) |
|------|------|------|------|
| `bc78151` 이번 작업 전 | 193 req/s · p95 265ms | 1,446 req/s · p95 52ms | **1.7 req/s · 실패 69% · 평균 28.8초** |
| `ed49f78` 13번 (키 행 락 추가, 트랜잭션 중첩 그대로) | 203 req/s · p95 259ms | 1,349 req/s · p95 60ms | **0.6 req/s · 실패 70%** |
| `c659cfa` 15번 (중첩 제거, 현재) | 208 req/s · p95 262ms | **1,794 req/s · p95 39ms** | **649 req/s · 실패 0% · p95 110ms** |

- **키 행 락의 비용**: 같은 카드 집중에서는 차이 없음(193 → 203, 측정 오차 수준) — 병목은 처음부터 카드 행 하나였습니다. 분산에서는 1,446 → 1,349로 약 7% 감소했는데, 1회 측정이라 오차와 구분할 수 있는 크기는 아닙니다.
- **커넥션 풀 교착(15번)이 부하에서도 그대로 보임**: 중첩 구조의 두 빌드는 pool 10에서 요청 대부분이 30초 커넥션 타임아웃으로 실패합니다. 중첩을 없앤 현재 빌드는 같은 pool 10에서 649 req/s, 실패 0%입니다. 8월 측정의 "pool 10에서 1.75 req/s"는 풀이 작아서가 아니라 이 교착이었고, 당시 "풀을 키웠더니 79배"라고 쓴 개선은 사실 교착이 안 일어날 만큼 풀을 키워 증상을 가린 것이었습니다.
- **중첩 제거의 부수 효과**: 요청당 동시에 쥐는 커넥션이 2개에서 1개로 줄었고, pool 60 분산에서 1,446 → 1,794 req/s, p95 52 → 39ms로 측정됐습니다. 다만 키 락 비용 7%와 같은 기준으로, 각 1회 측정이라 "+24%"라는 크기 자체는 오차 범위를 확정하지 못한 값입니다. 방향(나빠지지 않았다)까지만 주장하고, 크기를 말하려면 반복 측정이 필요합니다. 반면 pool 10의 실패율 70% → 0%는 1회 측정이어도 오차로 설명될 수 없는 차이입니다.
- **같은 카드 집중의 p99는 약 1.1초**: 50개 요청이 카드 한 행의 락 앞에 줄을 서기 때문입니다. 락 대기 상한(5초)보다 충분히 짧아 이 부하에서 409는 나오지 않았습니다.
- **8월 수치와 다른 이유**: 같은 코드(`bc78151`)도 8월(138 / 954 req/s)보다 높게 나왔습니다. 당시 `mysql` 프로파일은 base 설정의 `show-sql=true`를 물려받아 모든 SQL을 콘솔에 찍고 있었고 워밍업도 없었습니다. 그래서 절대 수치는 측정 회차끼리 비교하지 않고, 같은 회차 안의 빌드 간 비교로만 읽습니다.
- 락을 빼는 실험(8월: 같은 카드 집중에서 약 +40%)은 다시 하지 않았습니다. 락을 빼면 `PaymentServiceMySqlConcurrencyTest`가 검증하는 한도 붕괴가 재현되므로, 그 처리량은 의도적으로 지불하는 비용입니다.

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

- 모든 상태 변경 요청에는 `Idempotency-Key` 헤더(최대 64자)가 필요합니다. 키는 호출자(actor) 단위로 구분되며, 같은 키를 다른 요청 본문에 재사용하면 `422 Unprocessable Entity`를 반환합니다.
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

### Docker Compose (운영 프로파일: MySQL + Flyway + `ddl-auto=validate`)

```bash
JWT_SECRET=$(openssl rand -base64 32) docker compose up --build

# 다른 터미널에서 전체 라이프사이클 스모크 테스트 (curl, jq 필요)
bash scripts/smoke-test.sh
```

`JWT_SECRET`을 지정하지 않으면 compose가 실행을 거부합니다(운영 프로파일은 개발용 기본 키로 기동하지 않음).

### MySQL 직접 연결 (Flyway 활성화)

연결 정보는 환경변수 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`로 주입하고(미지정 시 `localhost:3306/cardpay`, `root`), `JWT_SECRET`은 필수입니다.

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

MySQL Testcontainers 테스트 11개를 포함해 전체 58개 테스트가 통과합니다(GitHub Actions에서도 매 push마다 `mvn clean verify`로 실행) — 이전에 실패했던 2건의 원인과 수정 내역은 [검증 (테스트)](#검증-테스트) 섹션 참고.

---

## 알려진 한계

알고 남겨둔 것들입니다. 대부분 "만들 수 있지만 이 프로젝트의 초점(정합성 코어) 밖이라 문서화로 대신한 것"입니다.

| 한계 | 내용 | 운영에서 필요한 것 |
|------|------|------|
| JWT 폐기 불가 | stateless 토큰이라 로그아웃·탈취 시 만료(1시간) 전까지 무효화할 수 없음. 리프레시 토큰 없음 | 짧은 액세스 토큰 + 리프레시 토큰 회전, 또는 폐기 목록(Redis) |
| 레이트 리밋이 인메모리 | 인스턴스마다 카운터가 따로라 다중 인스턴스에서는 한도가 인스턴스 수만큼 늘어남. 고정 윈도우라 경계에서 순간적으로 최대 2배 허용 | Redis 등 공유 스토어 기반 슬라이딩 윈도우/토큰 버킷 |
| 멱등성 키 보관 기간 없음 | 완료된 키(캐시된 응답 포함)가 영구히 쌓임 | 보관 기간(예: Stripe 24시간)을 정해 배치 삭제. 이 기간이 곧 "이 시간 안의 재시도만 중복 방지된다"는 클라이언트와의 계약 |
| 동시 중복 요청은 대기 | 같은 키의 동시 요청은 원 요청이 끝날 때까지 키 락을 기다렸다가 replay. 락 대기가 5초(`innodb_lock_wait_timeout`)를 넘기면 409 (13·15번). 5초 상한은 `mysql` 프로파일 설정이며 H2 개발 프로파일에는 없음 | — (의도한 트레이드오프) |
| 격리수준은 REPEATABLE READ 유지 | 정합성이 걸린 읽기는 전부 잠금 조회(`FOR UPDATE`)라 격리수준과 무관하게 최신 커밋을 읽음. READ COMMITTED로 낮춰도 정합성은 얻는 게 없고 다른 모든 읽기의 의미만 바뀌므로, 7번의 스냅샷 문제는 해당 조회만 잠금 조회로 바꿔 국소적으로 해결 | — |
| 승인 만료 없음 | 매입도 취소도 안 된 `AUTHORIZED`는 한도를 영구히 점유 | N일 경과 승인을 만료시키는 배치 (같은 Authorization 락으로 동시 매입과 직렬화) |
| 트랜잭션 경계 제약 | 결제 연산을 이미 열린 트랜잭션 안에서 호출하면 커넥션 중첩이 되살아남 (15번) | 호출 규약으로 관리 (현재 코드 주석에 명시) |
| 부하 수치는 단일 머신 기준 | k6·앱·MySQL이 한 머신에서 CPU를 나눠 씀. 절대 수치보다 같은 조건에서의 상대 비교로 읽어야 함 | 분리된 환경에서 재측정 |

---

## 의도적으로 다루지 않은 것

프로젝트의 초점을 흐리지 않기 위해 다음은 범위에서 제외했습니다.
프론트엔드, 실제 PG/카드사 망 연동, 정산·대사, 회원 관리.
이들 없이도 결제 처리 코어의 정합성이라는 주제를 온전히 다룰 수 있다고 판단했습니다.

---

## 포트폴리오 맥락

- **AtomPay** — 결제가 정확하게 처리되는가 (트랜잭션 코어: 동시성·멱등성·정합성·보안)
- **InfraPulse** — 수상한 거래를 탐지·분석하는가 (룰 + ML 이상탐지, 금융 규제 매핑)
