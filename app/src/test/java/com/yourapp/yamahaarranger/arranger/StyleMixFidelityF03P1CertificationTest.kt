package com.yourapp.yamahaarranger.arranger

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Diagnostic binary only; passing execution never certifies Yamaha pairing. */
class StyleMixFidelityF03P1CertificationTest {
    @Test fun reverseOrderAndSwappedSignatureRealSdkControls() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/test_f03_p1_native.py").isFile }
        val output = File(root, "build/f03-p1-native").apply { mkdirs() }
        val log = File(output, "process.log")
        val process = ProcessBuilder("python3", File(root, "tools/test_f03_p1_native.py").path)
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val completed = process.waitFor(180, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("Native controls timeout", completed)
        println(log.readText())
        assertEquals(log.readText(), 0, process.exitValue())
        val proof = File(output, "proof.json").readText()
        println("F03_P1_NATIVE_PROOF $proof")
        assertTrue(proof.contains("\"probe_execution\": \"PASS\"") ||
            proof.contains("\"probe_execution\": \"BLOCKED\""))
        assertTrue(proof.contains("\"yamaha_pairing\": \"BLOCKED\""))
        assertTrue(proof.contains("\"production_f03_patch\": \"NO_GO\""))
    }
}
