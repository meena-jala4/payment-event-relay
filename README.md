# Payment Event Relay

A small Spring Boot service that accepts outgoing payment instructions, stores them, and reliably
delivers a `PaymentSubmitted` event to three downstream consumers (Notifications, Fraud Screening,
Core Ledger), without losing or double-applying anything.

```
            POST /payments (Idempotency-Key)
                    │
                    ▼
   ┌────────────── ONE DB transaction ──────────────┐
   │  payment row            +   outbox_event row   │
   └────────────────────────────────────────────────┘
                    │  OutboxRelay (polls, retries while broker is down)
                    ▼
        MessageBroker  ── fan-out, durable, at-least-once ──┐
        (DB-backed stub; Kafka in prod)                     │
          │                    │                            │
          ▼                    ▼                            ▼
   notification loop    fraud-screening loop          ledger loop
   (inbox de-dup)       (inbox de-dup, calls          (inbox de-dup +
                         XML vendor, timeouts,         UNIQUE(payment_id))
                         retries, dead-letter)
```

## How to run

Requirements: Java 21. Nothing else (embedded H2 + built-in fraud stub).

```bash
./mvnw spring-boot:run          # or: make run      -> http://localhost:8080
./mvnw verify                   # or: make test     -> all tests + coverage in target/site/jacoco
docker compose up --build       # optional: same service against PostgreSQL
```

### Try it

```bash
# Submit (repeat the exact command: you get 200 + the same payment, not a second one)
curl -i -X POST localhost:8080/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-001' \
  -d '{"sourceAccount":"12-3456-7890123-00",
       "destination":{"name":"Aroha Smith","accountNumber":"02-0100-0123456-001"},
       "amount":"1500.00","currency":"NZD","reference":"Rent Nov",
       "requestedExecutionDate":"2026-12-01"}'

curl localhost:8080/payments/<id>                   # fraudScreening moves PENDING -> COMPLETED

# Failure drills (simulation endpoints are disabled in prod)
curl -X POST 'localhost:8080/simulation/broker?available=false'      # broker outage
curl -X POST 'localhost:8080/simulation/fraud-provider?failureRate=1' # vendor down
curl localhost:8080/admin/deliveries?status=DEAD                      # dead-letter queue
curl -X POST localhost:8080/admin/deliveries/<id>/replay              # re-drive after the fix
```

Stub decisions are deterministic by amount (> 10,000 DECLINE, > 1,000 REVIEW, else ACCEPT), with a
configurable random failure rate (`FRAUD_STUB_FAILURE_RATE`, default 0.3), slow rate and delay.

## API

| Request | Result |
|---|---|
| `POST /payments` new key | `201 Created` + `Location` |
| same key, same body | `200 OK` + `Idempotent-Replayed: true`, same payment |
| same key, different body | `422` problem+json |
| invalid field(s) / malformed JSON / missing key | `400` problem+json with `errors[{field,message}]` |
| `GET /payments/{id}` | `200` incl. `fraudScreening`, or `404` |

## Key design decisions and trade-offs

**1. Transactional outbox (the core decision).** The payment and its event are written in one local
transaction. Writing to the DB and then calling a broker is a "dual write": a crash or broker outage
between the two loses the event. With the outbox, an accepted payment *always* has an event row,
and the relay keeps retrying (it never gives up) until the broker takes it.
*Trade-off:* polling adds up to ~500 ms latency and DB load. Alternative: CDC (Debezium) tailing
the outbox table — lower latency, more infrastructure.

**2. At-least-once delivery + idempotent consumers, not "exactly-once".** Exactly-once across a
network isn't achievable in general, so the system is designed to tolerate duplicates:
- every event has a stable `eventId`;
- an *inbox* table `processed_event(consumer, event_id)` with a UNIQUE constraint is written in the
  same transaction as the consumer's side effect, so a side effect commits at most once even under
  concurrent duplicates;
