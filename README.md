# Flash Sale Consistency Project — Redis Failure Fallback

A Spring Boot 3 / Java 21 project for practicing flash-sale inventory consistency:

- PostgreSQL as the source of truth
- PostgreSQL `SELECT ... FOR UPDATE` as the final inventory lock
- Redis as a cache
- Redisson distributed lock when Redis is healthy
- **No Redis Lua scripts**
- Redis failure fallback to PostgreSQL
- multiple threads / multiple application instances
- idempotency
- transactional outbox
- cache recovery after Redis returns

## Core rule

```text
Redis distributed lock = performance / cross-instance coordination
PostgreSQL row lock   = correctness / final serialization
Redis cache           = optional derived data
```

The purchase operation must still work when Redis is completely unavailable.

## Purchase flow

### Redis healthy

```text
HTTP request
    |
    v
Try Redisson RLock
    |
    v
lock acquired
    |
    v
PostgreSQL transaction
    |
    v
SELECT product FOR UPDATE
    |
    v
check stock -> decrease stock -> create order -> create outbox
    |
    v
COMMIT
    |
    v
best-effort Redis cache invalidation
```

### Redis down

```text
HTTP request
    |
    v
Try Redisson RLock
    |
    X Redis unavailable
    |
    v
fallback immediately
    |
    v
PostgreSQL transaction
    |
    v
SELECT product FOR UPDATE
    |
    v
check stock -> decrease stock -> create order -> create outbox
    |
    v
COMMIT
```

The application therefore becomes **slower under high contention**, but it does not lose inventory correctness.

## Why PostgreSQL is still required when Redis is healthy

Imagine stock is 1 and two requests arrive together.

```text
Instance A                 Instance B
    |                          |
    +------ Redis lock -------+
               |
               v
        PostgreSQL row lock
               |
               v
          stock = 1
               |
          A -> stock 0
               |
             COMMIT
               |
               v
          B gets row lock
               |
          stock = 0
               |
          OutOfStock
```

The Redis lock reduces application-level contention, but the database remains the authoritative guard against
overselling.

## Redis failure is handled at every purchase-path Redis operation

There are three important failure points:

### 1. Redisson lock acquisition

The code handles both possibilities:

```text
tryLock() throws Redis connection/timeout exception
        -> fallback to PostgreSQL
```

and:

```text
tryLock() returns false
        -> ping Redis
             |
             +-- PONG -> real lock contention -> reject after wait timeout
             |
             +-- no PONG -> Redis outage -> fallback to PostgreSQL
```

### 2. Cache invalidation after DB commit

Redis is deliberately **not** part of the DB transaction.

If this succeeds:

```text
PostgreSQL COMMIT
```

but Redis is already down, the purchase still succeeds.

The synchronous cache deletion is best effort.

### 3. Lock release

Redis can fail after the database transaction has committed.

The application does not convert a successful purchase into an error just because `unlock()` cannot reach Redis.

The Redisson lease eventually expires.

## Redisson configuration

The project uses the Redisson core client directly instead of relying on Redisson Spring Boot auto-configuration. This
gives the demo explicit failure behavior:

```yaml
app:
  redis:
    connect-timeout-ms: 1000
    command-timeout-ms: 1000
    retry-attempts: 0
```

This is important for the fallback demo. We do not want Redisson to keep retrying Redis for a long time before
PostgreSQL fallback happens.

## PostgreSQL transaction

The DB transaction is implemented in a separate Spring bean so `@Transactional` is actually applied.

The order is important:

```text
BEGIN

SELECT product
FOR UPDATE

check requestId

check stock

stock--

INSERT order

INSERT outbox event

COMMIT
```

The product row is locked **before** checking the idempotency key. This prevents two concurrent requests for the same
product from both passing the initial idempotency check and then racing on the unique request ID constraint.

A reused `requestId` with different user/product/quantity is rejected instead of silently returning the old order.

## Outbox design

The outbox is written in the same PostgreSQL transaction as the inventory change:

```text
BEGIN
  UPDATE product
  INSERT order
  INSERT outbox_event
COMMIT
```

