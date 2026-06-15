# AtomPay

카드 결제 처리 코어를 단순화하여 구현한 Spring Boot 백엔드입니다.
승인(authorization) → 매입(capture) → 취소(cancel) / 환불(refund) 의 결제 라이프사이클을,
동시성 정합성 · 멱등성 · 상태 전이 세 가지 관점에서 다루는 데 집중했습니다.

화면(프론트엔드)이나 외부 PG 연동 없이, 결제 처리 코어 로직 그 자체의 정확성에 집중한 프로젝트입니다.
"기능이 동작한다"가 아니라 "동시 요청·재시도·중간 실패 상황에서도 금액 정합성이 깨지지 않는다"를 목표로 했습니다.

---

## 한눈에 보기

| 항목 | 내용 |
|------|------|
| 스택 | Spring Boot 3.2 · Spring Data JPA · MySQL (개발 시 H2) |
| 핵심 주제 | 동시성 제어 · 멱등성 · 결제 상태 머신 |
| API 문서 | Swagger UI — `/swagger-ui.html` |
| 검증 | JUnit 단위 테스트(13개) + MySQL Testcontainers 동시성 테스트 |
| 운영 가시성 | MDC 기반 X-Request-Id 추적 · Spring Actuator `/actuator/health` |
| 의도적으로 제외 | 프론트엔드, PG 연동, 정산/대사, 인증/회원 |

---

## 왜 이 프로젝트인가

결제 백엔드에서 가장 어려운 부분은 "기능을 만드는 것"이 아니라
같은 자원에 동시 요청이 몰리거나, 네트워크 재시도로 요청이 중복되거나, 처리 도중 실패했을 때
돈이 틀어지지 않게 하는 것이라고 판단했습니다.

그래서 기능 수를 늘리는 대신, 다음 세 가지 난제에 깊이 들어가는 것을 목표로 잡았습니다.

- **동시성** — 같은 카드/거래에 요청이 동시에 들어올 때 한도·환불 금액이 깨지지 않는가
- **멱등성** — 재시도로 같은 요청이 두 번 들어와도 결제가 한 번만 일어나는가
- **상태 전이** — 승인·매입·취소·환불이 허용된 순서로만 일어나고, 불법 전이는 차단되는가

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

- `cancel`은 `AUTHORIZED`에서만 가능합니다. 매입(`CAPTURED`) 이후에는 돈이 이미 이동했으므로 취소가 아니라 환불 경로로만 처리됩니다.
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
  이 트레이드오프 자체를 기록으로 남겨, "왜 이 락을 골랐는가"에 답할 수 있게 했습니다.

### 2. 같은 레이스가 환불에도 있었다 — 양방향으로 확장

처음에는 승인(`CardAccount` 한도)에만 락을 걸었지만,
부분환불에서 `Authorization.refundedAmount`를 누적 갱신하는 경로에도 동일한 레이스가 있음을 확인했습니다.
(동시 부분환불 두 건이 같은 누적값을 읽으면 과환불이 발생)
승인과 환불 양쪽 모두 락을 적용하고, 각각을 테스트로 검증했습니다.

### 3. 멱등성: "키 조회 후 처리"의 허점을 막기

단순히 "키가 있으면 스킵, 없으면 처리"는 **조회와 처리 사이(TOCTOU)** 에 동시 요청이 끼어들면 중복 결제가 뚫립니다.
그래서 `idempotency_key`에 DB unique 제약을 걸어 동시 도착 시 두 번째 요청이 제약 위반으로 걸러지게 하고,
완료된 요청의 응답 페이로드를 함께 저장해 재시도 시 재처리 없이 동일 응답을 반환하도록 했습니다.
같은 키 + 다른 본문은 멱등성 위반으로 거부합니다.

### 4. CardAccount 복원 경로의 락 누락 — 발견하고 수정

초기 구현에서 취소(cancel) · 환불(refund) 경로가 Authorization 행에는 비관적 락을 걸었지만,
CardAccount의 `availableAmount`를 복원할 때는 락 없이 `findByCardId`를 사용했습니다.
같은 카드에 서로 다른 두 승인이 동시에 취소되면, 두 스레드가 같은 `availableAmount`를 읽고 각자 +금액을 써서 한 쪽 복원이 유실됩니다.
이를 발견하고 `findByCardIdForUpdate`로 교체해 승인·취소·환불 모든 경로에서 CardAccount 락을 일관되게 적용했습니다.

### 5. 동시성 검증 환경: H2가 아니라 MySQL

