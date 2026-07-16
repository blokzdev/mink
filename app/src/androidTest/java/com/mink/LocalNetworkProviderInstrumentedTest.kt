package com.mink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.core.model.FingerprintSignal
import com.mink.core.provider.ProviderContext
import com.mink.signals.LocalNetworkProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [LocalNetworkProvider] against the real [android.net.nsd.NsdManager]
 * on a device/emulator. Hermetic: it grants no runtime permission and sends
 * nothing, so it asserts only that a discovery round trips without throwing and
 * yields a list. Contents are never asserted — an emulator has no multicast, so
 * discovery legitimately finds nothing and the list may hold only the count
 * signal.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkProviderInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun newProvider(): LocalNetworkProvider =
        LocalNetworkProvider(ProviderContext(context))

    @Test
    fun constructionAloneIsInert() {
        // Constructing without collect() must touch no service and crash nothing.
        newProvider()
    }

    @Test
    fun collectReturnsANonNullListWithoutThrowing() {
        val signals: List<FingerprintSignal> = runBlocking { newProvider().collect() }
        // The emulator has no multicast, so the discovered set is empty; only the
        // call's completion and the non-null list are asserted, never the contents.
        assertNotNull("collect should never return null", signals)
    }
}
