# ==========================================================================
# Stage 1: Build
# ==========================================================================
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

# ==========================================================================
# Stage 2: Runtime
# ==========================================================================
FROM eclipse-temurin:25-jre-alpine

# The released version, stamped by CI from the git tag. Defaults make a locally built image
# say so rather than claiming to be a release.
ARG APP_VERSION=dev
ARG APP_COMMIT=unknown
ENV ARCHIVER_VERSION=${APP_VERSION}
ENV ARCHIVER_COMMIT=${APP_COMMIT}

WORKDIR /app

RUN addgroup -S archiver && adduser -S -G archiver archiver

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R archiver:archiver /app
USER archiver

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
