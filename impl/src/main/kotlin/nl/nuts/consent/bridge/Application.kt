package nl.nuts.consent.bridge

import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.boot.autoconfigure.SpringBootApplication


@SpringBootApplication
@ComponentScan(basePackages = ["nl.nuts.consent.bridge", "nl.nuts.consent.bridge.api", "nl.nuts.consent.bridge.model"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
