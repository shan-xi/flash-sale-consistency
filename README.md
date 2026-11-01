# Flash Sale Consistency Demo

A Java/Spring Boot project demonstrating how to build a **flash-sale purchase system** that remains correct under:

- High concurrency
- Multiple application instances
- Redis failure
- Duplicate purchase requests
- Database contention
- Cache inconsistency
- Distributed locking
- Transactional event processing

The main goal of this project is **correctness first**, while using Redis to improve performance.

---

## 1. Architecture

```text
                         ┌──────────────────────┐
                         │      Client          │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │ Spring Boot API      │
                         │ Multiple Instances   │
                         └──────────┬───────────┘
                                    │
                     ┌──────────────┴──────────────┐
                     │                             │
                     ▼                             ▼
              ┌─────────────┐              ┌──────────────┐
              │    Redis    │              │ PostgreSQL   │
              │             │              │              │
              │ Cache       │              │ Source of    │
              │ Distributed │              │ Truth        │
              │ Lock        │              │              │
              └─────────────┘              └──────────────┘
                                                   │
                                                   │
                                                   ▼
                                          ┌────────────────┐
                                          │ Transactional   │
                                          │ Outbox          │
                                          └────────────────┘
```

### Core principle

> **PostgreSQL is the source of truth. Redis is only a performance optimization.**

Redis must never be required for business correctness.

---

# 2. Technology Stack

- Java 21
- Spring Boot
- Spring Web
- Spring Data JPA
- PostgreSQL
- Redis
- Redisson
- Docker / Docker Compose
- Maven
- JUnit
- Testcontainers

---

# 3. Main Problem

Imagine a product has:

```text
stock = 100
```

1000 users simultaneously try to purchase the product.

Without proper concurrency control, multiple application instances could execute:

```text
read stock = 1

User A → stock = 1
User B → stock = 1

User A → stock = 0
User B → stock = 0
```

The system could oversell the product.

We therefore need concurrency control.

---

# 4. Distributed Lock

When Redis is available, the application uses a **Redisson distributed lock**.

For example:

```text
flash-sale:product:1:lock
```

The flow is:

```text
Request
   │
   ▼
Redisson distributed lock
   │
   ▼
PostgreSQL transaction
   │
   ▼
SELECT ... FOR UPDATE
   │
   ▼
Decrease stock
   │
   ▼
Create order
   │
   ▼
Create outbox event
   │
   ▼
Commit
```

The distributed lock reduces the amount of concurrent work reaching PostgreSQL.

However:

> The Redis lock is not the final correctness boundary.

---

# 5. PostgreSQL Row Lock

The final correctness boundary is PostgreSQL.

The product row is locked using:

```sql
SELECT *
FROM product
WHERE id = ?
FOR UPDATE;
```

This is a **row-level lock**.

For example:

```text
Product 1
──────────────
stock = 100
```

Two transactions try to purchase at the same time.

```text
Transaction A
    │
    ├── SELECT ... FOR UPDATE
    │
    ├── locks Product 1
    │
    └── stock 100 → 99


Transaction B
    │
    ├── SELECT ... FOR UPDATE
    │
    ├── waits for Transaction A
    │
    └── stock 99 → 98
```

Therefore the database prevents both transactions from modifying the same stock value incorrectly.

---

# 6. What Happens When Redis Fails?

This is one of the most important parts of the project.

Redis is **not required for the system to continue processing purchases**.

If Redis is unavailable:

```text
Request
   │
   ▼
Try Redisson lock
   │
   ▼
Redis unavailable
   │
   ▼
Fallback
   │
   ▼
PostgreSQL
   │
   ▼
SELECT ... FOR UPDATE
   │
   ▼
Decrease stock
   │
   ▼
Create order
   │
   ▼
Commit
```

The system continues working.

The trade-off is performance.

Without Redis:

```text
More requests
      │
      ▼
PostgreSQL
      │
      ▼
More row-lock contention
```

So Redis improves scalability, but PostgreSQL still guarantees correctness.

---

# 7. Important Redis Failure Detection

There is an important difference between:

### Case A — Redis is healthy but another request owns the lock

```text
tryLock() = false

Redis is healthy
```

This means there is real lock contention.

We should **not bypass the distributed lock**.

The request can be rejected or retried according to the application policy.

---

### Case B — Redis itself is unavailable

For example:

```text
tryLock()
    ↓
false / exception
    ↓
Redis health check fails
```

This means Redis is unavailable.

The application falls back to PostgreSQL.

```text
Redis unavailable
        │
        ▼
PostgreSQL row lock
```

This distinction is important.

