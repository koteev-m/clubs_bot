# syntax=docker/dockerfile:1.7-labs

# ---------- build stage ----------
# builder: public.ecr.aws/docker/library/eclipse-temurin:21-jdk
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk@sha256:cd772abe6bc42ddc2f5927756ea33fb26470726438fe0631472cccd4c5ecc304 AS builder
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
# runtime: public.ecr.aws/docker/library/eclipse-temurin:21-jre
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre@sha256:b0f6befb3f2af49704998c4425cb6313c1da505648a8e78cee731531996f735d AS runner
WORKDIR /opt/app

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && addgroup --system --gid 10001 app \
 && adduser --system --uid 10001 --ingroup app --no-create-home app

# самодостаточный дистрибутив Ktor
COPY --from=builder /app/app-bot/build/install/app-bot /opt/app
RUN chmod -R a-w /opt/app \
 && mkdir -p /var/cache/app \
 && chown 10001:10001 /var/cache/app
USER 10001:10001

# JVM defaults (меняются через env)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+AlwaysActAsServerClassMachine -Dfile.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError"
ENV TZ=UTC

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=3s --retries=3 CMD curl -fsS http://localhost:8080/health || exit 1

# запускаем скрипт installDist
ENTRYPOINT ["/opt/app/bin/app-bot"]
