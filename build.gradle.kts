plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "13.0.0"
    id("com.google.cloud.tools.jib") version "3.4.5" // 컨테이너 이미지: Docker 없이 GHCR 로 직접 push (GitHub Actions)
}

group = "dev.gychoi"
version = "0.1.0"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } } // 가상 스레드 사용 → 21 필수

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis") // Lettuce + Sentinel
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.redisson:redisson-spring-boot-starter:3.50.0") // 대안 비교(strategy=redisson) 전용
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
    implementation("org.springframework.boot:spring-boot-starter-aop") // resilience4j 어노테이션 프록시
    implementation("io.micrometer:micrometer-registry-prometheus")

    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-mysql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.14.2")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") } }

tasks.withType<Test> {
    // Docker Engine 29+ 는 API < 1.44 요청을 400으로 거절 → docker-java(Testcontainers)에 최소 버전을 알려준다
    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.44")
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.44")
    useJUnitPlatform {
        if (project.hasProperty("excludeTags")) excludeTags(project.property("excludeTags").toString())
    }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

ktlint {
    version.set("1.7.1")
    filter { exclude("**/generated/**") }
}

// jib: ./gradlew jib --image=ghcr.io/<user>/flashgate:<tag>   (Actions 에서 실행, 로컬은 jibDockerBuild)
jib {
    from { image = "eclipse-temurin:21-jre" }
    container {
        ports = listOf("8080")
        jvmFlags = listOf("-XX:MaxRAMPercentage=70", "-XX:+UseZGC", "-Djava.security.egd=file:/dev/./urandom")
        creationTime.set("USE_CURRENT_TIMESTAMP")
        user = "1000:1000"
    }
}
