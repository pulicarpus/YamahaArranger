// Desktop (Skia) renderer for the LIVE hardware UI. It compiles the SAME Compose source files the Android app uses
// (HardwareLedButton, LiveFader, LiveControlPanels, LiveV3Model) and writes PNGs. Not part of the app build.
plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    // E343 experiment page: desktop Compose UI regression test (sizes of the Redmi Pad in landscape)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
    testImplementation("junit:junit:4.13.2")
}
tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    systemProperty("e343.png.dir", layout.buildDirectory.dir("png-e343").get().asFile.path)
    systemProperty("knob.png.dir", layout.buildDirectory.dir("png-knob").get().asFile.path)
    systemProperty("song.png.dir", layout.buildDirectory.dir("png-song").get().asFile.path)
    systemProperty("song.fixtures", rootDir.resolve("../../tests/fixtures/midi").canonicalPath)
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("../../app/src/main/java")
            include(
                "*.kt",
                "com/yourapp/ui/HardwareLedButton.kt", "com/yourapp/ui/LiveV3Model.kt", "com/yourapp/ui/LiveFader.kt", "com/yourapp/ui/LiveControlPanels.kt",
                "com/yourapp/style/StyleModel.kt", "com/yourapp/ui/StyleVoiceEditorScreen.kt",
                "com/yourapp/yamahaarranger/style/StyleVoiceInventory.kt", "com/yourapp/yamahaarranger/style/StyleVoiceEditorOps.kt", "com/yourapp/yamahaarranger/browser/BrowserModel.kt", "com/yourapp/yamahaarranger/browser/VoiceGroups.kt", "com/yourapp/ui/BrowserLcd.kt", "com/yourapp/yamahaarranger/style/StyleVoiceRequests.kt",
                "com/yourapp/yamahaarranger/style/StyleOverrideStore.kt",
                "com/yourapp/yamahaarranger/registration/RegistrationCodec.kt", "com/yourapp/yamahaarranger/registration/RegistrationModel.kt", "com/yourapp/yamahaarranger/audio/MixerConsoleState.kt",
                "com/yourapp/ui/MetronomePanel.kt", "com/yourapp/yamahaarranger/audio/MetronomeModel.kt",
                "com/yourapp/ui/E343ExperimentPanel.kt",
                "com/yourapp/ui/RotaryKnobMath.kt", "com/yourapp/ui/RotaryDataKnob.kt",
                "com/yourapp/ui/SongPanel.kt", "com/yourapp/yamahaarranger/song/Smf.kt", "com/yourapp/yamahaarranger/song/SongTimeline.kt",
                "com/yourapp/yamahaarranger/song/SongPlayer.kt", "com/yourapp/yamahaarranger/song/SongOutputs.kt",
                // used by StyleVoiceEditorScreen.kt on this branch line (E343 audit, Style Editor BACK); pure Kotlin, no Android types
                "com/yourapp/yamahaarranger/style/E343Compat.kt", "com/yourapp/yamahaarranger/style/StyleEditorNavigation.kt",
            )
        }
    }
}
tasks.register<JavaExec>("renderPreview") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("PreviewMainKt")
    systemProperty("java.awt.headless", "true")
    args(layout.buildDirectory.dir("png").get().asFile.path)
}
