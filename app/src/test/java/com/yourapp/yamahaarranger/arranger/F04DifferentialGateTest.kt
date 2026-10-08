package com.yourapp.yamahaarranger.arranger

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** CI automatically discovers this gate without a workflow/Gradle change. */
class F04DifferentialGateTest {
    @Test fun pinnedBaselineVersusPhysicalCandidateOnlyAllowsF04Drops() {
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it,"tools/test_f04_differential.py").isFile }
        val output=File(root,"build/f04-differential").apply { mkdirs() }
        val log=File(output,"gate.log")
        val process=ProcessBuilder("python3",File(root,"tools/test_f04_differential.py").path)
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val done=process.waitFor(360,TimeUnit.SECONDS)
        if(!done) process.destroyForcibly()
        assertTrue("F04 differential timeout",done)
        println(log.readText());assertEquals(log.readText(),0,process.exitValue())
        assertTrue(File(output,"proof.json").readText().contains("\"differential\": \"PASS\""))
    }
}
