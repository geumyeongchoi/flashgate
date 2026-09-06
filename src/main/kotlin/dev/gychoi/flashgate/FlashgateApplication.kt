package dev.gychoi.flashgate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class FlashgateApplication

fun main(args: Array<String>) {
    runApplication<FlashgateApplication>(*args)
}
