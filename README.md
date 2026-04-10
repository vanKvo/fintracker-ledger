# Ledger Service

The Ledger Service is the core transactional engine for FinTracker, responsible for maintaining ACID-compliant financial records, budgets, and statements. Built with Spring Boot 3.4.4, Hexagonal Architecture, and jOOQ, it securely processes integrations and strictly isolates data across users via PostgreSQL RLS-ready structures.

## Architecture

This service strictly adheres to a Hexagonal (Ports & Adapters) pattern, decoupling the core domain from REST endpoints and PostgreSQL interactions.

```text
src/main/java/com/fintracker/ledger
├── api/             # Primary / Driving Adapters (REST Controllers)
├── config/          # Spring Boot Wiring & Security Filters
├── domain/          # Core Business Logic & Interfaces (Ports)
│   ├── model/       # Domain Entities & Value Objects
│   ├── ports/       # Inbound / Outbound Ports (Interfaces)
│   └── service/     # Business Use Cases
└── infrastructure/  # Secondary / Driven Adapters
    ├── jooq/        # jOOQ Code Generation (Target)
    └── persistence/ # jOOQ Repositories implementations
```

## Modules & Interfaces

| Module | Method | Path | Description |
|---|---|---|---|
| Dashboard | GET | `/api/v1/ledger/dashboard/aggregations` | Fetch financial summary for UI components |
| Dashboard | GET | `/api/v1/ledger/bills` | Fetch upcoming bills and their statuses |
| Dashboard | POST | `/api/v1/ledger/bills/{id}/pay` | Mark an upcoming bill as paid |
| Transactions | GET | `/api/v1/ledger/transactions` | Query, filter, and paginate transactions |
| Transactions | PUT | `/api/v1/ledger/transactions/{id}/approve` | Mark pending transaction as POSTED |
| Transactions | POST | `/api/v1/ledger/transactions/{id}/split` | Split a transaction across categories |
| Transactions | POST | `/api/v1/ledger/transactions/bulk` | Bulk Operations (Approve/Exclude) |
| Transactions | PUT | `/api/v1/ledger/transactions/{id}/exclude` | Toggle exclusion from budgeting |
| Transactions | DELETE | `/api/v1/ledger/transactions/{id}` | Delete a manually entered transaction |
| Statements | GET | `/api/v1/ledger/statements` | Fetch statements history |
| Statements | DELETE | `/api/v1/ledger/statements/{id}` | Delete statement & cascade transactions |
| Budgets | GET | `/api/v1/ledger/budgets` | Fetch budget configurations |
| Budgets | POST | `/api/v1/ledger/budgets` | Upsert budgets & budget lines |

## Core Workflows

- **Tenant Isolation**: Incoming requests hit the `UserContextFilter`, resolving identity through `X-Internal-User-Id` headers set by the API Gateway. The context filters all jOOQ queries unconditionally.
- **Statement Cascading**: When an imported statement is deleted, all dependent transactions strictly cascade-delete, leaving manual user-added transactions intact.
- **Dynamic SQL Querying**: Filter queries are efficiently built via jOOQ `dsl.select()....and(noCondition())`, gracefully removing N+1 problem possibilities while avoiding string concatenation vulnerabilities.

## Local Setup

### Prerequisites
- Java 21+
- Maven 3.9+
- Docker & Docker Compose
- PostgreSQL 16+

### Run Locally
Start the PostgreSQL container (from the root or local directory):
```bash
docker compose up -d
```
Ensure you have the required `DB_PASSWORD` and container variables in `.env` or system variables.
Run the service:
```bash
mvn spring-boot:run
```

### Build & Test
Compile the code and run full ArchUnit and Testcontainers test suites:
```bash
mvn clean package
```

## Key Design Decisions

- **jOOQ over Hibernate**: Enables complete control over schema validation via compilation and highly-performant batch queries on high-volume statement imports.
- **Hexagonal Architecture**: Business logic in `domain/` contains zero Spring or Web dependencies, resulting in lightning fast unit testing and a modular upgrade path.
- **Problem Details RFC 9457**: Security Rejections map to standard generic errors through Spring's `ProblemDetail`, preventing internal data leakage to external callers.
- **Type-safe Flyway Automation**: Code generation hooks directly into Flyway schemas during Maven phases matching the actual deployed baseline.