We must not interpret every `tryLock() == false` as "Redis is down."

---

# 8. Redis Is a Disposable Cache

This project intentionally treats Redis as a **non-persistent cache**.

Redis does not contain the source-of-truth business data.

If Redis crashes:

```text
Before crash:

PostgreSQL
stock = 97

Redis
product:1:stock = 97
```

After Redis restarts:

```text
PostgreSQL
stock = 97

Redis
EMPTY
```

This is completely acceptable.

The database still contains the real data.

---

# 9. Redis Recovery

Because Redis is a disposable cache, we do not need to restore Redis from persisted cache data.

After Redis comes back:

```text
PostgreSQL
stock = 96

Redis
EMPTY
```

This is simply a cache miss.

The application can repopulate Redis from PostgreSQL using the normal cache-aside pattern:

```text
GET product
      │
      ▼
Check Redis
      │
      ├── HIT ──────► return cached value
      │
      ▼
    MISS
      │
      ▼
Read PostgreSQL
      │
      ▼
Populate Redis
      │
      ▼
Return result
```

Therefore:

> Redis recovery does not change the source of truth. PostgreSQL remains authoritative.

---

# 10. Cache Consistency

The most important rule is:

```text
PostgreSQL
    ↓
source of truth

Redis
    ↓
cache
```

We should never make a final inventory decision based only on Redis.

For example, Redis might contain:

```text
stock = 97
```

while PostgreSQL contains:

```text
stock = 96
```

PostgreSQL is still correct.

---

# 11. Transactional Outbox

The purchase operation modifies multiple pieces of data:

```text
Product stock
Order
Outbox event
```

These must be committed atomically.

The transaction is conceptually:

```text
BEGIN

SELECT product FOR UPDATE

check stock

decrease stock

INSERT order

INSERT outbox event

COMMIT
```

If the transaction succeeds:

```text
Product updated
Order created
Outbox event created
```

If the transaction fails:

```text
Product rollback
Order rollback
Outbox rollback
```

This prevents situations such as:

```text
stock decreased
but
order was not created
```

---

# 12. Why Use an Outbox?

Suppose PostgreSQL commits successfully:

```text
stock = 96
```

but Redis is unavailable.

We cannot depend on Redis at transaction time.

Instead, we create an outbox event in the same PostgreSQL transaction.

```text
PostgreSQL transaction
────────────────────────────

UPDATE product

INSERT order

INSERT outbox_event

COMMIT
```

Later, an outbox worker processes the event.

This allows Redis synchronization/invalidation to happen asynchronously.

---

# 13. Outbox Event Strategy

The outbox event is used for **cache invalidation**.

Conceptually:

```text
Purchase
   │
   ▼
PostgreSQL transaction
   │
   ├── update stock
   ├── create order
   └── create invalidation event
             │
             ▼
       transaction commits
             │
             ▼
        Outbox Worker
             │
             ▼
       Redis DEL cache
```

Invalidation is safer than blindly writing an old stock value into Redis.

For example, an old event should not overwrite a newer cache value with stale inventory.

---

# 14. Idempotency

A client may send the same request multiple times:

```text
requestId = request-001
```

For example:

```text
Client
   │
   ├── request-001
   ├── timeout
   └── request-001 again
```

We must not create two orders.

The `requestId` is therefore unique in the database.

Conceptually:

```text
requestId
    │
    ▼
unique constraint
    │
    ▼
only one successful order
```

This protects against duplicate requests even when multiple application instances receive the same request.

---

# 15. Multi-Instance Deployment

The application is designed to run on multiple JVM instances.

For example:

```text
                 Load Balancer
                       │
          ┌────────────┼────────────┐
          │            │            │
          ▼            ▼            ▼
      Instance A   Instance B   Instance C
          │            │            │
          └────────────┼────────────┘
                       │
                ┌──────┴──────┐
                │             │
                ▼             ▼
             Redis       PostgreSQL
```

The Redisson distributed lock works across JVMs.

A normal Java `synchronized` block would not work here because:

```text
Instance A → JVM A lock
Instance B → JVM B lock
```

They have different memory spaces.

The distributed lock coordinates them through Redis.

---

# 16. Why We Still Need PostgreSQL Locking

It may look like Redis already provides a distributed lock.

Why do we still use:

```sql
SELECT ... FOR UPDATE
```

?

Because Redis can fail.

```text
Redis
  │
  ├── healthy
  │
  └── unavailable
```

If Redis is unavailable, we still need correctness.

Therefore:

```text
Redis distributed lock
        +
PostgreSQL row lock
```

have different responsibilities.

