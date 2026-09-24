/* Copyright (C) 2026 Kevin Buzeau */
package com.buzbuz.smartautoclicker.core.processing.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.domain.model.action.Action
import com.buzbuz.smartautoclicker.core.domain.model.event.Event
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionExecutionResult
import com.buzbuz.smartautoclicker.core.processing.domain.model.ActionFailureSnapshot
import com.buzbuz.smartautoclicker.core.processing.domain.model.isFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionFailureRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
    private val displayRecorder: DisplayRecorder,
) {
    private val _lastFailure = MutableStateFlow<ActionFailureSnapshot?>(null)
    val lastFailure: StateFlow<ActionFailureSnapshot?> = _lastFailure

    suspend fun onActionResult(event: Event, action: Action, result: ActionExecutionResult) {
        if (!result.isFailure) return
        val path = persistLatestScreenshot(displayRecorder.takeScreenshot())
        _lastFailure.value = ActionFailureSnapshot(
            eventId = event.id.databaseId,
            eventName = event.name,
            actionId = action.id.databaseId,
            actionName = action.name.orEmpty(),
            result = result,
            timestampMs = System.currentTimeMillis(),
            screenshotPath = path,
        )
    }

    private suspend fun persistLatestScreenshot(bitmap: Bitmap?): String? = withContext(ioDispatcher) {
        if (bitmap == null) return@withContext null
        val directory = File(context.filesDir, "debug").apply { mkdirs() }
        val target = File(directory, "action_failure_latest.png")
        val temporary = File(directory, "action_failure_latest.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) error("Unable to replace old failure screenshot")
            if (!temporary.renameTo(target)) error("Unable to publish failure screenshot")
            target.absolutePath
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist failure screenshot", error)
            temporary.delete()
            null
        }
    }
}

private const val TAG = "ActionFailureRecorder"
