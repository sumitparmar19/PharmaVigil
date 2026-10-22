# PharmaVigil architecture

What this file is: the system map (services, data flow, storage, messaging) and the record of every
pinned version (BUILD_INSTRUCTIONS §4, Amendment 1 item 4). Later milestones extend each section.

## System map

```
 PHARMACY                                          DRUG-SAFETY PLATFORM
 ┌───────────────────────────┐  REST: ADE report  ┌──────────────────────────────┐   SOAP/WSDL
 │ pharmacy-service          │ ─────────────────▶ │ intake-gateway               │◀── partner / CRO
 │ dispensing (FEFO lots),   │                    │ REST, SOAP (Spring-WS),      │
 │ one-click side-effect     │                    │ E2B(R3) import (XSD-checked) │
 │ report, safety warnings ◀─┼──── signals ───┐   └──────────────┬───────────────┘
 └───────────────────────────┘                │       Kafka: report.received
 ┌───────────────────────────┐                │                  ▼
 │ signal-service            │                │   ┌──────────────────────────────┐
 │ SafetyFormer + baseline   │────────────────┘   │ case-service (JPA, Oracle)   │
 │ scores per drug-event     │                    │ dedup → MedDRA coder → triage│
 └───────────────────────────┘                    │ → reviewer → E2B(R3) export  │
                                                  └──────────────┬───────────────┘
                                                      JMS (Artemis): E2B(R3) out
                                                                 ▼
                                                  ┌──────────────────────────────┐
                                                  │ submission-gateway           │
                                                  │ mock regulator: XSD, ACK/NACK│
                                                  └──────────────────────────────┘
 OFFLINE: FAERS / VAERS → data/ (PySpark, Parquet/Delta) → ml/ (PyTorch, LightGBM, MLflow) → ONNX → services
```

## Pinned versions

Checked against Maven Central, PyPI and npm when M0 was built.

### Java services

| Component | Version | Note |
|---|---|---|
| Java | 21 (OpenJDK 21.0.12) | LTS |
| Maven | 3.9.11 | |
| Spring Boot | 4.1.1 | latest GA; Spring Framework 7.0.9 |
| Spring Web Services | 5.0.2 | managed by Boot |
| Apache WSS4J | 4.0.1 (`wss4j-ws-security-dom`, OpenSAML excluded) | WS-Security UsernameToken + Timestamp (ADR-009) |
| WSDL4J | 1.6.3 | managed by Boot; WSDL generation |
| JAXB Maven plugin | `org.jvnet.jaxb:jaxb-maven-plugin` 4.0.16 | partner-intake contract classes |
| E2B(R3) schemas | EMA's copy of the ICH set, fetched at build time | `scripts/fetch-e2b-xsd.sh`, not committed (docs/E2B_R3.md) |
| Spring for Apache Kafka | 4.1.1 | managed by Boot |
| Kafka client | 4.2.1 | managed by Boot; talks to the 4.4 broker (Kafka clients and brokers are wire-compatible across versions) |
| Kafka broker | `apache/kafka:4.3.2` (KRaft) in tests | Amendment 1 asks for 4.4: kafka-clients 4.4.0 is on Maven Central, but Docker Hub has only 4.4.0 release candidates, so the newest GA image (4.3.2) is used until 4.4.0 is published |
| ActiveMQ Artemis | client 2.53.0 (managed by Boot); broker image `apache/activemq-artemis:2.44.0-alpine` | newest broker image on Docker Hub; same core protocol |
| Hibernate ORM | 7.4.5.Final | managed by Boot |
| Flyway | 12.4.0 | managed by Boot |
| Oracle JDBC (ojdbc11) | 23.26.3.0.0 | managed by Boot |
| Oracle Database Free | `gvenzl/oracle-free:23-slim-faststart` | |
| SQL Server JDBC | 13.4.0.jre11 | managed by Boot |
| SQL Server | `mcr.microsoft.com/mssql/server:2022-latest` | portability profile |
| Jakarta XML Binding | 4.0.5 API, GlassFish runtime 4.0.9 | |
| JUnit Jupiter | 6.0.3 | managed by Boot |
| Mockito | 5.23.0 | managed by Boot |
| AssertJ | 3.27.7 | managed by Boot |
| Testcontainers | 2.0.5 | managed by Boot |
| Micrometer | 1.17.1 | managed by Boot |
| JaCoCo | 0.8.15 | coverage gate |
| ONNX Runtime (Java) | 1.31.0 | models in case-service (ADR-008) |
| Anthropic Java SDK | 3.1.0 (`com.anthropic:anthropic-java`) | narrative drafts; model id only from `CLAUDE_MODEL` |

### Python (data and ml)

| Component | Version |
|---|---|
| Python | ≥ 3.12 (CI: 3.12; build container: 3.13) |
| Other packages | pinned per milestone in `data/pyproject.toml` and `ml/pyproject.toml` |

### Frontend

| Component | Version | Note |
|---|---|---|
| React / React DOM | 19.3.1 | |
| TypeScript | 6.0.3 | newest the toolchain supports: typescript-eslint 8.72 requires TypeScript < 6.1, so 7.x is excluded |
| Vite | 8.3.5 | |
| @vitejs/plugin-react | 6.1.2 | |
| Tailwind CSS | 4.3.3 | via `@tailwindcss/vite` |
| Vitest | 5.0.5 | jsdom 30.1.3 |
| Testing Library (React) | 16.3.3 | |
| TanStack Query | 5.104.5 | |
| React Router | 8.5.0 | |
| ESLint | 10.13.0 | typescript-eslint 8.72.0 |
| Playwright | 1.64.1 | |
| Node.js | 24 in CI (some packages need ≥ 22.22.2) | |