동시성 테스트를 개발용 H2에서 돌리면, H2와 MySQL(InnoDB)의 `SELECT ... FOR UPDATE` 동작 차이 때문에
실제 운영 환경에서의 정합성을 증명하지 못합니다.
그래서 동시성 테스트만큼은 MySQL Testcontainers로 실제 InnoDB 위에서 레이스를 재현·검증했습니다.

---

## 검증 (테스트)

| 테스트 | 검증 내용 |
|--------|-----------|
| 동시성 재현 (MySQL Testcontainers) | 락 제거 시 동시 요청으로 한도/환불 금액이 깨지는 것을 재현하고, 락 적용 시 최종 금액이 정확함을 검증 |
| 멱등성 재시도 | 같은 키 재시도 시 중복 처리 없이 동일 응답 반환, 다른 본문 동일 키는 거부 |
| 상태 전이 | 허용된 전이(capture→cancel 불가 등)는 예외로 차단, 불법 전이 4종 검증 |
| 카드 상태 검사 | BLOCKED 카드 승인 시도 거부 |
| 원장(ledger) 조회 | 거래 기록을 수정 없이 append-only로 쌓고, 히스토리로 조회 |

동시성 테스트의 핵심은 "통과한다"가 아니라 **"락을 빼면 금액이 깨지는 것을 눈으로 보여줄 수 있다"** 입니다.
before(락 없음 → 금액 붕괴) / after(락 적용 → 금액 정합) 를 한 쌍으로 검증합니다.

---

## API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/v1/payments/authorize` | 결제 승인 |
| POST | `/api/v1/payments/{authorizationId}/capture` | 매입 |
| POST | `/api/v1/payments/{authorizationId}/cancel` | 승인 취소 (매입 전) |
| POST | `/api/v1/payments/{authorizationId}/partial-refund` | 부분 환불 (매입 후) |
| POST | `/api/v1/payments/{authorizationId}/refund` | 전액 환불 (매입 후) |
| GET | `/api/v1/payments/{authorizationId}` | 상태 및 승인 정보 조회 |
| GET | `/api/v1/payments/{authorizationId}/transactions` | 거래 히스토리 조회 |

- 모든 상태 변경 요청에는 `Idempotency-Key` 헤더가 필요합니다.
- 외부 식별자(`authorizationId`)는 DB의 auto-increment PK를 그대로 노출하지 않고 별도 식별자를 사용해, 다른 거래 ID 추측·열람(IDOR)을 방지합니다.

---

## 아키텍처

```
controller   # REST 엔드포인트, @Valid 입력 검증, OpenAPI 문서
filter       # CorrelationIdFilter — X-Request-Id를 MDC에 주입, 응답 헤더로 반환
service      # 트랜잭션 경계, 락 획득, 멱등성 처리 조율
repository   # JPA, 비관적 락 조회(findByAuthorizationIdForUpdate 등)
domain
 ├─ entity   # 상태 전이 메서드(capture/cancel/refund)와 불변식을 보유
 └─ enum     # 결제 상태 정의
dto
exception    # GlobalExceptionHandler 기반 일관된 예외 응답
```

설계 원칙: 상태 전이 규칙과 금액 불변식은 도메인 엔티티 안에 두고, 서비스는 트랜잭션·락·멱등성의 조율만 담당하도록 책임을 분리했습니다.

- **입력 검증**: Bean Validation(`@Valid`)으로 API 경계에서 잘못된 요청을 차단합니다.
- **분산 추적**: 모든 요청에 `X-Request-Id` 헤더를 자동 부여하고 MDC에 삽입해 로그와 응답을 연결합니다.

---

## 실행

### 개발용 (H2)

```bash
mvn spring-boot:run
```

기본 주소: `http://localhost:8080`

### MySQL

`src/main/resources/application-mysql.properties`에 연결 정보를 설정한 뒤 해당 프로파일로 실행합니다.
동시성 테스트는 별도 설치 없이 MySQL Testcontainers로 동작합니다. (Docker 필요)

---

## 의도적으로 다루지 않은 것

프로젝트의 초점을 흐리지 않기 위해 다음은 범위에서 제외했습니다.
프론트엔드, 실제 PG/카드사 망 연동, 정산·대사, 인증/회원 관리.
이들 없이도 결제 처리 코어의 정합성이라는 주제를 온전히 다룰 수 있다고 판단했습니다.

---

## 포트폴리오 맥락

- **AtomPay** — 결제가 정확하게 처리되는가 (트랜잭션 코어: 동시성·멱등성·정합성)
- **InfraPulse** — 수상한 거래를 탐지·분석하는가 (룰 + ML 이상탐지, 금융 규제 매핑)
