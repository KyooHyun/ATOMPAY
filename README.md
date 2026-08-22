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
| 검증 | JUnit 단위 테스트 13개 + MySQL Testcontainers 동시성 테스트 |
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

부하 테스트 환경을 처음으로 실제 MySQL(Flyway + `ddl-auto: validate`)에 올려 보니, 멱등성 처리가 재현율 100%로 실패했습니다.
원인은 `handleIdempotentRequest`가 (1) 트랜잭션 시작 시 키를 한 번 조회하고 → (2) `REQUIRES_NEW`로 분리된 트랜잭션에서 placeholder를 커밋하고 → (3) 같은 바깥 트랜잭션에서 다시 조회하는 구조였는데,
InnoDB REPEATABLE READ 하에서는 (3)의 재조회가 (1)에서 이미 고정된 스냅샷을 그대로 쓰기 때문에 방금 커밋된 placeholder가 "없는 것"으로 보였던 것입니다.
H2와 MySQL Testcontainers 테스트 어느 쪽도 이 경로를 실제로 검증하지 못해 지금까지 발견되지 않았습니다.
재조회를 `PESSIMISTIC_WRITE` 락 조회(`findByKeyValueForUpdate`)로 바꿔 스냅샷을 우회하고 항상 최신 커밋을 읽도록 수정했습니다. (자세한 경위: [docs/loadtest-results.md](docs/loadtest-results.md))

---

## 검증 (테스트)

| 테스트 | 검증 내용 |
|--------|-----------|
| 동시성 재현 (MySQL Testcontainers) | 락 제거 시 동시 요청으로 한도/환불 금액이 깨지는 것을 재현하고, 락 적용 시 최종 금액이 정확함을 검증 |
| 멱등성 재시도 | 같은 키 재시도 시 중복 처리 없이 동일 응답 반환, 다른 본문 동일 키는 거부 |
| 상태 전이 | 허용된 전이만 성공하고, capture 후 cancel 등 불법 전이 4종은 예외로 차단됨 |
| 카드 상태 검사 | BLOCKED 카드로 승인 시도 시 거부 |
| 원장(ledger) 조회 | 거래 기록을 수정 없이 append-only로 쌓고, 히스토리로 조회 |

---

## 부하 테스트: 락이 처리량에 미치는 영향

"비관적 락을 걸면 처리량이 떨어지지 않는가?"에 실측으로 답하기 위해 k6로 벤치마크했습니다. (전체 방법론과 원자료: [docs/loadtest-results.md](docs/loadtest-results.md))

| 시나리오 | 락 | 처리량 | 평균 지연 |
|---|---|---|---|
| 같은 카드에 요청 집중 (최악의 경합) | 있음 (현재 코드) | 138 req/s | 457ms |
| 카드 100개에 요청 분산 (현실적 분산) | 있음 (현재 코드) | 954 req/s | 70ms |
| 같은 카드에 요청 집중 | 없음 (로컬 실험용, 커밋 안 함) | 193 req/s | 342ms |

- 카드를 분산하면 락을 켠 채로도 **약 6.9배** 처리량이 나옵니다 — 실제 카드사 트래픽은 카드 수가 많아 한 로우에 경합이 몰릴 일이 드뭅니다.
- 최악의 경합 상황에서 락 제거가 벌어주는 처리량은 **약 40%**로, 커넥션 풀 크기를 늘렸을 때의 개선(50배 이상)에 비하면 작습니다. 즉 병목은 락이 아니라 풀 크기였고, 락은 그 다음 문제였습니다.
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

- 모든 상태 변경 요청에는 `Idempotency-Key` 헤더가 필요합니다.
- 외부 식별자(`authorizationId`)는 UUID를 사용해 IDOR를 방지합니다.
- 요청마다 `X-Request-Id` 헤더를 응답으로 반환합니다.

---

## 아키텍처

```
controller   # REST 엔드포인트, Bean Validation(@Valid), OpenAPI 문서
filter       # CorrelationIdFilter — X-Request-Id MDC 주입
             # JwtAuthenticationFilter — Bearer 토큰 검증 및 SecurityContext 주입
security     # JwtTokenProvider — 토큰 생성/검증
config       # SecurityConfig — 필터 체인, 공개/보호 경로 구성
             # OpenApiConfig — Swagger bearerAuth 스키마 설정
             # DataInitializer — 초기 데이터 시딩 (CARD-001, admin 계정)
service      # 트랜잭션 경계, 락 획득, 멱등성 처리 조율
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
# 단위 테스트 (H2, Docker 불필요)
.\mvnw.cmd test -Dtest=PaymentServiceTest

# 동시성 테스트 (MySQL Testcontainers, Docker 필요)
.\mvnw.cmd test -Dtest=PaymentServiceMySqlConcurrencyTest
```

---

## 의도적으로 다루지 않은 것

프로젝트의 초점을 흐리지 않기 위해 다음은 범위에서 제외했습니다.
프론트엔드, 실제 PG/카드사 망 연동, 정산·대사, 회원 관리.
이들 없이도 결제 처리 코어의 정합성이라는 주제를 온전히 다룰 수 있다고 판단했습니다.

---

## 포트폴리오 맥락

- **AtomPay** — 결제가 정확하게 처리되는가 (트랜잭션 코어: 동시성·멱등성·정합성·보안)
- **InfraPulse** — 수상한 거래를 탐지·분석하는가 (룰 + ML 이상탐지, 금융 규제 매핑)
