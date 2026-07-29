# Ledger Service

The Ledger Service is the core transactional engine for FinTracker, responsible for maintaining ACID-compliant financial records, budgets, and statements. Built with Java Spring Boot, Hexagonal Architecture, and jOOQ, it securely processes integrations and strictly isolates data across users via PostgreSQL RLS-ready structures.

## Key Features & Impacts
* **Core Financial Engine:** Provides immutable, double-entry style ledger tracking for all user financial activity, guaranteeing 100% mathematical accuracy for account balances.
* **Tenant Isolation Security:** Implements rigorous Row-Level Security (RLS) patterns and a `UserContextFilter` that injects the verified API Gateway identity into every single jOOQ SQL query, making cross-tenant data leaks impossible.
* **Smart Budgeting:** Enables granular budget configurations and automatic matching algorithms to track spending velocity against user-defined limits.
* **Cascade Integrity:** Ensures referential integrity where deleting a source statement cascade-deletes all generated dependent transactions, while gracefully preserving user-entered manual adjustments.

## Tech Stack
* **Frontend:** Angular (via fintracker-ui)
* **Backend:** Java 21, Spring Boot 3.4.4, PostgreSQL 16, jOOQ (SQL-related code), Flyway (manages database schemas and migrations)
* **Cloud:** AWS (API Gateway, EventBridge, S3)
* **DevOps:** Maven, Docker
* **Testing:** Spring Boot Start Test (JUnit, Mockito, AssertJ), Testcontainers

## Architecture

## REST APIs
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

## Quick Start
<details>
<summary>Click to expand setup instructions</summary>

### Prerequisites
* Java 21+
* Maven 3.9+
* Docker & Docker Compose

### Installation & Run
**Option 1 — Fully containerized**                                                                                    
1. cd services/fintracker-ledger                                                                                                                    
2. docker compose up --build       # first time (compiles + starts both containers)                                                                 
3. docker compose up               # subsequent starts (no rebuild)

**Option 2 — Running apps in the local host**         
1. Clone the repository:
    ```bash
    git clone https://github.com/vanKvo/fintracker.git
    cd fintracker/services/fintracker-ledger
    ```
2. Start the database in the local host:
    ```bash
    docker run --name fintracker -e POSTGRES_PASSWORD=<mysecretpassword> -d -p 5432:5432 postgres
    ```
3. Run the Service:
    ```bash
    mvn spring-boot:run
    ```

### Testing
Seed the test data
```bash
psql -U <user> -d <db> -f services/fintracker-ledger/test-data/seed-statements.sql

```


</details>

## License
<details>
<summary>Click to expand Apache License 2.0</summary>

```
Copyright 2026 FinTracker

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

</details>