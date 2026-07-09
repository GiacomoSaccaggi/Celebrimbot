package com.github.giacomosaccaggi.celebrimbot.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests LazyModelManager's lifecycle semantics using a fake model file.
 *
 * NOTE: These tests verify the load/unload state machine logic.
 * They do NOT actually load a real GGUF model (which would require
 * a 1+ GB file). The tests that trigger ensureLoaded() with a
 * non-GGUF file will throw — that's expected and tested.
 */
class LazyModelManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `isLoaded returns false initially`() {
        val fakeModel = File(tempDir.toFile(), "test.gguf")
        fakeModel.writeText("fake")
        val mgr = LazyModelManager(fakeModel, unloadAfterSeconds = 1)
        try {
            assertFalse(mgr.isLoaded())
        } finally {
            mgr.shutdown()
        }
    }

    @Test
    fun `infer throws when model file does not exist`() {
        val missing = File(tempDir.toFile(), "nonexistent.gguf")
        val mgr = LazyModelManager(missing, unloadAfterSeconds = 1)
        try {
            assertThrows(IllegalStateException::class.java) {
                mgr.infer("hello")
            }
        } finally {
            mgr.shutdown()
        }
    }

    @Test
    fun `forceUnload on unloaded model does not throw`() {
        val fakeModel = File(tempDir.toFile(), "test.gguf")
        fakeModel.writeText("fake")
        val mgr = LazyModelManager(fakeModel, unloadAfterSeconds = 1)
        try {
            // Should not throw even when nothing is loaded
            mgr.forceUnload()
            assertFalse(mgr.isLoaded())
        } finally {
            mgr.shutdown()
        }
    }

    @Test
    fun `shutdown is idempotent`() {
        val fakeModel = File(tempDir.toFile(), "test.gguf")
        fakeModel.writeText("fake")
        val mgr = LazyModelManager(fakeModel, unloadAfterSeconds = 1)
        mgr.shutdown()
        // Second shutdown should not throw
        mgr.shutdown()
    }

    @Test
    fun `logger receives messages`() {
        val fakeModel = File(tempDir.toFile(), "test.gguf")
        fakeModel.writeText("fake")
        val messages = mutableListOf<String>()
        val mgr = LazyModelManager(fakeModel, unloadAfterSeconds = 1, logger = { messages.add(it) })
        try {
            // Attempting infer will trigger load attempt (which will fail with a fake file,
            // but the logger should receive the "Loading model" message before the error)
            try {
                mgr.infer("test")
            } catch (_: Exception) {
                // Expected: fake file is not a valid GGUF
            }
            assertTrue(messages.any { it.contains("Loading model") })
        } finally {
            mgr.shutdown()
            assertTrue(messages.any { it.contains("Shutdown complete") })
        }
    }
}
