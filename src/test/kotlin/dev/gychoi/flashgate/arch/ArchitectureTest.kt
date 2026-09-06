package dev.gychoi.flashgate.arch

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/** CLAUDE.md 규칙을 코드로 강제한다. */
@AnalyzeClasses(packages = ["dev.gychoi.flashgate"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    val domainHasNoFrameworkDependency: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "com.fasterxml..", "io.lettuce..")

    @ArchTest
    val applicationDependsOnlyOnPorts: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infra..", "..api..")
            .because("유스케이스는 포트(domain)만 본다")

    /** HARD-GATE #2: 재고 증감은 Lua 안에서만. Redis 템플릿은 infra.redis 밖에서 쓰지 않는다. */
    @ArchTest
    val onlyRedisAdapterTouchesRedisTemplate: ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackage("..infra.redis..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..")
}
