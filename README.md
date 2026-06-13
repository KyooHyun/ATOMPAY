# card-pay-core

A Spring Boot backend that models a simplified card payment processing core.

## 목표
- 카드 승인(authorization)부터 매입(capture), 취소(cancel), 부분취소(partial cancel), 환불(refund)까지의 결제 라이프사이클 처리
- 카드사 백엔드가 자주 묻는 `동시성`, `멱등성`, `트랜잭션 상태 관리` 역량 증명
- 화면 없이 API + 테스트 + README로 충분한 구현

## 주요 기능
- 카드 결제 승인 시 `PESSIMISTIC_WRITE` 기반 동시성 제어
- `Idempotency-Key` 헤더를 통한 멱등성 지원
- 승인 → 매입 → 취소/부분취소 → 환불 상태 전이
- 룰 기반 차단
  - 한도초과
  - 짧은 시간 중복 요청 차단
  - 이상금액 차단
- `Spring Boot + Spring Data JPA + MySQL` 설계

## 패키지 구조
- `controller`
- `service`
- `repository`
- `domain`
  - `entity`
  - `enum`
- `dto`
- `exception`
- `util`

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
- `POST /api/v1/payments/{authorizationId}/partial-cancel`
- `POST /api/v1/payments/{authorizationId}/refund`

## 기술적 어필 포인트
- InfraPulse는 탐지/분석 역량을 보여주는 프로젝트이고, `card-pay-core`는 **카드사 백엔드의 결제 처리 코어 역량**을 보여줍니다.
- 이 프로젝트는 `동시성 제어`, `Idempotency-Key 기반 멱등성`, `승인-매입-취소-환불 상태 전이`를 중심으로 설계되었습니다.
- `Spring 첫 프로젝트`였기 때문에 Java를 선택하여 안정성, 가독성, 구현 속도를 확보했습니다.

## 테스트
- 동시성 테스트
- 멱등성 재시도 테스트
- 상태 전이 테스트

## 참고
- 실제 카드사 환경에서는 MySQL, Oracle 또는 유사한 RDBMS를 사용합니다.
- 이 프로젝트는 프론트엔드, PG 연동, 정산/대사, 인증/회원 기능을 제외하고 결제 처리 흐름에 집중합니다.
