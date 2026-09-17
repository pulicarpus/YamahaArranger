package com.yourapp.yamahaarranger.di

import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.audio.SilentPlaceholderSampleProvider
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNativeAudioBridge(): NativeAudioBridge = NativeAudioBridge()

    @Provides
    @Singleton
    fun provideChordDetector(): ChordDetector = ChordDetector(mode = ChordMode.MultiFinger)

    // Phase 2: swap for a real SoundFont-backed SampleProvider once
    // TinySoundFont/FluidSynth is wired into the native layer.
    @Provides
    @Singleton
    fun provideSampleProvider(): SampleProvider = SilentPlaceholderSampleProvider()
}
