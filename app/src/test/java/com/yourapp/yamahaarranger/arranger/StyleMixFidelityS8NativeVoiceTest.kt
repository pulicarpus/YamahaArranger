package com.yourapp.yamahaarranger.arranger
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
class StyleMixFidelityS8NativeVoiceTest {
 @Test fun isolatedAvailableNativeSdkSignatures() {
  val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"tools/test_s8_native_voice.py").isFile }
  val output=File(root,"build/s8-native").apply { mkdirs() };val log=File(output,"process.log")
  val p=ProcessBuilder("python3",File(root,"tools/test_s8_native_voice.py").path,"--output",output.path).directory(root).redirectErrorStream(true).redirectOutput(log).start()
  val done=p.waitFor(180,TimeUnit.SECONDS);if(!done)p.destroyForcibly();assertTrue("native timeout",done)
  println(log.readText());assertEquals(log.readText(),0,p.exitValue());val proof=File(output,"proof.json").readText();println("S8_NATIVE_PROOF $proof")
  assertFalse(proof.contains("\"FAIL\""));assertTrue(proof.contains("\"real_sdk\": \"PASS\"") || proof.contains("\"real_sdk\": \"BLOCKED\""))
 }
}
