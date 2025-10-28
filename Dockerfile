# syntax=docker/dockerfile:1.7-labs

# ---------- build stage ----------
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk AS builder
WORKDIR /app

# прогрев gradle (депенденси-кеш)
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY app-bot/build.gradle.kts app-bot/build.gradle.kts
COPY core-domain/build.gradle.kts core-domain/build.gradle.kts
COPY core-data/build.gradle.kts core-data/build.gradle.kts
COPY core-security/build.gradle.kts core-security/build.gradle.kts
COPY core-telemetry/build.gradle.kts core-telemetry/build.gradle.kts

RUN --mount=type=cache,target=/root/.gradle \
    chmod +x ./gradlew && ./gradlew --no-daemon -v

# сборка дистрибутива (без тестов) с кешом gradle
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app-bot:installDist -x test

# ---------- runtime stage ----------
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre AS runner
WORKDIR /opt/app

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && adduser --disabled-password --gecos "" appuser \
 && chown -R appuser /opt/app
USER appuser

# самодостаточный дистрибутив Ktor
COPY --from=builder /app/app-bot/build/install/app-bot /opt/app

# JVM defaults (меняются через env)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+AlwaysActAsServerClassMachine -Dfile.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError"
ENV TZ=UTC

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=3s --retries=3 CMD curl -fsS http://localhost:8080/health || exit 1

# запускаем скрипт installDist
ENTRYPOINT ["/opt/app/bin/app-bot"]