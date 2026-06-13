# AtomPay

A Spring Boot backend that models a simplified card payment processing core.

## 목표
- 카드 승인(authorization)부터 매입(capture), 취소(cancel), 부분환불(partial refund), 환불(refund)까지의 결제 라이프사이클 처리
- 카드사 백엔드가 자주 묻는 `동시성`, `멱등성`, `트랜잭션 상태 관리` 역량 증명
- 화면 없이 API + 테스트 + README로 충분한 설계와 구현

## 주요 기능
- 카드 결제 승인 시 `PESSIMISTIC_WRITE` 기반 동시성 제어
- `Idempotency-Key` 헤더를 통한 DB 기반 멱등성 지원
- 승인 → 매입 → 취소/부분환불/환불 상태 전이
- 룰 기반 차단
  - 한도 초과
  - 이상 금액 차단
- `Spring Boot + Spring Data JPA + MySQL` 설계

## 결제 상태 전이
이 시스템은 결제 로직을 명확한 상태 머신으로 표현합니다.

| 시작 상태 | 이벤트 | 결과 상태 | 비고 |
| --- | --- | --- | --- |
| AUTHORIZED | capture | CAPTURED | 매입은 승인 금액 전체만 가능 |
| AUTHORIZED | cancel | CANCELLED | 매입 이전에만 가능 |
| CAPTURED | partial refund | PARTIALLY_REFUNDED | 매입 후 일부 환불 |
| PARTIALLY_REFUNDED | partial refund | PARTIALLY_REFUNDED | 누적 환불 금액 관리 |
| CAPTURED | refund | REFUNDED | 전액 환불 |
| PARTIALLY_REFUNDED | refund | REFUNDED | 남은 잔액 전액 환불 |

### 핵심 도메인 룰
- `cancel`은 `AUTHORIZED` 상태에서만 가능하고, `CAPTURED` 후에는 허용되지 않습니다.
- `partial refund`와 `refund`는 `CAPTURED` 또는 `PARTIALLY_REFUNDED` 상태에서만 허용됩니다.
- 환불/부분환불은 `Authorization.refundedAmount`를 누적하여 남은 환불 한도를 계산합니다.
- `capture`는 승인 금액 전체와 일치해야 하며, 부분 매입은 지원하지 않습니다.

## 멱등성과 동시성
- 모든 상태 변경 요청에는 `Idempotency-Key` 헤더가 필요합니다.
- 동일한 `Idempotency-Key`와 동일한 요청 본문으로 재시도하면 이전 결과를 그대로 반환합니다.
- 다른 본문으로 동일 키가 들어오면 요청을 거부하여 멱등성 위반을 방지합니다.
- `Idempotency-Key`는 DB에 저장되고 응답 페이로드가 함께 유지되므로 서버 재시도와 동시 요청 모두 안전하게 처리됩니다.
- 승인 시 `PESSIMISTIC_WRITE`로 `CardAccount` 보유 한도를 잠그고, 동시 인증 요청이 충돌하지 않도록 합니다.
- 환불과 부분 환불도 `Authorization` 행을 `PESSIMISTIC_WRITE`로 잠궈 `refundedAmount` 누적 갱신 경쟁을 방지합니다.
- 동시성 증명은 H2가 아닌 MySQL(InnoDB) 기반 Testcontainers에서 수행합니다. H2와 MySQL의 `SELECT ... FOR UPDATE` 동작이 다르기 때문에, 이 프로젝트는 MySQL 환경에서 레이스를 재현하고 잠금 적용을 검증합니다.

## 설계하며 고민한 점
처음엔 락 없이 구현했더니 동시 승인에서 한도가 음수로 깨졌다. 비관적 락(PESSIMISTIC_WRITE)과 낙관적 락을 두고, 한도 차감은 충돌이 잦은 경로라 재시도 비용이 큰 낙관적 락 대신 비관적 락을 택했다. 이후 승인(CardAccount 한도 차감)뿐 아니라 환불(Authorization.refundedAmount 누적)에서도 같은 레이스가 발생함을 확인하고, 양방향 모두 락을 적용하고 테스트로 검증했다. 멱등성도 단순 "키 조회 후 처리"는 조회와 처리 사이 레이스가 남아, DB unique 제약으로 동시 도착을 막는 방식으로 바꿨다.

## 패키지 구조
- `controller`
- `service`
- `repository`
- `domain`
  - `entity`
  - `enum`
- `dto`
- `exception`

## 실행
### 개발용 (H2)
```bash
mvn spring-boot:run
```
서버는 기본적으로 `http://localhost:8080`에서 실행됩니다.

### MySQL 설정 예시
`src/main/resources/application-mysql.properties`를 참조하여 MySQL 연결 정보를 입력합니다.

## 주요 API
- `POST /api/v1/payments/authorize`
- `POST /api/v1/payments/{authorizationId}/capture`
- `POST /api/v1/payments/{authorizationId}/cancel`
- `POST /api/v1/payments/{authorizationId}/partial-refund`
- `POST /api/v1/payments/{authorizationId}/refund`
- `GET /api/v1/payments/{authorizationId}/transactions`
- `GET /api/v1/payments/{authorizationId}`

### 멱등성 헤더
모든 상태 변경 요청에는 `Idempotency-Key` 헤더를 포함해야 합니다.

### 거래 조회
- `GET /api/v1/payments/{authorizationId}/transactions` : 해당 승인 ID의 트랜잭션 히스토리를 반환합니다.
- `GET /api/v1/payments/{authorizationId}` : 상태 및 승인 정보를 조회합니다.

## 기술적 어필 포인트
- InfraPulse는 탐지/분석 역량을 보여주는 프로젝트이고, `card-pay-core`는 **카드사 백엔드의 결제 처리 코어 역량**을 보여줍니다.
- 이 프로젝트는 `동시성 제어`, `Idempotency-Key 기반 멱등성`, `승인-매입-취소-환불 상태 전이`를 중심으로 설계되었습니다.
- Java에 익숙하기 때문에 `Spring Boot`를 선택하여 안정성, 가독성, 구현 속도를 확보했습니다.

## 테스트
- MySQL Testcontainers 기반 동시성 재현 테스트 — 락 제거 시 한도 붕괴를 재현하고, 락 적용으로 해결됨을 최종 금액으로 검증
- 멱등성 재시도 테스트
- 상태 전이 테스트
- 거래 내역 append-only 원장(ledger) 테스트

## 참고
- 실제 카드사 환경에서는 MySQL, Oracle 또는 유사한 RDBMS를 사용합니다.
- 이 프로젝트는 프론트엔드, PG 연동, 정산/대사, 인증/회원 기능을 제외하고 결제 처리 흐름에 집중합니다.
