plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.0.0"
    jacoco
}

group = "place.icomb"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Database
    implementation("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")

    // OpenAPI / SpringDoc
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // PDF generation (page export)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    // Markdown. Mistral OCR returns markdown for 128,118 pages and the structure is load-bearing —
    // a table's header row is what makes its values mean anything. Parsed properly rather than by
    // regex: hand-rolled handling existed in three places and disagreed with itself.
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    // MCP (Model Context Protocol) server via Spring AI
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // MCP OAuth — per-user auth for /api/mcp/**, replacing the shared MCP_TOKEN
    implementation("org.springaicommunity:mcp-authorization-server-spring-boot:0.1.14")
    implementation("org.springaicommunity:mcp-server-security-spring-boot:0.1.14")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Spotless — Google Java Format
// ---------------------------------------------------------------------------
spotless {
    java {
        googleJavaFormat("1.36.1")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        targetExclude("build/**")
    }
}

// ---------------------------------------------------------------------------
// JaCoCo — 80 % minimum line coverage
// ---------------------------------------------------------------------------
jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.10".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
    // Gradle's default output truncates causes to one line each, which hides the class
    // name in a ClassNotFoundException — the only detail that actually identifies the
    // problem. Print failures in full so CI logs are diagnosable without a re-run.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showExceptions = true
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

// Diagnostic helper: print the test runtime classpath so jars can be scanned for
// references to Boot 3 classes that Boot 4 relocated. springdoc 2.9.1 shipped such a
// reference and cost three CI builds to find, because the failure only appears at
// bean construction and Gradle truncates the class name out of the trace.
tasks.register("printRuntimeCp") {
    doLast {
        sourceSets["test"].runtimeClasspath
            .filter { it.name.endsWith(".jar") }
            .forEach { println(it.absolutePath) }
    }
}
