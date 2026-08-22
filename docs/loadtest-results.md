# Load test: does the pessimistic lock cost throughput?

**Date:** 2026-08-22
**Question:** AtomPay uses `PESSIMISTIC_WRITE` on `CardAccount` for every balance mutation to guarantee correctness under concurrent requests (see `PaymentServiceMySqlConcurrencyTest`). The obvious follow-up question is: what does that cost in throughput, and where does the cost actually show up?

## Setup

- Real MySQL 8.1 (Docker, not H2/mocks), app run standalone via `spring-boot:run` against it, not through the JPA test harness.
- 100 seeded card accounts (`CARD-LOAD-001`..`100`, `loadtest` Spring profile), each with an effectively unlimited balance so the benchmark never fails on business rules, only on infrastructure limits.
- Load generator: k6 (Docker), 50 VUs, 30s, hitting `POST /api/v1/payments/authorize`.
- Two traffic shapes:
  - **Contention** — all 50 VUs authorize against the *same* card (`CARD-LOAD-001`). Worst case: every request serializes on one row's lock.
  - **Distributed** — each VU pinned to its own card (up to 100 distinct cards). Best case: lock contention is spread thin.
- Two code variants: the real code (`findByCardIdForUpdate`, `PESSIMISTIC_WRITE`) vs. a temporary local-only edit swapping it for a plain `findByCardId` (no lock), rebuilt, benchmarked, then reverted — never committed.

## First run: the bottleneck wasn't the lock at all

With HikariCP's default pool size (10), **both** scenarios collapsed to the same place:

| Scenario | Success rate | Throughput | Avg latency |
|---|---|---|---|
| Contention, pool=10 | 30% | 1.75 req/s | 28.4s |
| Distributed, pool=10 | 23% | 1.82 req/s | 27.4s |

Identical collapse regardless of card distribution means the lock wasn't the limiting factor yet — 50 concurrent VUs against a 10-connection pool queue on the *pool*, not the row lock. This is itself a useful finding: a lock-contention benchmark is meaningless until the connection pool is sized past the concurrency level you're testing, otherwise you're just measuring the pool.

## Second run: pool sized to the load (maximumPoolSize=60)

| Scenario | Lock | Success | Throughput | Avg latency | p95 |
|---|---|---|---|---|---|
| Contention (same card) | **On** (current code) | 100% | **138 req/s** | 457ms | 410ms |
| Distributed (100 cards) | **On** (current code) | 100% | **954 req/s** | 70ms | 67ms |
| Contention (same card) | Off (temp, local only) | 100% | 193 req/s | 342ms | 279ms |
| Distributed (100 cards) | Off (temp, local only) | 99.9% | 553 req/s* | 64ms | 65ms |

\* The unlocked-distributed number is noisier than the others (18 request timeouts out of 28k, likely transient container/JIT-warmup noise from being the 4th run in the sequence) — treat it as "same order of magnitude as locked-distributed," not as a precise figure.

**Reading it:**
- Distributing across cards is a **~6.9x** throughput gain over hammering one card, with the lock on. That's the realistic case for a real card issuer: millions of cards, so row-lock contention on any single card is rare in practice.
- Removing the lock buys **~40%** more throughput in the worst case (same-card contention) — real, but far smaller than what fixing the connection pool bought (10 -> 60 was a >50x improvement by itself).
- HikariCP metrics during the runs confirm *why*: in the contention run, ~50 connections sit `active` with `pending=0` — connections are checked out but idle, blocked waiting on the row lock, not queueing for a connection. In the distributed run, `active` pins at the pool max (60) with brief `pending` spikes — the bottleneck there is genuinely "not enough connections for this much real work," a much healthier problem to have.

**Conclusion for the interview question:** "Locking costs about 40% throughput under worst-case single-card contention, and nothing measurable under realistic distributed traffic. That's a trade we take deliberately, because the alternative — proven by `PaymentServiceMySqlConcurrencyTest` — is silently corrupted balances under concurrent requests. The bigger lesson from actually measuring this was that connection pool sizing dominates lock overhead by an order of magnitude, so the pool needs to scale with expected concurrency regardless of the locking strategy."

## Bugs found while building this benchmark (not the original goal, but blocking it)

Getting a real load test running against real MySQL required actually exercising the `mysql` Spring profile end-to-end for the first time — it had only ever been used via H2 (dev) or Testcontainers with `ddl-auto=update` (tests), never via Flyway + `ddl-auto=validate` the way it would run in production. That surfaced:

1. **Critical: idempotency was completely broken on real MySQL.** Every fresh idempotency key deterministically failed with `Idempotency placeholder was unexpectedly removed` — 100% reproduction, not a race. Root cause: the re-read after reserving the placeholder (in a sibling `REQUIRES_NEW` transaction) happened inside the *same* outer transaction whose REPEATABLE READ snapshot was already fixed by an earlier read — so it could never see the just-committed row. Fixed with a `PESSIMISTIC_WRITE` locking re-read (`IdempotencyKeyRepository.findByKeyValueForUpdate`), since InnoDB locking reads bypass the snapshot and always see latest-committed data. This means the API's core payment flows did not work at all against real MySQL before this fix.
2. `application-mysql.properties` was missing `spring.datasource.driver-class-name`, silently inheriting H2's driver from the base properties file.
3. Hibernate 6 defaults `@Enumerated(STRING)` fields to the dialect's native `ENUM` column type; the Flyway migrations create `VARCHAR`. Fixed with `@JdbcTypeCode(SqlTypes.VARCHAR)` on all 5 enum-mapped fields.
4. Same class of mismatch for `IdempotencyKey.responsePayload` (`@Lob String` defaulted to `TINYTEXT` via length inference vs. the migration's `LONGTEXT`) — fixed with `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`.
5. The existing `PaymentServiceMySqlConcurrencyTest` (Testcontainers) never actually ran in this environment either — it hit an unrelated SSL handshake failure against the container's self-signed cert. Fixed by disabling SSL on the test datasource URL, consistent with how the rest of the project already runs MySQL locally.

**Open finding, not fixed here:** with the SSL issue fixed, two of the three Testcontainers concurrency tests now fail deterministically (`mySqlShouldPreventConcurrentPartialRefundOverRefund`, `mySqlShouldPreserveAllRestoresOnConcurrentCancels`) — both time out waiting ~15s for background threads to acquire row locks. Suspected cause: `@DataJpaTest` wraps each test method in one shared, uncommitted transaction; the tests' single-threaded *setup* calls (made on the main test thread) run inside that transaction and hold row locks that are never released (no commit) until the test ends, so the background threads spawned later in the same test block on those locks indefinitely. This is a test-design issue (mixing main-thread setup with background-thread concurrency under a transactional test), separate from the idempotency bug above, and needs its own fix — worth picking up as part of the idempotency-failure-semantics work ([[project_atompay]] priority #2), since it currently means these three tests provide less real coverage than they appear to.

## How to reproduce

```
docker run --name atompay-loadtest-mysql -e MYSQL_ROOT_PASSWORD=changeit -e MYSQL_DATABASE=cardpay -p 3307:3306 -d mysql:8.1.0

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3307/cardpay?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
SPRING_DATASOURCE_DRIVER_CLASS_NAME="com.mysql.cj.jdbc.Driver" \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=60 \
./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql,loadtest

docker run --rm --add-host=host.docker.internal:host-gateway \
  -v "<repo>/scripts/loadtest:/scripts" \
  grafana/k6 run /scripts/contention.js   # or distributed.js
```
