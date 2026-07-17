# Inventory Management System

A robust, production-ready inventory management backend built with Spring Boot, PostgreSQL, Kafka, and Redis.

## Features

- **Item Management:** Create, read, update, delete, and list items.
- **Stock Adjustment:** Thread-safe stock adjustment with idempotency support.
- **Outbox Pattern:** Guaranteed delivery of events to Kafka using the transactional outbox pattern.
- **Rate Limiting:** Global rate limiting using Redis and Bucket4j.
- **Reporting:** Simple inventory reporting and export to CSV.

## Architecture
The application uses a layered monolith architecture with an event-driven core.

### Deduplication Strategy
The application employs the **Transactional Outbox Pattern** to guarantee at-least-once delivery of events to the message broker (Kafka). 
Downstream consumers **must** implement idempotency to handle potential duplicate messages safely. 
Every published event includes a unique `eventId` and `timestamp`. Consumers should use the `eventId` (or a composite of `aggregateId` and `timestamp`) as an idempotency key to deduplicate incoming messages on their side.

### Performance & Metrics
The application uses Spring Boot Actuator and Micrometer to expose performance metrics and operational health.
- **Outbox Processing Time**: View timing metrics for the outbox processor via `/actuator/metrics/outbox.process.time`
- **Business Events**: Track successfully processed domain events via `/actuator/metrics/business.events.processed`
- **Rate Limiting**: Monitor rejected requests via `/actuator/metrics/rate.limit.rejected`

These metrics can be scraped by Prometheus and visualized in Grafana to ensure the system operates at scale.

## Tech Stack

- **Java 17**
- **Spring Boot 3.x**
  - Spring Web
  - Spring Security (Basic Auth)
  - Spring Data JDBC
  - Spring Kafka
- **PostgreSQL 15** for relational data
- **Flyway** for database migrations
- **Redis** for rate limiting
- **Apache Kafka** for event streaming

## Getting Started

### Prerequisites

- Docker and Docker Compose
- Java 17 (if running locally without Docker)
- Maven (if building locally)

### Running with Docker Compose

1. Clone the repository
2. Run `docker-compose up -d` in the root directory.
   This will start:
   - PostgreSQL
   - Redis
   - Kafka & Zookeeper
   - The Spring Boot application (on port 8080)

### Configuration

Create a `.env` file in the root directory if you need to override secrets:

```env
POSTGRES_USER=inventory_user
POSTGRES_PASSWORD=inventory_password
POSTGRES_DB=inventory
REDIS_HOST=redis
REDIS_PORT=6379
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
```

## API Endpoints

All endpoints require Basic Authentication (e.g., `admin` / `admin`).

### Items

- `GET /api/items` - List items (supports cursor-based pagination with `lastId`, `size`, `search`, and `category`)
- `POST /api/items` - Create an item
- `PUT /api/items/{id}` - Update an item
- `DELETE /api/items/{id}` - Delete an item
- `PATCH /api/items/{id}/quantity` - Adjust stock (supports `Idempotency-Key` header)
- `GET /api/items/export` - Export items as CSV
- `GET /api/items/categories` - List distinct active categories

### Reports

- `GET /api/report` - Get inventory report
