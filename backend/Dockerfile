# ==========================================================================
# Stage 1: Build
# ==========================================================================
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

# Run unit tests first — if they fail, the build (and deploy) stops
RUN ./gradlew test --no-daemon -x spotlessCheck --tests '*PersonMatchServiceTest'

RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

# ==========================================================================
# Stage 2: Runtime
# ==========================================================================
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S archiver && adduser -S -G archiver archiver

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R archiver:archiver /app
USER archiver

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