The outbox event is an **invalidation event**, not a snapshot of the old stock value.

Why?

Suppose:

```text
Event A: stock 99
Event B: stock 98
```

If events are processed out of order, blindly writing their stock values into Redis could produce:

```text
Redis = 99
```

while PostgreSQL is already 98.

Instead, the worker does:

```text
outbox event
    |
    v
DELETE product:{id}:stock
```

Then the cache rebuild process reads the current PostgreSQL stock.

If Redis is down, the outbox transaction rolls back and the event remains `PENDING`.

When Redis returns, it is retried.

Multiple application instances can process the same event safely because the outbox row is locked with PostgreSQL
`FOR UPDATE`, and cache deletion is idempotent.

## Cache rebuild

Every few seconds the cache rebuild reads PostgreSQL and repopulates Redis.

Redis is never used as the final inventory decision.

Therefore a stale cache can affect performance/display, but it cannot cause an oversell.

## Run

Requirements:

- Java 21
- Maven 3.9+
- Docker

Start infrastructure:

```bash
docker compose up -d
```

Run:

```bash
mvn clean spring-boot:run
```

## Test normal purchase

```bash
curl -X POST http://localhost:8080/products/1/purchase \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1001,
    "quantity": 1,
    "requestId": "request-normal-001"
  }'
```

## Test Redis failure

First make sure the application is running and PostgreSQL is still running.

Stop Redis:

```bash
docker stop flash-sale-redis
```

Now use a **new requestId**:

```bash
curl -X POST http://localhost:8080/products/1/purchase \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1002,
    "quantity": 1,
    "requestId": "request-redis-down-001"
  }'
```

Expected behavior:

```text
Redis unavailable
      |
      v
PostgreSQL SELECT FOR UPDATE
      |
      v
purchase succeeds
```

Then start Redis again:

```bash
docker start flash-sale-redis
```

The outbox/rebuild mechanisms will repair the cache.

## Test idempotency

Send the same request twice:

```bash
curl -X POST http://localhost:8080/products/1/purchase \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1001,
    "quantity": 1,
    "requestId": "request-idempotent-001"
  }'
```

The second call returns the existing order instead of creating another order.

## Test multiple application instances

Build:

```bash
mvn clean package
```

Run multiple JVMs against the same PostgreSQL and Redis:

```bash
java -jar target/flash-sale-consistency-1.0.0.jar --server.port=8081
java -jar target/flash-sale-consistency-1.0.0.jar --server.port=8082
java -jar target/flash-sale-consistency-1.0.0.jar --server.port=8083
```

When Redis is healthy, the Redisson lock is shared between all three JVMs.

If Redis is stopped, all three JVMs can still purchase because PostgreSQL `SELECT FOR UPDATE` serializes the inventory
row.

## Important trade-off

Redis fallback is a **correctness-preserving degradation**, not a performance-preserving degradation.

With Redis:

```text
many requests
     |
     v
Redis distributed lock
     |
     v
less DB contention
```

Without Redis:

```text
many requests
     |
     v
PostgreSQL row lock
     |
     v
high DB contention
```

So the system remains correct but can become much slower during a Redis outage.

That is a useful senior-backend interview trade-off to explain.

## Test suite

Run:

```bash
mvn test
```

The project contains unit tests for:

- Redis exception -> PostgreSQL fallback
- Redisson `tryLock()` returning false while Redis is down -> PostgreSQL fallback
- healthy Redis + lock contention -> do not bypass the distributed lock
- Testcontainers PostgreSQL/Redis infrastructure startup

## Interview summary

If asked:

> Redis is your distributed lock. What happens when Redis goes down?

Answer:

> Redisson depends on Redis, so the distributed lock becomes unavailable. I don't make that lock the final correctness
> boundary. PostgreSQL is the source of truth and the purchase transaction uses `SELECT FOR UPDATE` on the product row.
> When Redis is healthy, Redisson reduces contention across application instances. When Redis is unavailable, we skip the
> distributed lock and fall back to the PostgreSQL row lock. The system degrades in throughput but remains correct and
> does not oversell. Redis cache updates are asynchronous/best effort through the outbox and rebuild process.
