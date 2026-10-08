package com.yourapp.yamahaarranger.arranger

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** P0 test-only orchestration. Never linked into playback. */
class StyleMixFidelityF03PrototypeTest {
    @Test fun isolatedImmutableBookkeepingAndBaselineIntegrity() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test_f03_prototype.py").isFile }
        val output = File(root, "build/f03-prototype").apply { mkdirs() }
        val log = File(output, "process.log")
        val process = ProcessBuilder("python3", File(root, "tools/test_f03_prototype.py").path)
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val completed = process.waitFor(60, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("Prototype timeout", completed)
        println(log.readText())
        assertEquals(log.readText(), 0, process.exitValue())
        val proof = File(output, "proof.json").readText()
        println("F03_TEST_ONLY_PROOF $proof")
        assertTrue(proof.contains("\"bookkeeping_tests\": \"PASS\""))
        assertTrue(proof.contains("\"yamaha_pairing_contract\": \"BLOCKED\""))
        assertTrue(proof.contains("\"native_voice_identity\": \"UNKNOWN\""))
    }
}
