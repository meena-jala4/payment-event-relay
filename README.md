# Payment Event Relay

A Spring Boot service that accepts a payment instruction, stores it, and
then makes sure a `PaymentSubmitted` event reaches three consumers (notifications, fraud screening
and the ledger) without anything being lost or applied twice.

```
 POST /payments (Idempotency-Key header)
        |
        v
 [ one DB transaction: payment row + outbox_event row ]
        |
        |  outbox relay polls and keeps retrying while the broker is down
        v
 [ broker stub (a table): one copy per consumer, at-least-once ]
        |                    |                     |
        v                    v                     v
  notification         fraud screening         ledger
  (inbox check)        (inbox check, XML       (inbox check +
                        vendor, retries,        unique payment_id)
                        dead-letter)
```

## How to run it

All you need is Java 21. It uses an H2 database and has its own fake fraud service, so there's nothing else to set up.

```bash
./mvnw spring-boot:run       # starts on http://localhost:8080
./mvnw verify                # runs all the tests, coverage report in target/site/jacoco
docker compose up --build    # optional, same app against PostgreSQL
```

### Trying it out

```bash
# send a payment (run it twice: the second time you get 200 and the same payment back)
curl -i -X POST localhost:8080/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-001' \
  -d '{"sourceAccount":"12-3456-7890123-00",
       "destination":{"name":"Aroha Smith","accountNumber":"02-0100-0123456-001"},
       "amount":"1500.00","currency":"NZD","reference":"Rent Nov",
       "requestedExecutionDate":"2027-01-15"}'

curl localhost:8080/payments/<id>     # fraudScreening goes from PENDING to COMPLETED

# break things on purpose (these endpoints are off in prod)
curl -X POST 'localhost:8080/simulation/broker?available=false'        # broker outage
curl -X POST 'localhost:8080/simulation/fraud-provider?failureRate=1'  # vendor completely down
curl 'localhost:8080/admin/deliveries?status=DEAD'                     # dead-letter queue
curl -X POST localhost:8080/admin/deliveries/<id>/replay               # try a dead one again
```

The fraud stub decides by amount: over 10,000 is DECLINE, over 1,000 is REVIEW, anything else is
ACCEPT. It also fails randomly on purpose (30% by default, change it with `FRAUD_STUB_FAILURE_RATE`).
Some of those failures come back as HTTP 200 with an error in the body, which is the trap the brief
describes.

## API

| Request | Response |
|---|---|
| `POST /payments` with a new key | `201` and a `Location` header |
| same key, same body | `200` with `Idempotent-Replayed: true` and the original payment |
| same key, different body | `422` |
| bad fields, malformed JSON or missing key | `400` with a list of field errors |
| `GET /payments/{id}` | `200` including `fraudScreening`, or `404` |

Errors use `application/problem+json`.

## Why I built it this way

**Outbox for publishing events.** The payment and its event are saved in the same transaction. If I
saved the payment and then called a broker, a crash or an outage in between would leave a payment
nobody hears about. With the outbox that can't happen, and the relay keeps retrying until the broker
takes the event. The cost is a bit of latency (it polls every 500 ms) and extra load on the database.
Debezium reading the outbox table would be faster, but it's more infrastructure than this needs.

**At-least-once delivery, and consumers that cope with duplicates.** I didn't try for exactly-once.
Each event has a stable `eventId`, and every consumer writes `(consumer, event_id)` to a
`processed_event` table with a unique constraint in the same transaction as its work. The ledger
also has a unique `payment_id`, so even an event re-published with a new id can't post the same
payment twice. Posting twice is the one thing a bank really can't have.

**Broker stub as a table.** An in-memory queue would lose everything on restart, so the stub is a
table (`event_delivery`) with one row per consumer. It doesn't remove duplicates, on purpose, like a
real broker. It sits behind a `MessageBroker` interface, so moving to Kafka or Service Bus means
swapping one class.

**Failures stay separate.** Each consumer has its own polling loop, so a slow fraud vendor doesn't
hold up the ledger. Each delivery retries on its own schedule (exponential backoff with jitter, from
1 second up to 5 minutes). After 8 attempts, or straight away for errors that can't be fixed by
retrying, a delivery is dead-lettered. It stays in the table and can be replayed from
`/admin/deliveries`. There is no ordering guarantee between events, which is fine because each
payment produces one.

