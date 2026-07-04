package com.atlas.platform

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Fails the build if any module reaches into another module's internals, or if a
 * cyclic dependency is introduced between modules. This is how the DDD boundaries
 * stay real over time rather than eroding under deadline pressure.
 */
class ModularityTests {
    private val modules = ApplicationModules.of(AtlasApplication::class.java)

    @Test fun `module boundaries are respected`() = modules.verify()

    @Test fun `render documentation`() {
        org.springframework.modulith.docs.Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
    }
}