- the ledger additionally has `UNIQUE(payment_id)` — a guard on the *business* key, so even a
  re-published event with a new eventId can never post twice. Belt and braces, because double
  posting is the one failure a bank can't tolerate.

**3. Durable broker stub.** The brief allowed an in-process stub. An in-memory queue would lose
messages on restart, so the stub is a table (`event_delivery`) with real broker semantics: durable,
fan-out per subscriber, at-least-once (it deliberately does *not* de-duplicate). It sits behind a
`MessageBroker` interface, so moving to Kafka / Service Bus replaces one class.

**4. Failures are isolated per consumer and per event.** Each consumer has its own polling loop, so
a slow fraud vendor can't delay ledger postings. Each delivery has its own retry schedule
(exponential backoff + jitter), so a failing event backs off without blocking the ones behind it.
After `max-delivery-attempts`, or immediately for non-retryable errors, it is dead-lettered: kept,
visible on `/admin/deliveries`, and replayable. *Trade-off:* no per-payment ordering guarantee —
not needed here because each payment produces one event.

**5. Defensive vendor integration.** The fraud client:
- applies connect/read timeouts (a slow vendor becomes a retryable failure, not a stuck thread);
- calls the vendor *outside* any DB transaction, so a 5 s stall doesn't hold a DB connection;
- ignores the HTTP status for errors, because the vendor sometimes reports errors with HTTP 200 —
  an `<Error>` body is an error whatever the status;
- maps `Retryable=false` and 4xx to dead-letter, and timeouts/5xx/429/`Retryable=true` to retry;
- parses XML namespace-aware, by namespace URI rather than prefix, and reads only the elements it
  needs, so new vendor elements are ignored (tolerant reader);
- disables DOCTYPEs (XXE protection; this is untrusted third-party input);
- fails *safe*: an unknown decision value is recorded as `REVIEW`, never `ACCEPT`.

**6. Money.** `BigDecimal` + ISO-4217 currency code, never `double` (binary floating point can't
represent 0.10 exactly). Stored as `NUMERIC(19,4)`. The amount's scale is validated against the
currency's minor units (`10.001 NZD` is rejected, not rounded — silently changing a customer's
amount is worse than a 400). In JSON the amount is a *string* so JavaScript clients can't turn it
into a float. Alternative considered: integer minor units (cents) as `long` — also correct, but
couples every consumer to per-currency exponents; Joda-Money/JSR-354 would be the next step if we
needed currency arithmetic.

**7. Idempotency keys on the API.** Mobile channels retry on flaky networks. A required
`Idempotency-Key` header plus a SHA-256 of the canonicalised request makes retries safe: same
key + same body replays the original response; same key + different body is a client bug → 422.
A UNIQUE constraint on the key settles concurrent retries.

**8. Dates in business time.** "Not in the past" is judged in `Pacific/Auckland`, not the
container's UTC — between midnight and 1 pm NZDT those disagree. A `Clock` is injected so this is
tested deterministically.

**9. One codebase, H2 or PostgreSQL.** Flyway owns the schema; scripts run on both. H2 keeps "clone
and run" to one command; dev/prod point at PostgreSQL purely via config.

## Deployment configuration

- **Dockerfile** — multi-stage (Maven/JDK build → JRE-alpine runtime), runs as UID 10001,
  heap sized from the container limit (`MaxRAMPercentage`).