### Redis lock

Mainly:

```text
reduce contention
reduce unnecessary concurrent work
coordinate multiple application instances
```

### PostgreSQL row lock

Mainly:

```text
final correctness boundary
```

---

# 17. Redis Failure Trade-Off

When Redis is healthy:

```text
High concurrency
      │
      ▼
Redis distributed lock
      │
      ▼
fewer requests reach DB simultaneously
      │
      ▼
lower DB contention
```

When Redis is down:

```text
High concurrency
      │
      ▼
PostgreSQL
      │
      ▼
SELECT FOR UPDATE
      │
      ▼
more DB contention
```

The system is therefore:

> **Degraded in performance, but not degraded in correctness.**

This is a major design goal of the project.

---

# 18. API

## Get Product

```http
GET /products/{productId}
```

Example:

```bash
curl http://localhost:8080/products/1
```

---

## Purchase Product

```http
POST /products/{productId}/purchase
Content-Type: application/json
```

Request:

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

Successful response:

```json
{
  "orderId": 1,
  "productId": 1,
  "userId": 1001,
  "quantity": 1,
  "remainingStock": 99
}
```

---

# 19. Running the Project

## Start PostgreSQL and Redis

```bash
docker compose up -d
```

Check containers:

```bash
docker compose ps
```

Expected services:

```text
flash-sale-postgres
flash-sale-redis
```

---

## Start Spring Boot

Using Maven:

```bash
./mvnw spring-boot:run
```

Or:

```bash
mvn spring-boot:run
```

The application runs on:

```text
http://localhost:8080
```

---

# 20. Simulate Redis Failure

Stop Redis:

```bash
docker compose stop redis
```

Then send a purchase:

```bash
curl -X POST http://localhost:8080/products/1/purchase \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 1001,
    "quantity": 1,
    "requestId": "redis-down-001"
  }'
```

The purchase should still succeed.

The application falls back to:

```text
PostgreSQL
    ↓
SELECT ... FOR UPDATE
    ↓
decrease stock
```

---

# 21. Start Redis Again

```bash
docker compose start redis
```

Redis starts with an empty cache because Redis is treated as a disposable cache.

The database remains authoritative.

The next cache miss can repopulate Redis from PostgreSQL.

---

# 22. Important Failure Scenarios

| Scenario | Expected behavior |
|---|---|
| Redis healthy | Use Redisson distributed lock |
| Redis lock contention | Do not bypass healthy Redis lock |
| Redis unavailable | Fall back to PostgreSQL row lock |
| Redis crashes | Purchase system continues using PostgreSQL |
| Redis restarts | Cache may be empty |
| Redis cache miss | Read PostgreSQL and repopulate cache |
| Duplicate request | Idempotency prevents duplicate order |
| Multiple JVMs | Redisson coordinates instances |
| High DB contention | PostgreSQL row lock serializes updates |
| DB transaction fails | Stock/order/outbox rollback together |
| Redis delete fails | Business transaction remains successful |

---

# 23. What This Project Demonstrates

This project is intentionally designed as a **backend system-design interview project**.

Important interview topics covered:

### Concurrency

- Multiple threads
- Multiple JVM instances
- Distributed locks
- Database row locks
- High contention

### Redis

- Distributed locking with Redisson
- Cache-aside
- Cache invalidation
- Redis failure
- Redis recovery
- Disposable cache design
- Why Redis should not be the source of truth

### PostgreSQL

- Transactions
- `SELECT FOR UPDATE`
- Row-level locking
- Unique constraints
- Source of truth

### Reliability

- Redis failure fallback
- Idempotency
- Transactional outbox
- Eventual cache synchronization

---

# 24. Design Summary

The most important design can be summarized as:

```text
                    Purchase Request
                           │
                           ▼
                Try Redis Distributed Lock
                           │
              ┌────────────┴────────────┐
              │                         │
        Redis healthy              Redis down
              │                         │
              ▼                         ▼
       Redisson Lock             Skip Redis Lock
              │                         │
              └────────────┬────────────┘
                           ▼
                    PostgreSQL TX
                           │
                           ▼
                 SELECT FOR UPDATE
                           │
                           ▼
                    Check stock
                           │
                           ▼
                  Decrease stock
                           │
                           ▼
                    Create order
                           │
                           ▼
                 Create outbox event
                           │
                           ▼
                        COMMIT
                           │
                           ▼
                    Return success
```

The fundamental rule is:

> **Redis improves performance. PostgreSQL guarantees correctness.**

If Redis disappears, the system becomes slower under heavy contention, but it should still produce the correct inventory and orders.