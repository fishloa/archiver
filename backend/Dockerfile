# ==========================================================================
# Stage 1: Build
# ==========================================================================
FROM amazoncorretto:25 AS build

WORKDIR /app
COPY gradlew gradle/ ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

# ==========================================================================
# Stage 2: Runtime
# ==========================================================================
FROM amazoncorretto:25

WORKDIR /app

RUN groupadd --system archiver && useradd --system --gid archiver archiver

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R archiver:archiver /app
USER archiver

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
