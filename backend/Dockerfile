# ==========================================================================
# Stage 1: Build
# ==========================================================================
FROM gradle:8.12-jdk25 AS build

WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle bootJar --no-daemon -x test -x spotlessCheck

# ==========================================================================
# Stage 2: Runtime
# ==========================================================================
FROM eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd --system archiver && useradd --system --gid archiver archiver

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R archiver:archiver /app
USER archiver

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
