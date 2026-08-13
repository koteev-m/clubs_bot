# syntax=docker/dockerfile:1.7-labs

# ---------- build stage ----------
# builder: public.ecr.aws/docker/library/eclipse-temurin:21.0.11_10-jdk-noble
FROM public.ecr.aws/docker/library/eclipse-temurin:21.0.11_10-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15 AS builder
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
    mkdir -p miniapp/dist && \
    ./gradlew --no-daemon :app-bot:installDist -x test

# ---------- runtime stage ----------
# runtime: public.ecr.aws/docker/library/eclipse-temurin:21.0.11_10-jre-noble
FROM public.ecr.aws/docker/library/eclipse-temurin:21.0.11_10-jre-noble@sha256:ca397720325ceefe39ce397f186759fc87d9efafb2dc4ce53315980844c2f4f2 AS runner
WORKDIR /opt/app

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system --gid 10001 app \
 && adduser --system --uid 10001 --ingroup app --no-create-home app

# самодостаточный дистрибутив Ktor
COPY --from=builder /app/app-bot/build/install/app-bot /opt/app
RUN test -x /opt/app/bin/app-bot \
 && test -x /opt/app/bin/app-bot-migrate \
 && test -x /opt/app/bin/app-bot-migrate-java \
 && test ! -e /opt/app/gradlew \
 && chmod -R a-w /opt/app \
 && mkdir -p /var/cache/app \
 && chown 10001:10001 /var/cache/app
USER 10001:10001

# JVM defaults (меняются через env)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+AlwaysActAsServerClassMachine -Dfile.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError"
ENV TZ=UTC

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=3s --retries=3 CMD curl -fsS http://localhost:8080/ready >/dev/null && curl -fsS http://localhost:8080/health >/dev/null || exit 1

# запускаем скрипт installDist
ENTRYPOINT ["/opt/app/bin/app-bot"]
