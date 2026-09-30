package com.buzbuz.smartautoclicker.core.display.recorder

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class MediaProjectionRequestTests {
    @Test @Config(sdk = [33])
    fun olderAndroidRetainsSystemConsentIntent() {
        val manager = mock(MediaProjectionManager::class.java)
        val expected = Intent("legacy-consent")
        `when`(manager.createScreenCaptureIntent()).thenReturn(expected)
        assertSame(expected, manager.createScreenCaptureIntentCompat(true))
        verify(manager).createScreenCaptureIntent()
    }

    @Test @Config(sdk = [34, 35])
    fun entireScreenUsesConfiguredConsentStartingOnAndroid14() {
        val manager = mock(MediaProjectionManager::class.java)
        val expected = Intent("display-consent")
        `when`(manager.createScreenCaptureIntent(any(MediaProjectionConfig::class.java))).thenReturn(expected)
        assertSame(expected, manager.createScreenCaptureIntentCompat(true))
        verify(manager).createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        verify(manager, never()).createScreenCaptureIntent()
    }

    @Test @Config(sdk = [34, 35])
    fun explicitUserChoiceKeepsTheAppPicker() {
        val manager = mock(MediaProjectionManager::class.java)
        val expected = Intent("user-choice")
        `when`(manager.createScreenCaptureIntent()).thenReturn(expected)
        assertSame(expected, manager.createScreenCaptureIntentCompat(false))
        verify(manager, never()).createScreenCaptureIntent(any(MediaProjectionConfig::class.java))
    }
}
