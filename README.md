# Ledger Service

The Ledger Service is the core transactional engine for FinTracker, responsible for maintaining ACID-compliant financial records, budgets, and statements. Built with Java Spring Boot, Hexagonal Architecture, and jOOQ, it securely processes integrations and strictly isolates data across users via PostgreSQL RLS-ready structures.

## Key Features & Impacts
* **Core Financial Engine:** Provides immutable, double-entry style ledger tracking for all user financial activity, guaranteeing 100% mathematical accuracy for account balances.
* **Tenant Isolation Security:** Implements rigorous Row-Level Security (RLS) patterns and a `UserContextFilter` that injects the verified API Gateway identity into every single jOOQ SQL query, making cross-tenant data leaks impossible.
* **Smart Budgeting:** Enables granular budget configurations and automatic matching algorithms to track spending velocity against user-defined limits.
* **Cascade Integrity:** Ensures referential integrity where deleting a source statement cascade-deletes all generated dependent transactions, while gracefully preserving user-entered manual adjustments.

## Architecture

This service strictly adheres to a Hexagonal (Ports & Adapters) pattern, completely decoupling the pure Java core domain from REST framework specifics and PostgreSQL interactions.

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
*(For complete system diagrams, see `/docs/fintracker-architectural-doc.md`)*

## Tech Stack
* **Frontend:** Angular (via fintracker-ui)
* **Backend:** Java 21, Spring Boot 3.4.4, PostgreSQL 16, jOOQ, Flyway
* **Cloud:** AWS (API Gateway, EventBridge, S3)
* **DevOps:** Maven, Docker
* **Testing:** Testcontainers, ArchUnit

## Modules & Interfaces

**REST API Operations**
| Module | Method | Path | Description |
|---|---|---|---|
| Dashboard | `GET` | `/api/v1/ledger/dashboard/aggregations` | Fetch financial summary for UI components. |
| Dashboard | `GET` | `/api/v1/ledger/bills` | Fetch upcoming bills and their statuses. |
| Dashboard | `POST` | `/api/v1/ledger/bills/{id}/pay` | Mark an upcoming bill as paid. |
| Transactions | `GET` | `/api/v1/ledger/transactions` | Query, filter, and paginate transactions. |
| Transactions | `PUT` | `/api/v1/ledger/transactions/{id}/approve` | Mark pending transaction as POSTED. |
| Transactions | `POST` | `/api/v1/ledger/transactions/{id}/split` | Split a transaction across categories. |
| Transactions | `POST` | `/api/v1/ledger/transactions/bulk` | Bulk Operations (Approve/Exclude). |
| Transactions | `PUT` | `/api/v1/ledger/transactions/{id}/exclude` | Toggle exclusion from budgeting. |
| Transactions | `DELETE` | `/api/v1/ledger/transactions/{id}` | Delete a manually entered transaction. |
| Statements | `GET` | `/api/v1/ledger/statements` | Fetch statements history. |
| Statements | `DELETE` | `/api/v1/ledger/statements/{id}` | Delete statement & cascade transactions. |
| Budgets | `GET` | `/api/v1/ledger/budgets` | Fetch budget configurations. |
| Budgets | `POST` | `/api/v1/ledger/budgets` | Upsert budgets & budget lines. |

## Core Workflows

* **Tenant Isolation Routing:** Incoming requests pass through the `UserContextFilter`, securely resolving the `X-Internal-User-Id` header provided by the API Gateway. This context contextually filters all downstream jOOQ queries.
* **Dynamic SQL Querying:** Advanced transactional filtering relies on dynamically constructed jOOQ SQL expressions, heavily optimizing complex multi-join filters while eliminating string-concatenation SQL injection vectors and avoiding N+1 read problems.
* **Event-Driven Cleanup:** Listens for account deletion events (via an EventBridge integration or adapter) to compliantly erase all transactional history associated with a deleted user.

## Quick Start
<details>
<summary>Click to expand setup instructions</summary>

### Prerequisites
* Java 21+
* Maven 3.9+
* Docker & Docker Compose

### Installation & Run
1.  **Clone the repository:**
    ```bash
    git clone https://github.com/vanKvo/fintracker.git
    cd fintracker/services/fintracker-ledger
    ```
2.  **Start the Database:**
    Launch the PostgreSQL 16 container (ensure `DB_PASSWORD` is configured in your environment):
    ```bash
    docker compose up -d
    ```
3.  **Run the Service:**
    ```bash
    mvn spring-boot:run
    ```

### Build & Test
Compile the code, trigger jOOQ code generation against the Flyway schema, and run full ArchUnit and Testcontainers test suites:
```bash
mvn clean package
```

</details>