**The fraud vendor.**
- Connect and read timeouts are set (2 s and 3 s), so a slow vendor becomes a retry and not a stuck thread.
- The call happens outside any DB transaction, so a slow vendor can't hold a connection.
- The HTTP status isn't trusted: an `<Error>` element means failure even with a 200.
- Timeouts, 5xx and `Retryable=true` are retried. 4xx and `Retryable=false` go to the dead-letter queue.
- The XML is read by namespace URI, not by the `fs:` prefix, and only the elements I need are read,
  so new elements from the vendor are ignored.
- DOCTYPEs are switched off to block XXE, since it's XML from a third party.
- A decision value I don't recognise is saved as `REVIEW`, never `ACCEPT`.

**Money.** `BigDecimal` plus an ISO currency code, stored as `NUMERIC(19,4)`, never a double. The
number of decimal places is checked per currency, so `10.001 NZD` is rejected and not rounded,
because quietly changing someone's amount seemed worse than a 400. The amount is a JSON string so
JavaScript clients don't turn it into a float. Integer cents would also work, but then every
consumer needs to know each currency's exponent.

**Idempotency keys.** The `Idempotency-Key` header is required because mobile apps retry on bad
networks. I store a SHA-256 of the normalised request, so `100.5` and `100.50` count as the same.
A unique constraint on the key decides which of two simultaneous retries wins.

**Dates.** "Not in the past" is checked in Pacific/Auckland time, not the container's UTC. The two
disagree between midnight and 1 pm NZ time. The clock is injected so a test covers this.

**One schema for H2 and PostgreSQL.** Flyway owns the schema and the same scripts run on both. H2
keeps "clone and run" simple, and dev and prod point at PostgreSQL through config only.

## Tests

`./mvnw verify` runs everything. The tests that matter most are in `ReliabilityTest`:
- a payment accepted while the broker is down still gets published once it's back
- duplicate deliveries (including a new eventId for the same payment) give exactly one ledger entry
- when the fraud vendor is completely down, that delivery is dead-lettered (after 3 attempts in the
  test profile, 8 by default), and the notification and ledger still go through

`PaymentApiTest` covers the idempotency rules and validation, `FraudResponseParserTest` covers the
vendor XML cases (error inside a 200, unknown elements, XXE), and `PaymentValidatorTest` covers the
NZ-date rule.

## Deployment files

- **Dockerfile:** two stages (Maven build, then a JRE-only image). Runs as a non-root user, and the
  heap is sized from the container's memory limit.
- **Kubernetes (Kustomize):** `k8s/base` plus `dev` and `prod` overlays. Deployment with startup,
  liveness and readiness probes (liveness doesn't check the database, so a DB blip doesn't restart
  every pod), rolling updates with no downtime, non-root and read-only filesystem. Also a Service,
  ConfigMap, HPA, PodDisruptionBudget, NetworkPolicy and ServiceAccount. Secrets are only referenced
  with `secretKeyRef`; `docs/secret.example.yaml` shows the shape and holds no real values. Dev has 1
  replica with the stub on. Prod has 3 to 10 replicas, the real vendor URL and the stub off.
- **Jenkinsfile:** build and test, SonarQube, dependency scan, image build, Trivy scan, push, deploy
  to dev, smoke test, manual approval, deploy to prod. The same image is promoted through the
  environments and only the config changes.

I haven't deployed the Kubernetes files or run the Jenkins pipeline. I checked that the manifests
render with `kubectl kustomize`.

## Assumptions

- Account numbers look like `BB-bbbb-AAAAAAA-SSS`. Only the format is checked, not a checksum.
- Supported currencies are NZD, AUD, USD, GBP and EUR (configurable).
- The execution date can be today or later in NZ time. No holiday calendar.
- Fraud decisions are recorded but not enforced here, so a DECLINE doesn't stop the ledger posting.
- No authentication, so `/admin` and `/simulation` are open. `/simulation` is off in prod.
- Idempotency keys are kept forever, with no expiry.

## What I'd do with more time

- Use a real broker (Kafka or whatever the bank uses) behind `MessageBroker`.
- Stop several replicas polling the same rows, for example with `SELECT ... FOR UPDATE SKIP LOCKED`.
  It's already safe because of the idempotency, just wasteful.
- Make the ledger wait for an ACCEPT, with a proper payment state machine.
- Add a circuit breaker on the vendor call.
- Add metrics and alerts for outbox age, dead-letter count and vendor errors, plus tracing.
- Add authentication, PII masking in logs and an audit trail.
- Clean up old outbox, delivery and idempotency rows.
- Run tests against PostgreSQL with Testcontainers, and add a load test.

## Tech

Java 21, Spring Boot 4.1, Maven, Flyway, H2 locally and PostgreSQL in Docker and Kubernetes.
