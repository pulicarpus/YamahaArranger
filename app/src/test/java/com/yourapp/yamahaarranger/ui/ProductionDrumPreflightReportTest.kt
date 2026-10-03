package com.yourapp.yamahaarranger.ui

import com.yourapp.audio.ProductionDrumPlan
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ProductionDrumPreflightReportTest {
    private fun fixture():Pair<MainViewModel,AudioEngineManager> {
        val vm=mock(MainViewModel::class.java,CALLS_REAL_METHODS);val audio=mock(AudioEngineManager::class.java)
        // Mockito 5 may use inline mocks rather than generated subclasses.
        MainViewModel::class.java.getDeclaredField("audioEngine").apply {isAccessible=true}.set(vm,audio)
        MainViewModel::class.java.getDeclaredField("productionDrumPreflightSummary").apply {isAccessible=true}
            .set(vm,"STAGE3_PREFLIGHT conditionalSafeRows=48 ABSTAIN=1006\n")
        return vm to audio
    }
    @Test fun reopeningInspectorExportsInstalledPlanWithFreshCountersUsingOnlyReadOnlyGetters() {
        val (vm,audio)=fixture()
        doReturn(ProductionDrumPlan(emptyMap(),48,1054,"fixture")).`when`(audio).productionDrumPlan
        doReturn("accepted=11 owners=0\n","accepted=48 owners=0\n").`when`(audio).productionDrumReport()
        val before=vm.productionDrumExport()!!;val after=vm.productionDrumExport()!!
        assertTrue(before.contains("conditionalSafeRows=48 ABSTAIN=1006"));assertTrue(before.contains("accepted=11"))
        assertTrue(after.contains("conditionalSafeRows=48 ABSTAIN=1006"));assertTrue(after.contains("accepted=48"))
        verify(audio,times(2)).productionDrumPlan;verify(audio,times(2)).productionDrumReport();verifyNoMoreInteractions(audio)
    }
    @Test fun inactiveOrReplacedPlanCannotExportStalePreflightAsActiveProduction() {
        val (vm,audio)=fixture();assertNull(vm.productionDrumExport())
        verify(audio).productionDrumPlan;verifyNoMoreInteractions(audio)
    }
}
