package com.github.giacomosaccaggi.celebrimbot.services

import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.mockk.mockk

/**
 * Property-based tests for ValidationService.detectBuildSystem.
 *
 * **Validates: Requirement 8.5**
 */
class ValidationServicePropertyTest : FunSpec({

    val project = mockk<Project>(relaxed = true)
    val service = ValidationService(project)

    /** Generates arbitrary directory paths — existing, non-existing, and edge cases. */
    val pathArb: Arb<String> = arbitrary {
        val choice = Arb.int(0..4).bind()
        when (choice) {
            0 -> "/tmp/nonexistent_${Arb.string(5..15, Codepoint.az()).bind()}"
            1 -> Arb.string(1..50, Codepoint.az()).bind()
            2 -> "/a/b/c/${Arb.string(3..10, Codepoint.az()).bind()}"
            3 -> ""
            else -> System.getProperty("java.io.tmpdir") + "/${Arb.string(5..10, Codepoint.az()).bind()}"
        }
    }

    // -------------------------------------------------------------------------
    // Property 8: detectBuildSystem is deterministic and total
    // **Validates: Requirement 8.5**
    // -------------------------------------------------------------------------
    test("Property 8: detectBuildSystem returns exactly one BuildSystem enum value without throwing for any path") {
        val allBuildSystems = ValidationService.BuildSystem.entries.toSet()

        checkAll(200, pathArb) { path ->
            val result = service.detectBuildSystem(path)
            assert(result in allBuildSystems) {
                "Expected result to be a valid BuildSystem enum value, got $result for path '$path'"
            }
        }
    }

    test("Property 8: detectBuildSystem is deterministic — same input always yields same output") {
        checkAll(200, pathArb) { path ->
            val result1 = service.detectBuildSystem(path)
            val result2 = service.detectBuildSystem(path)
            assert(result1 == result2) {
                "detectBuildSystem is not deterministic: '$path' -> $result1 then $result2"
            }
        }
    }
})
