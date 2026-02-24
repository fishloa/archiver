# ==========================================================================
# Stage 1: Build
# ==========================================================================
FROM amazoncorretto:25 AS build

RUN yum install -y findutils tar gzip && yum clean all

WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

# ==========================================================================
# Stage 2: Runtime
# ==========================================================================
FROM amazoncorretto:25

WORKDIR /app

RUN yum install -y shadow-utils && yum clean all && \
    groupadd --system archiver && useradd --system --gid archiver archiver

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R archiver:archiver /app
USER archiver

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
