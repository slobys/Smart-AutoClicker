/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.scenarios.list

import java.io.File

import javax.xml.parsers.DocumentBuilderFactory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ScenarioCardInteractionTests {

    @Test
    fun smartScenarioCard_exposesSeparateGroupAndStartControls() {
        verifyScenarioCard("item_smart_scenario.xml", expectsStartButton = true)
    }

    @Test
    fun dumbScenarioCard_exposesSeparateGroupAndStartControls() {
        verifyScenarioCard("item_dumb_scenario.xml", expectsStartButton = true)
    }

    @Test
    fun emptyScenarioCard_limitsGroupTouchZoneToVisibleContent() {
        verifyScenarioCard("item_empty_scenario.xml", expectsStartButton = false)
    }

    private fun verifyScenarioCard(layoutName: String, expectsStartButton: Boolean) {
        val root = parseLayout(layoutName)
        val group = root.findView("scenario_group")

        assertEquals("wrap_content", group.androidAttribute("layout_width"))
        assertEquals("@dimen/item_height", group.androidAttribute("minHeight"))
        assertEquals("0", group.appAttribute("layout_constraintHorizontal_bias"))

        if (expectsStartButton) {
            val start = root.findView("button_start")
            assertEquals("@id/button_start", group.appAttribute("layout_constraintEnd_toStartOf"))
            assertEquals("@style/AppTheme.Widget.IconButtonFilled", start.getAttribute("style"))
            assertEquals(
                "@string/content_desc_play_pause_scenario",
                start.androidAttribute("contentDescription"),
            )
        }
    }

    private fun parseLayout(layoutName: String): Element {
        val layoutFile = File("src/main/res/layout/$layoutName")
        assertTrue("Missing layout: ${layoutFile.absolutePath}", layoutFile.isFile)

        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(layoutFile)
            .documentElement
    }

    private fun Element.findView(id: String): Element {
        val nodes = getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.androidAttribute("id").substringAfterLast('/') == id) return element
        }

        error("Missing view @$id")
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.appAttribute(name: String): String = getAttributeNS(APP_NAMESPACE, name)
}

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
