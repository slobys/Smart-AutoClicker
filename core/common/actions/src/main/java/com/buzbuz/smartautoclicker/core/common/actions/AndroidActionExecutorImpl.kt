/*
 * Copyright (C) 2025 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.core.common.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.AndroidRuntimeException
import android.util.Log

import com.buzbuz.smartautoclicker.core.common.actions.gesture.GestureExecutor
import com.buzbuz.smartautoclicker.core.common.actions.gesture.GestureDispatchResult
import com.buzbuz.smartautoclicker.core.common.actions.model.ActionNotificationRequest
import com.buzbuz.smartautoclicker.core.common.actions.notification.NotificationRequestExecutor
import com.buzbuz.smartautoclicker.core.common.actions.text.TextExecutor

import kotlinx.coroutines.delay
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
internal class AndroidActionExecutorImpl @Inject constructor(
    private val gestureExecutor: GestureExecutor,
    private val notificationRequestExecutor: NotificationRequestExecutor,
    private val textExecutor: TextExecutor,
) : AndroidActionExecutor {

    /** Keep the service in a week reference to avoid potential leak. */
    private var accessibilityServiceRef: WeakReference<AccessibilityService>? = null
    private val accessibilityService: AccessibilityService?
        get() {
            val ref = accessibilityServiceRef ?: let {
                Log.w(TAG, "Can't get accessibility service, init has not been called")
                return null
            }
            return ref.get() ?: let {
                Log.w(TAG, "Can't get accessibility service, it has been destroyed")
                null
            }
        }

    override fun init(service: AccessibilityService) {
        accessibilityServiceRef = WeakReference(service)
        notificationRequestExecutor.init(service)
    }

    override fun resetState() {
        gestureExecutor.clear()
        notificationRequestExecutor.clear()
    }

    override fun clear() {
        resetState()
        accessibilityServiceRef = null
    }

    override suspend fun dispatchGesture(gestureDescription: GestureDescription): AndroidGestureResult {
        val service = accessibilityService ?: return AndroidGestureResult.SERVICE_UNAVAILABLE

        repeat(GESTURE_DISPATCH_MAX_ATTEMPTS) { attempt ->
            when (gestureExecutor.dispatchGestureWithResult(service, gestureDescription)) {
                GestureDispatchResult.COMPLETED -> return AndroidGestureResult.COMPLETED
                GestureDispatchResult.CANCELLED -> {
                    // A cancellation is commonly caused by a competing user/system gesture. Do not replay a
                    // potentially destructive click or swipe after the screen state may have changed.
                    delay(GESTURE_DISPATCH_CANCELLED_BACKOFF_MS)
                    return AndroidGestureResult.CANCELLED
                }
                GestureDispatchResult.ERROR -> Unit
            }

            if (attempt < GESTURE_DISPATCH_MAX_ATTEMPTS - 1) {
                Log.w(TAG, "System rejected gesture, retrying (${attempt + 1}/$GESTURE_DISPATCH_MAX_ATTEMPTS)")
                delay(GESTURE_DISPATCH_RETRY_DELAY_MS)
            }
        }

        Log.w(TAG, "System did not execute the gesture after retries, backing off to avoid spamming a slow system")
        delay(GESTURE_DISPATCH_FAILURE_BACKOFF_MS)
        return AndroidGestureResult.REJECTED
    }

    override fun performGlobalAction(globalAction: Int): Boolean {
        val service = accessibilityService ?: return false

        try {
            return service.performGlobalAction(globalAction)
        } catch (ex: Exception) {
            Log.w(TAG, "Can't execute global action.", ex)
        }
        return false
    }

    override fun writeTextOnFocusedItem(text: String, validate: Boolean): Boolean {
        val service = accessibilityService ?: return false

        return try {
            textExecutor.writeText(service, text, validate)
        } catch (ex: RuntimeException) {
            Log.w(TAG, "Text input was rejected", ex)
            false
        }
    }

    override fun startActivity(intent: Intent): Boolean {
        val service = accessibilityService ?: return false

        try {
            service.startActivity(intent)
            return true
        } catch (anfe: ActivityNotFoundException) {
            Log.w(TAG, "Can't start activity, it is not found.", anfe)
        } catch (arex: AndroidRuntimeException) {
            Log.w(TAG, "Can't start activity, Intent is invalid: $intent", arex)
        } catch (iaex: IllegalArgumentException) {
            Log.w(TAG, "Can't start activity, Intent contains invalid arguments: $intent", iaex)
        } catch (secEx: SecurityException) {
            Log.w(TAG, "Can't start activity with intent $intent, permission is denied by the system", secEx)
        } catch (npe: NullPointerException) {
            Log.w(TAG, "Can't start activity with intent $intent, intent is invalid", npe)
        }
        return false
    }

    override fun sendBroadcast(intent: Intent): Boolean {
        val service = accessibilityService ?: return false

        try {
            service.sendBroadcast(intent)
            return true
        } catch (iaex: IllegalArgumentException) {
            Log.w(TAG, "Can't send broadcast, Intent is invalid: $intent", iaex)
        } catch (ex: SecurityException) {
            Log.w(TAG, "Broadcast permission denied", ex)
        }
        return false
    }

    override fun postNotification(notificationRequest: ActionNotificationRequest): Boolean {
        accessibilityService ?: return false // Queue acceptance is not notification delivery.
        notificationRequestExecutor.postNotification(notificationRequest)
        return true
    }

    override fun dump(writer: PrintWriter, prefix: CharSequence) {
        gestureExecutor.dump(writer, prefix)
    }
}

private const val TAG = "ServiceActionExecutor"
private const val GESTURE_DISPATCH_MAX_ATTEMPTS = 2
private const val GESTURE_DISPATCH_RETRY_DELAY_MS = 120L
private const val GESTURE_DISPATCH_CANCELLED_BACKOFF_MS = 120L
private const val GESTURE_DISPATCH_FAILURE_BACKOFF_MS = 500L
