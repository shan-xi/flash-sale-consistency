# Flash Sale Consistency Demo

Java 21 / Spring Boot project demonstrating a flash-sale purchase system that remains correct under high concurrency,
multiple application instances, Redis failure, duplicate requests, and cache failures.

## Core design

> **PostgreSQL is the source of truth. Redis is a disposable performance cache and distributed-lock mechanism.**

```text
                         Client
                           |
                           v
                  Spring Boot instances
                    /              \
                   v                v
              Redis             PostgreSQL
          cache + lock          source of truth
                                    |
                                    v
                             Transactional outbox
```

## Technology

- Java 21
- Spring Boot 3.5
- Spring Data JPA
- PostgreSQL
- Redis
- Redisson
- Docker Compose
- JUnit / Testcontainers

## Read path: real Redis cache benefit

`GET /products/{id}` uses cache-aside.

```text
GET product
     |
     v
   Redis
   /   \
 HIT   MISS
  |      |
  v      v
return PostgreSQL
         |
         v
      warm Redis
         |
         v
       return
```

On a Redis hit, **no PostgreSQL query is made**. Redis stores the whole product read model:

```json
{
  "id": 1,
  "name": "iPhone 17",
  "stock": 99
}
```

The API response includes `source` (`REDIS` or `DATABASE`) so the cache behavior is easy to observe during the demo.

### Cache-miss consistency

A cache miss reads the product with PostgreSQL `SELECT ... FOR UPDATE` and writes the snapshot to Redis while holding
the row lock. This prevents this race:

```text
GET reads DB stock 100
        |
        | purchase commits stock 99
        |
GET writes old 100 into Redis  <-- stale cache
```

The row lock serializes the DB-to-cache copy with purchases.

## Purchase path

```text
POST purchase
      |
      v
Try Redisson distributed lock
      |
      +---- Redis healthy + lock acquired ----+
      |                                        |
      +---- Redis unavailable -----------------+
                                               |
                                               v
                                  PostgreSQL transaction
                                               |
                                               v
                                  SELECT ... FOR UPDATE
                                               |
                                               v
                                        check stock
                                               |
                                               v
                                      decrease stock
                                               |
                                               v
                                       create order
                                               |
                                               v
                                    create outbox event
                                               |
                                               v
                                             COMMIT
                                               |
                                               v
                                      best-effort cache delete
```

PostgreSQL row locking is the final correctness boundary. Redis locking reduces database contention but is not required
for correctness.

## Redis failure behavior

If Redis is unavailable, the purchase path still works:

```text
Redis down
   |
   v
Skip distributed lock
   |
   v
PostgreSQL SELECT ... FOR UPDATE
   |
   v
purchase succeeds/fails based on DB state
```

The system distinguishes:

- `tryLock() == false` + Redis healthy = real lock contention; do not bypass the lock.
- `tryLock()` exception or `false` + Redis health check fails = Redis outage; fall back to PostgreSQL.

Redis failures during cache delete also never turn a committed purchase into an HTTP failure. The outbox retries
invalidation.

## Redis is non-persistent

This project intentionally treats Redis as a disposable cache. No Redis volume or persistence configuration is used.

If Redis restarts:

```text
PostgreSQL: stock = 96
Redis:      empty
```

That is not a data-loss problem. The next cache miss reads PostgreSQL and warms Redis again.

The application does not depend on Redis persistence to recover business data.

## Cache invalidation

Every successful purchase creates an outbox event in the same PostgreSQL transaction as the stock update and order
creation.

```text
DB transaction
  update stock
  create order
  create cache-invalidation outbox event
  COMMIT
        |
        v
   Outbox worker
        |
        v
     Redis DEL
```

The event contains the product ID, not an old stock snapshot. This avoids replaying stale stock values into Redis.

## Transactional outbox

The stock update, order creation, and outbox event are atomic:

```sql
BEGIN;

SELECT...FOR
UPDATE;
UPDATE product...;
INSERT INTO orders...;
INSERT INTO outbox_events...;

COMMIT;
```

If the transaction rolls back, all three changes roll back together.

## Idempotency

`requestId` is unique in PostgreSQL. Repeating the same purchase request does not create another order.

The database also verifies that a reused `requestId` has the same user, product, and quantity.

## Multiple application instances

The project can run as:

```text
                 Load Balancer
                 /     |     \
                v      v      v
             App A  App B  App C
                \      |      /
                 \     |     /
                   Redis
                     |
                 PostgreSQL
```

A JVM-local `synchronized` lock would not coordinate App A and App B. Redisson provides the cross-instance lock.
PostgreSQL `SELECT FOR UPDATE` still guarantees correct stock updates if Redis is unavailable.

## API

### Get product

```http
GET /products/{productId}
```

Example:

```bash
curl http://localhost:8080/products/1
```

Example response from Redis:

```json
{
  "id": 1,
  "name": "iPhone 17",
  "stock": 99,
  "source": "REDIS"
}
```

### Purchase

```http
POST /products/{productId}/purchase
Content-Type: application/json
```

```json
{
  "userId": 1001,
  "quantity": 1,
  "requestId": "request-001"
}
```

Example:

```bash
curl -X POST http://localhost:8080/products/1/purchase \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1001,
    "quantity": 1,
    "requestId": "request-001"
  }'
```

## Run locally

Start PostgreSQL and Redis:

```bash
docker compose up -d
```

Start Spring Boot:

```bash
mvn spring-boot:run
```

The application runs on `http://localhost:8080`.

## Test Redis failure

Stop Redis:

```bash
docker compose stop redis
```

Then purchase:

```bash
curl -X POST http://localhost:8080/products/1/purchase \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1001,
    "quantity": 1,
    "requestId": "redis-down-001"
  }'
```

The purchase should still use PostgreSQL row locking and succeed if stock is available.

Start Redis again:

```bash
docker compose start redis
```

Redis starts empty. A subsequent `GET` will repopulate the product cache from PostgreSQL.

## Interview concepts demonstrated

- Cache-aside pattern
- Redis as a disposable cache
- Distributed locking with Redisson
- PostgreSQL pessimistic row locking
- Redis failure fallback
- Multiple JVM instances
- High-contention inventory updates
- Idempotency
- Transactional outbox
- Cache invalidation
- Cache-miss race prevention
- Eventual cache consistency

## Most important rule

```text
Redis improves performance.
PostgreSQL guarantees correctness.
```

If Redis disappears, the application becomes less efficient under heavy contention, but the inventory and orders remain
correct because the final business decision is always made by PostgreSQL.
