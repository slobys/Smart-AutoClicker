/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.database.migrations

import android.content.ContentValues
import android.content.Context
import android.os.Build

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import com.buzbuz.smartautoclicker.core.database.ACTION_TABLE
import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import com.buzbuz.smartautoclicker.core.database.CONDITION_TABLE
import com.buzbuz.smartautoclicker.core.database.EVENT_TABLE
import com.buzbuz.smartautoclicker.core.database.SCENARIO_TABLE

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies data preservation across the auto migrations introduced in database versions 23 to 26. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class Migration22to26Tests {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClickDatabase::class.java,
    )

    private lateinit var dbPath: String

    @Before
    fun setUp() {
        dbPath = ApplicationProvider
            .getApplicationContext<Context>()
            .getDatabasePath("migration-22-to-26-test")
            .path
    }

    @Test
    fun migrate22To26_preservesExistingDataAndAddsSafeDefaults() {
        helper.createDatabase(dbPath, 22).use { database ->
            database.insertScenario(id = 1L, name = "Legacy scenario")
            database.insertEvent(id = 2L, scenarioId = 1L, name = "Legacy event")
            database.insertPauseAction(id = 3L, eventId = 2L, durationMs = 1_234L)
            database.insertNumberCondition(id = 4L, eventId = 2L, numberValue = 72.0)
        }

        helper.runMigrationsAndValidate(dbPath, 26, true).use { database ->
            database.query(
                "SELECT name, is_favorite, group_name FROM $SCENARIO_TABLE WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy scenario", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("", cursor.getString(2))
            }
            database.query(
                "SELECT name, is_breakpoint FROM $EVENT_TABLE WHERE id = 2",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy event", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            database.query(
                "SELECT pauseDuration, pause_wait_mode, pause_timeout_behavior, " +
                    "pause_fallback_event_id FROM $ACTION_TABLE WHERE id = 3",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_234L, cursor.getLong(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertTrue(cursor.isNull(3))
            }
            database.query(
                "SELECT number_counter_value, number_format_type FROM $CONDITION_TABLE WHERE id = 4",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(72.0, cursor.getDouble(0), 0.0)
                assertNull(cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate24To26_preservesScenarioOrganization() {
        helper.createDatabase(dbPath, 24).use { database ->
            database.insertScenario(
                id = 10L,
                name = "Daily tasks",
                isFavorite = true,
                groupName = "Tasks",
            )
            database.insertEvent(id = 11L, scenarioId = 10L, name = "Open rewards")
        }

        helper.runMigrationsAndValidate(dbPath, 26, true).use { database ->
            database.query(
                "SELECT name, is_favorite, group_name FROM $SCENARIO_TABLE WHERE id = 10",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Daily tasks", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("Tasks", cursor.getString(2))
            }
        }
    }

    @Test
    fun migrate25To26_preservesBreakpointsAndInitializesSmartWaitFields() {
        helper.createDatabase(dbPath, 25).use { database ->
            database.insertScenario(id = 20L, name = "Debug scenario", isFavorite = false, groupName = "Debug")
            database.insertEvent(
                id = 21L,
                scenarioId = 20L,
                name = "Breakpoint event",
                isBreakpoint = true,
            )
            database.insertPauseAction(id = 22L, eventId = 21L, durationMs = 500L)
        }

        helper.runMigrationsAndValidate(dbPath, 26, true).use { database ->
            database.query("SELECT is_breakpoint FROM $EVENT_TABLE WHERE id = 21").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.query(
                "SELECT pause_wait_mode, pause_wait_target_event_id, pause_timeout_behavior, " +
                    "pause_max_retries, pause_fallback_event_id, pause_confirmation_frames, " +
                    "pause_change_threshold_percent FROM $ACTION_TABLE WHERE id = 22",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                for (columnIndex in 0 until cursor.columnCount) assertTrue(cursor.isNull(columnIndex))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertScenario(
        id: Long,
        name: String,
        isFavorite: Boolean? = null,
        groupName: String? = null,
    ) {
        insert(SCENARIO_TABLE, 0, ContentValues().apply {
            put("id", id)
            put("name", name)
            put("detection_quality", 1_200)
            put("compute_rate", 0.0)
            put("randomize", 0)
            put("keep_screen_on", 0)
            isFavorite?.let { put("is_favorite", if (it) 1 else 0) }
            groupName?.let { put("group_name", it) }
        })
    }

    private fun SupportSQLiteDatabase.insertEvent(
        id: Long,
        scenarioId: Long,
        name: String,
        isBreakpoint: Boolean? = null,
    ) {
        insert(EVENT_TABLE, 0, ContentValues().apply {
            put("id", id)
            put("scenario_id", scenarioId)
            put("name", name)
            put("operator", 0)
            put("priority", 0)
            put("enabled_on_start", 1)
            put("type", "IMAGE_EVENT")
            isBreakpoint?.let { put("is_breakpoint", if (it) 1 else 0) }
        })
    }

    private fun SupportSQLiteDatabase.insertPauseAction(id: Long, eventId: Long, durationMs: Long) {
        insert(ACTION_TABLE, 0, ContentValues().apply {
            put("id", id)
            put("eventId", eventId)
            put("priority", 0)
            put("name", "Pause")
            put("type", "PAUSE")
            put("pauseDuration", durationMs)
        })
    }

    private fun SupportSQLiteDatabase.insertNumberCondition(id: Long, eventId: Long, numberValue: Double) {
        insert(CONDITION_TABLE, 0, ContentValues().apply {
            put("id", id)
            put("eventId", eventId)
            put("name", "Number")
            put("type", "NUMBER")
            put("priority", 0)
            put("shouldBeDetected", 1)
            put("number_counter_comparison_operation", "EQUALS")
            put("number_counter_operation_value_type", "NUMBER")
            put("number_counter_value", numberValue)
        })
    }
}
