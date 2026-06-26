# ─── Stage 1: Dependency cache ────────────────────────────────────────────────
# Isolated layer so Maven deps are only re-downloaded when pom.xml changes.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -DskipJooq=true -q

# ─── Stage 2: Build ───────────────────────────────────────────────────────────
FROM deps AS builder
COPY src ./src

# The properties-maven-plugin requires a .env file to exist; credentials are unused
# here because jOOQ codegen is skipped (no generated types are referenced in source).
RUN printf 'DB_URL=unused\nDB_USERNAME=unused\nDB_PASSWORD=unused\n' > .env

RUN mvn package -DskipTests -DskipJooq=true -q

# Extract layered JAR so each layer is cached independently in the runtime image.
RUN java -Djarmode=layertools \
         -jar target/ledger-service-1.0.0-SNAPSHOT.jar \
         extract --destination target/extracted

# ─── Stage 3: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S ledger && adduser -S -G ledger ledger
WORKDIR /app

# Copy layered contents in ascending order of change frequency for optimal layer caching.
COPY --from=builder --chown=ledger:ledger /build/target/extracted/dependencies/ ./
COPY --from=builder --chown=ledger:ledger /build/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=ledger:ledger /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=ledger:ledger /build/target/extracted/application/ ./

USER ledger
EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]