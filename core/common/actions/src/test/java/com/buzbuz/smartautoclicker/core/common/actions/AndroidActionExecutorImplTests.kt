/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.common.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build

import com.buzbuz.smartautoclicker.core.common.actions.gesture.GestureExecutor
import com.buzbuz.smartautoclicker.core.common.actions.notification.NotificationRequestExecutor
import com.buzbuz.smartautoclicker.core.common.actions.text.TextExecutor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class AndroidActionExecutorImplTests {

    @Test
    fun dispatchGesture_systemRejectsRequest_retriesOnce() = runTest {
        val service = mock(AccessibilityService::class.java)
        `when`(service.dispatchGesture(any(), any(), any())).thenReturn(false)
        val executor = actionExecutor(service)

        val result = executor.dispatchGesture(gesture())

        verify(service, times(2)).dispatchGesture(any(), any(), any())
        assertEquals(AndroidGestureResult.REJECTED, result)
    }

    @Test
    fun dispatchGesture_systemCancelsAcceptedGesture_doesNotReplayIt() = runTest {
        val service = mock(AccessibilityService::class.java)
        val callbackCaptor = ArgumentCaptor.forClass(GestureResultCallback::class.java)
        `when`(service.dispatchGesture(any(), any(), any())).thenReturn(true)
        val executor = actionExecutor(service)

        val result = async { executor.dispatchGesture(gesture()) }
        runCurrent()
        verify(service).dispatchGesture(any(), callbackCaptor.capture(), any())
        callbackCaptor.value.onCancelled(null)
        assertEquals(AndroidGestureResult.CANCELLED, result.await())

        verify(service, times(1)).dispatchGesture(any(), any(), any())
    }

    @Test
    fun dispatchGesture_withoutService_reportsUnavailable() = runTest {
        val executor = AndroidActionExecutorImpl(
            gestureExecutor = GestureExecutor(),
            notificationRequestExecutor = mock(NotificationRequestExecutor::class.java),
            textExecutor = mock(TextExecutor::class.java),
        )

        assertEquals(AndroidGestureResult.SERVICE_UNAVAILABLE, executor.dispatchGesture(gesture()))
    }

    private fun actionExecutor(service: AccessibilityService): AndroidActionExecutorImpl =
        AndroidActionExecutorImpl(
            gestureExecutor = GestureExecutor(),
            notificationRequestExecutor = mock(NotificationRequestExecutor::class.java),
            textExecutor = mock(TextExecutor::class.java),
        ).apply { init(service) }

    private fun gesture(): GestureDescription = GestureDescription.Builder()
        .addStroke(
            GestureDescription.StrokeDescription(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(1f, 1f)
                },
                0L,
                100L,
            )
        )
        .build()
}
