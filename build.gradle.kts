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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

// 버전 결정(ADR-1 보충): 컴파일 검증 없이 착수하는 첫 스프린트는 GA 안정 라인(Boot 3.5 / Spring AI 1.1)으로 시작하고,
// Day 5 이후 Boot 4.x / Spring AI 2.0 업그레이드를 별도 브랜치에서 진행한다(README 로드맵 참고).
extra["springAiVersion"] = "1.1.0"

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.projectreactor:reactor-core")

    // Spring AI — 모델. 셋 다 classpath 에 두고 spring.ai.model.chat / spring.ai.model.embedding 프로퍼티(프로파일)로 선택한다
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")      // private: 로컬
    implementation("org.springframework.ai:spring-ai-starter-model-openai")      // public: ChatGPT(OpenAI)
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")   // claude: Anthropic (임베딩은 없음 → Ollama/OpenAI 임베딩과 조합)
    // Spring AI — 벡터/문서
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")
    // MCP 서버 (Day 5)
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("com.knuddels:jtokkit:1.1.0")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.14.2")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property") } }

tasks.withType<Test> {
    // Docker Engine 29+ 는 API < 1.44 요청을 400으로 거절한다 → docker-java(Testcontainers)에 최소 버전을 알려준다
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

springBoot { mainClass.set("dev.gychoi.docmind.DocmindApplicationKt") } // EvalRunnerKt 도 main 을 가지므로 명시

ktlint {
    version.set("1.7.1")
    filter { exclude("**/generated/**") }
}

// ./gradlew eval — 평가셋 실행 (Day 5). 앱을 기동한 뒤 HTTP로 질의하는 독립 러너.
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "RAG 평가셋(eval/golden.yaml) 실행 → build/eval/report.md"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.gychoi.docmind.eval.EvalRunnerKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain)) // 컴파일 toolchain(21)과 동일 JDK로 실행
    args(
        project.findProperty("eval.baseUrl")?.toString() ?: "http://localhost:8080",
        "eval/golden.yaml",
        "build/eval/report.md",
    )
}

// jib: ./gradlew jib --image=ghcr.io/<user>/docmind:<tag>   (Actions 에서 실행, 로컬은 jibDockerBuild)
jib {
    from { image = "eclipse-temurin:21-jre" }
    container {
        ports = listOf("8080")
        jvmFlags = listOf("-XX:MaxRAMPercentage=70", "-Djava.security.egd=file:/dev/./urandom")
        creationTime.set("USE_CURRENT_TIMESTAMP")
        user = "1000:1000"
    }
}
