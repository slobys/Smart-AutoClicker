package com.buzbuz.smartautoclicker.core.settings.engine.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.settings.engine.SettingsRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureScopePreferencesTests {
    @Test fun defaultAndExplicitChoicesAreConsistentWithToggle() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = SettingsDataSource(context, Dispatchers.IO)
        val repository = SettingsRepositoryImpl(Dispatchers.IO, source,
            ScenarioSortSettingsDataSource(context, Dispatchers.IO))
        assertTrue("a cold start must await preferences instead of returning a placeholder", repository.isEntireScreenCaptureForced())
        assertTrue("fresh settings default to the entire display", source.isEntireScreenCaptureForced().first())
        source.toggleForceEntireScreenCapture()
        assertFalse("explicit single-app choice must override the default", source.isEntireScreenCaptureForced().first())
        assertFalse("authorization must read the persisted single-app choice", repository.isEntireScreenCaptureForced())
        assertFalse("re-reading must not replace an explicit false", source.isEntireScreenCaptureForced().first())
        source.toggleForceEntireScreenCapture()
        assertTrue("entire-display choice can be restored", source.isEntireScreenCaptureForced().first())
    }
}
