package com.buzbuz.smartautoclicker.application

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.BuildConfig
import com.buzbuz.smartautoclicker.ComponentConfig
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.base.data.AppComponentsManager
import com.buzbuz.smartautoclicker.core.base.data.getNotificationSettingsIntent
import com.buzbuz.smartautoclicker.core.settings.domain.SettingsRepository
import com.buzbuz.smartautoclicker.feature.notifications.ServiceNotificationController
import com.buzbuz.smartautoclicker.feature.notifications.ServiceNotificationListener
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class VisionClickBrandingTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun components() = AppComponentsManager().apply {
        registerOriginalAppId(ComponentConfig.ORIGINAL_APP_ID)
        registerScenarioActivity(ComponentName(context.packageName, ComponentConfig.scenarioActivity.className))
        registerSmartAutoClickerService(ComponentName(context.packageName, ComponentConfig.smartAutoClickerService.className))
    }

    @Test fun newInstallIdIsIndependentOfTheSourceNamespaceAndOldApp() {
        assertEquals("com.slobys.visionclick.debug", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("com.slobys.visionclick", ComponentConfig.ORIGINAL_APP_ID)
        assertEquals(ComponentConfig.ORIGINAL_APP_ID, ComponentConfig.scenarioActivity.packageName)
        assertEquals("com.buzbuz.smartautoclicker.scenarios.ScenarioActivity", ComponentConfig.scenarioActivity.className)
        assertEquals("com.buzbuz.smartautoclicker.SmartAutoClickerService", ComponentConfig.smartAutoClickerService.className)
    }

    @Test fun debugLabelIsUnambiguousInEnglishAndChinese() {
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE)) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(locale)
            })
            assertEquals("VisionClick 测试版", localized.getString(R.string.app_name))
        }
    }

    @Test fun launcherAndAccessibilityComponentsResolveInsideTheInstalledApp() {
        val provider = components()
        val pm = context.packageManager
        assertEquals(context.packageName, pm.getActivityInfo(provider.scenarioActivityComponentName, 0).packageName)
        assertEquals(context.packageName, pm.getServiceInfo(provider.klickrServiceComponentName, 0).packageName)
        assertEquals(context.packageName, pm.getActivityInfo(provider.tutorialActivityComponentName, 0).packageName)
    }

    @Test fun tutorialUsesTheActiveInstallRatherThanTheOriginalKlickrPackage() {
        val provider = components()
        assertEquals(context.packageName, provider.tutorialActivityComponentName.packageName)
        assertEquals("com.buzbuz.smartautoclicker.feature.tutorial.ui.TutorialActivity", provider.tutorialActivityComponentName.className)
    }

    @Test fun notificationSettingsUseTheCurrentInstallIncludingItsDebugSuffix() {
        val intent = getNotificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test @Config(sdk = [32, 33]) fun notificationActionsCannotBeBroadcastToTheOldAppOrAnotherBuild() {
        // Robolectric does not automatically grant the merged manifest's signature permission.
        shadowOf(context as Application).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val settings = mockk<SettingsRepository> {
            every { isLegacyNotificationUiEnabled() } returns true
        }
        val controller = ServiceNotificationController(context, settings, components(), mockk<ServiceNotificationListener>(relaxed = true))
        try {
            val notification = controller.createNotification(context, "Branding smoke test", isRunning = true, isMenuVisible = true)
            assertEquals(3, notification.actions.size)
            notification.actions.forEach {
                val intent = shadowOf(it.actionIntent).savedIntent
                assertEquals(context.packageName, intent.`package`)
                assertTrue(intent.action!!.startsWith("com.slobys.visionclick."))
            }
            assertEquals(context.packageName, shadowOf(notification.contentIntent).savedIntent.component!!.packageName)
        } finally {
            controller.destroyNotification()
        }
    }

    @Test @Config(sdk = [24], qualifiers = "mdpi") fun android7LoadsTheSuppliedBitmapIcon() {
        val icon = context.getDrawable(R.mipmap.ic_visionclick)
        assertTrue(icon is BitmapDrawable)
        assertEquals(48, (icon as BitmapDrawable).bitmap.width)
        assertEquals(48, icon.bitmap.height)
        assertTrue(context.getDrawable(R.mipmap.ic_visionclick_round) is BitmapDrawable)
    }

    @Test fun tightenedArtworkHasTransparentOuterCornersAndAVisibleLogo() {
        val artwork = (context.getDrawable(R.drawable.visionclick_artwork) as BitmapDrawable).bitmap
        assertEquals(1254, artwork.width)
        assertEquals(artwork.width, artwork.height)
        assertEquals(0, Color.alpha(artwork.getPixel(0, 0)))
        assertEquals(0, Color.alpha(artwork.getPixel(artwork.width - 1, artwork.height - 1)))
        assertTrue(Color.alpha(artwork.getPixel(artwork.width / 2, artwork.height / 2)) >= 250)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun renderDensitySpecificLegacyLauncherAssets() {
        val densities = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        densities.forEach { (density, side) ->
            val drawable = context.getDrawable(R.drawable.visionclick_artwork)!!
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, side, side)
            drawable.draw(Canvas(bitmap))
            assertNotEquals(0, bitmap.getPixel(side / 2, side / 2))
            val output = File("build/outputs/branding/mipmap-$density/ic_visionclick_legacy.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test @Config(sdk = [26, 32]) fun android8AndLaterUseTheNewArtworkInsideAdaptiveIcons() {
        val icon = context.getDrawable(R.mipmap.ic_visionclick)
        assertTrue(icon is AdaptiveIconDrawable)
        assertNotNull((icon as AdaptiveIconDrawable).foreground)
        assertNotNull(icon.background)
        assertTrue(context.getDrawable(R.mipmap.ic_visionclick_round) is AdaptiveIconDrawable)
    }

    @Test @Config(sdk = [33]) fun themedIconsHaveAMonochromeVariant() {
        val icon = context.getDrawable(R.mipmap.ic_visionclick) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun renderTheActualAdaptiveLauncherIconForVisualReview() {
        val icon = context.getDrawable(R.mipmap.ic_visionclick)!!
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(Canvas(bitmap))
        assertNotEquals(0, bitmap.getPixel(128, 128))
        val output = File("build/outputs/branding/visionclick-adaptive-icon.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        // Circle launchers use the same layer geometry, with a stricter mask.
        bitmap.eraseColor(Color.TRANSPARENT)
        val circleCanvas = Canvas(bitmap)
        circleCanvas.clipPath(Path().apply { addCircle(128f, 128f, 128f, Path.Direction.CW) })
        icon.draw(circleCanvas)
        assertNotEquals(0, bitmap.getPixel(128, 128))
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        File("build/outputs/branding/visionclick-circle-icon.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test @Config(sdk = [32, 33]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun adaptiveIconDoesNotExposeAnExtraDarkBandAroundTheArtwork() {
        val side = 256
        val icon = context.getDrawable(R.mipmap.ic_visionclick)!!
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, side, side)
        icon.draw(Canvas(bitmap))
        val output = File("build/outputs/branding/visionclick-edge-check.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        try {
            for (edge in listOf("left", "top", "right", "bottom")) {
                val darkBand = (0 until side / 2).firstOrNull { distance ->
                    val color = when (edge) {
                        "left" -> bitmap.getPixel(distance, side / 2)
                        "top" -> bitmap.getPixel(side / 2, distance)
                        "right" -> bitmap.getPixel(side - 1 - distance, side / 2)
                        else -> bitmap.getPixel(side / 2, side - 1 - distance)
                    }
                    Color.alpha(color) >= 250 && Color.blue(color) >= 80 && Color.green(color) >= 40
                } ?: side / 2
                assertTrue("Extra dark band on $edge edge: $darkBand px", darkBand <= 3)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
