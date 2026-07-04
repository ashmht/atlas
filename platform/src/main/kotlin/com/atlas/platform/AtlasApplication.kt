package com.atlas.platform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Atlas core financial platform — a Spring Modulith modular monolith.
 *
 * Each first-level package under com.atlas.platform is an application module with
 * an enforced boundary: modules may only depend on another module's named API
 * package, never its internals. Spring Modulith verifies this at test time
 * (see ModularityTests), so architectural drift fails the build instead of
 * silently accreting.
 */
@Modulithic(systemName = "Atlas")
@EnableScheduling // outbox relay
@SpringBootApplication
class AtlasApplication

fun main(args: Array<String>) {
    runApplication<AtlasApplication>(*args)
}
