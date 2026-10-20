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
                "com/yourapp/yamahaarranger/style/StyleVoiceInventory.kt", "com/yourapp/yamahaarranger/style/StyleVoiceEditorOps.kt", "com/yourapp/yamahaarranger/browser/BrowserModel.kt", "com/yourapp/ui/BrowserLcd.kt", "com/yourapp/yamahaarranger/style/StyleVoiceRequests.kt",
                "com/yourapp/yamahaarranger/style/StyleOverrideStore.kt",
                "com/yourapp/yamahaarranger/registration/RegistrationCodec.kt", "com/yourapp/yamahaarranger/registration/RegistrationModel.kt", "com/yourapp/yamahaarranger/audio/MixerConsoleState.kt",
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