- **Kubernetes (Kustomize)** — `k8s/base` + `k8s/overlays/{dev,prod}`:
  - *Deployment*: requests/limits; separate **startup**, **liveness** (process health only — not DB,
    so a DB blip doesn't restart every pod) and **readiness** probes; zero-downtime rolling update
    (`maxUnavailable: 0`); `preStop` sleep + graceful shutdown; non-root, read-only root FS, all
    capabilities dropped, seccomp; zone spread.
  - *Service* (ClusterIP), *ConfigMap* (vendor URL, timeouts, retry settings, feature flags),
    *Secret references* via `secretKeyRef` — the Secret itself is created by the secret store
    (External Secrets / Vault); `docs/secret.example.yaml` shows its shape only.
  - *HPA* (CPU), *PDB* (drains can't take all replicas), *NetworkPolicy* (default deny; only ingress
    controller + monitoring in; only DNS, PostgreSQL and HTTPS out), dedicated *ServiceAccount* with
    no API token.
  - **dev vs prod**: dev = 1 replica, small resources, fraud **stub** + simulation endpoints on,
    DEBUG logs. prod = 3–10 replicas, larger resources, PDB minAvailable 2, strict zone spread,
    **real vendor URL**, stub and simulation **off**, TLS to the DB. Switching to the real provider
    is configuration only.
  - The base file is named `Kustomization` (no extension) so both
    `kubectl apply --dry-run=client -f k8s/base/` and `kubectl apply --dry-run=client -k k8s/overlays/prod` work.
- **Jenkinsfile** — checkout → build + tests (JUnit/JaCoCo published) → SonarQube + quality gate →
  OWASP dependency check → image build → Trivy image scan → push (main only) → deploy dev → smoke
  test → **manual approval** (restricted submitters) → deploy prod with automatic rollback. The
  same immutable, SHA-tagged image is promoted; only the overlay differs.

## Assumptions

- Account numbers are NZ format `BB-bbbb-AAAAAAA-SSS` (2–3 digit suffix); format only, no checksum.
- Supported currencies: NZD, AUD, USD, GBP, EUR (configurable).
- Execution date may be today or later, in NZ time. No business-day / holiday calendar.
- Fraud decisions are recorded, not enforced: a DECLINE doesn't stop the ledger posting in this
  exercise (in reality the ledger would hold until screening — see below).
- Authentication/authorisation are out of scope, so `/admin` and `/simulation` are unprotected
  (`/simulation` is disabled by config in prod).
- `Idempotency-Key` is required; keys are kept indefinitely (no expiry).

## What I'd do with more time / before production

- **Real broker** (Kafka with idempotent producer, or the bank's standard) behind `MessageBroker`;
  partition by payment id; DLQ topics.
- **Multi-replica polling**: correctness is already safe with several replicas (idempotency), but
  they'd do duplicate work. Claim rows with `SELECT … FOR UPDATE SKIP LOCKED` / a lease column, or
  ShedLock — or let Kafka consumer groups do it.
- **Business orchestration**: ledger posting should wait for an ACCEPT (or a cleared REVIEW);
  a payment state machine (ACCEPTED → SCREENED → POSTED / DECLINED).
- **Circuit breaker** (Resilience4j) on the vendor so we stop calling it while it's clearly down.
- **Observability**: metrics for outbox lag/age, DLQ size, vendor latency/error rate with alerts;
  trace/correlation ids propagated through events (OpenTelemetry).
- **Security**: authN/Z (OAuth2 resource server), mTLS to vendor, PII masking in logs, audit trail.
- **Data**: retention/archiving for outbox, deliveries and idempotency keys; key expiry.
- Contract tests against the vendor's sandbox; Testcontainers (PostgreSQL) in CI; load test.

## Tech stack

Spring Boot 4.1.x (Spring Framework 7, Jakarta EE 11, Hibernate 7, **Jackson 3** under `tools.jackson.*`), Java 21,
Maven. Boot 4 specifics in this repo: `spring-boot-starter-webmvc` (was `-web`), `spring-boot-starter-flyway`
(Flyway is no longer auto-configured from the bare jar), and the per-technology test starter
`spring-boot-starter-webmvc-test` for `@AutoConfigureMockMvc`. JSON annotations (`@JsonFormat`, `@JsonInclude`)
still live in `com.fasterxml.jackson.annotation`; the mapper itself is `tools.jackson.databind.json.JsonMapper`.
