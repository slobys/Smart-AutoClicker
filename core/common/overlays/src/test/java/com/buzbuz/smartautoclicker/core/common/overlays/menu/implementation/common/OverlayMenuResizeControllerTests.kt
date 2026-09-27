/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common

import android.animation.LayoutTransition
import android.os.Build
import android.os.Looper
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class OverlayMenuResizeControllerTests {

    @Test
    fun collapsedNoOpChangeDoesNotLockControlsOrLeaveOversizedWindow() {
        val sizes = mutableListOf<Size>()
        val (controller, _) = createController(sizes)
        controller.animateLayoutChanges { /* Changes to hidden buttons need no animation. */ }
        assertTrue(controller.isAnimating)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertFalse(controller.isAnimating)
        assertEquals(Size(48, 48), sizes.last())
    }

    @Test
    fun waitsForStartedTransitionsButRecoversFromMissingEndCallback() {
        val sizes = mutableListOf<Size>()
        val (controller, buttons) = createController(sizes)
        val transition = LayoutTransition().also { buttons.layoutTransition = it }
        // Controller listener is bound during construction, so use a controller for this transition.
        controller.release()
        val active = OverlayMenuResizeController(buttons.parent as android.view.ViewGroup, buttons, Size(200, 300), sizes::add)
        active.animateLayoutChanges {
            transition.transitionListeners.toList().forEach {
                it.startTransition(transition, buttons, buttons.getChildAt(0), LayoutTransition.DISAPPEARING)
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
        assertTrue(active.isAnimating)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(active.isAnimating)
        assertEquals(Size(48, 48), sizes.last())
    }

    @Test
    fun releaseCancelsDeferredResizeAndUnlocksController() {
        val sizes = mutableListOf<Size>()
        val (controller, _) = createController(sizes)
        controller.animateLayoutChanges { }
        controller.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(controller.isAnimating)
        assertEquals(listOf(Size(200, 300)), sizes)
    }

    private fun createController(sizes: MutableList<Size>): Pair<OverlayMenuResizeController, LinearLayout> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val background = FrameLayout(context)
        val buttons = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            addView(View(context), LinearLayout.LayoutParams(48, 48))
            layout(0, 0, 48, 48)
        }
        background.addView(buttons)
        return OverlayMenuResizeController(background, buttons, Size(200, 300), sizes::add) to buttons
    }

    @Test
    fun measureMenuSizeIncludesVisiblePanelTallerThanCollapsedButtons() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val background = FrameLayout(context)
        val content = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
        }
        val buttons = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            addView(View(context), LinearLayout.LayoutParams(48, 48))
        }
        val debugPanel = View(context)

        content.addView(buttons, LinearLayout.LayoutParams(48, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(debugPanel, LinearLayout.LayoutParams(240, 116))
        background.addView(content)

        val controller = OverlayMenuResizeController(
            backgroundViewGroup = background,
            resizedContainer = buttons,
            maximumSize = Size(288, 116),
            windowResizer = {},
        )

        assertEquals(Size(288, 116), controller.measureMenuSize())
    }

    @Test
    fun measureMenuSizeIncludesCompactFanBesideCollapsedLauncher() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val background = FrameLayout(context)
        val content = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
        }
        val buttons = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            addView(View(context), LinearLayout.LayoutParams(68, 68))
        }
        val quickControls = View(context)

        content.addView(buttons, LinearLayout.LayoutParams(68, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(quickControls, LinearLayout.LayoutParams(100, 152))
        background.addView(content)

        val controller = OverlayMenuResizeController(
            backgroundViewGroup = background,
            resizedContainer = buttons,
            maximumSize = Size(308, 360),
            windowResizer = {},
        )

        assertEquals(Size(168, 152), controller.measureMenuSize())
    }
}
