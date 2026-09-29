/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.graphics.PointF
import android.graphics.Typeface
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.PositionSelectorMenu
import com.buzbuz.smartautoclicker.core.processing.routes.*
import com.buzbuz.smartautoclicker.core.ui.views.itembrief.renderers.ClickDescription
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.areaselector.ConditionAreaSelectorMenu
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

class RouteDialog : OverlayDialog(R.style.ScenarioConfigTheme) {
    private val model: RouteViewModel by viewModels(ScenarioConfigViewModelsEntryPoint::class.java, { routeViewModel() })
    private lateinit var content: LinearLayout
    private lateinit var savedList: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var feedbackView: TextView
    private lateinit var busyIndicator: ProgressBar
    private lateinit var readinessView: TextView
    private lateinit var replayButton: MaterialButton
    private lateinit var calibrationView: TextView
    private var loadedRouteButton: MaterialButton? = null
    private var storageBusy = false
    private var deleteConfirmation: AlertDialog? = null
    private var issueDialog: AlertDialog? = null
    private var returnConfirmation: AlertDialog? = null

    override fun onCreateView(): ViewGroup {
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        scroll = ScrollView(context).apply { addView(content, ViewGroup.LayoutParams(-1, -2)) }
        feedbackView = TextView(context).apply {
            textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        busyIndicator = ProgressBar(context).apply { visibility = View.GONE }
        // Keep feedback visible even when the user is at the bottom of this long form.
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, (displayConfigManager.displayConfig.sizePx.y * .88).toInt())
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(busyIndicator, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
                addView(feedbackView, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }
    override fun onDialogCreated(dialog: BottomSheetDialog) = Unit
    override fun onStart() {
        super.onStart()
        if (model.route == null) newRoute()
        render()
    }

    override fun onStop() {
        deleteConfirmation?.dismiss()
        deleteConfirmation = null
        issueDialog?.dismiss()
        issueDialog = null
        returnConfirmation?.dismiss()
        returnConfirmation = null
        super.onStop()
    }

    private fun newRoute() {
        val size = displayConfigManager.displayConfig.sizePx
        model.newRoute(size.x, size.y, context.getString(R.string.route_new))
        feedback(context.getString(R.string.route_created))
    }

    private fun render() {
        val r = model.route ?: return
        val previousScroll = scroll.scrollY
        loadedRouteButton = null
        content.removeAllViews()
        title(R.string.routes_title)
        row(R.string.route_close to { back() }, R.string.route_new to { newRoute(); render() })
        paragraph(R.string.route_help)
        val field = TextInputLayout(context).apply { hint = context.getString(R.string.route_name) }
        val name = TextInputEditText(context).apply {
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(60)); setText(r.name)
            doAfterTextChanged { model.route = model.route?.copy(name = it.toString().trim()); updateReadiness() }
        }
        field.addView(name); content.addView(field)
        paragraph(context.getString(R.string.route_summary, r.points.size,
            context.getString(if (r.recordingComplete) R.string.route_complete_label else R.string.route_draft_label)))
        if (r.positionMode == RoutePositionMode.COORDINATES && r.points.isNotEmpty())
            paragraph(context.getString(R.string.route_endpoints, r.points.first().coordinateText(), r.points.last().coordinateText()))
        title(R.string.route_setup)
        row(R.string.route_mode_coordinates to { setMode(RoutePositionMode.COORDINATES) },
            R.string.route_mode_minimap to { setMode(RoutePositionMode.MINIMAP) })
        paragraph(context.getString(R.string.route_selected, context.getString(if (r.positionMode == RoutePositionMode.MINIMAP)
            R.string.route_mode_minimap else R.string.route_mode_coordinates)))
        if (r.positionMode == RoutePositionMode.COORDINATES) {
            paragraph(R.string.route_setup_help)
            row(R.string.route_x to { selectArea(0) }, R.string.route_y to { selectArea(1) })
            paragraph("X: ${areaText(r.xArea)}    Y: ${areaText(r.yArea)}")
        } else {
            paragraph(R.string.route_minimap_help)
            button(R.string.route_minimap_select) { selectArea(3) }
            button(R.string.route_minimap_capture) { captureMinimap() }
            paragraph(r.minimap?.let { areaText(it.area) } ?: context.getString(R.string.route_pending))
            paragraph(if (r.minimap?.tested == true) R.string.route_message_localization_passed else R.string.route_minimap_test_help)
        }
        row(R.string.route_map to { selectArea(2) }, R.string.route_capture to { capture() })
        paragraph(context.getString(if (r.mapPng.isNotEmpty()) R.string.route_capture_ok else R.string.route_pending))
        title(R.string.route_control)
        row(R.string.route_ground to { setControl(RouteControl.GROUND_TAP) }, R.string.route_joystick to { setControl(RouteControl.JOYSTICK) })
        paragraph(context.getString(R.string.route_selected, context.getString(
            if (r.control == RouteControl.GROUND_TAP) R.string.route_ground else R.string.route_joystick)))
        button(R.string.route_anchor) { selectPoint(r.anchor) {
            model.route = model.route?.copy(anchor = it); model.clearCalibration()
            feedback(context.getString(R.string.route_anchor_updated))
        } }
        paragraph(if (r.anchor.x < 0) context.getString(R.string.route_pending) else "${r.anchor.x.toInt()}, ${r.anchor.y.toInt()}")
        if (r.control == RouteControl.JOYSTICK) {
            paragraph(R.string.route_joystick_help)
            val durationField = TextInputLayout(context).apply { hint = context.getString(R.string.route_joystick_duration) }
            val duration = TextInputEditText(context).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine()
                setText(String.format(java.util.Locale.ROOT, "%d", r.joystickDurationMs))
                doAfterTextChanged {
                    val value = it.toString().toLongOrNull()
                    durationField.error = if (value == null || value !in 100..800) context.getString(R.string.route_joystick_duration) else null
                    val storedValue = value?.takeIf { it in 100..800 } ?: 0
                    if (storedValue != model.route?.joystickDurationMs) {
                        model.route = model.route?.copy(joystickDurationMs = storedValue); model.clearCalibration()
                        updateReadiness()
                        feedback(context.getString(R.string.route_calibration_reset))
                    }
                }
            }
            durationField.addView(duration); content.addView(durationField)
        }
        paragraph(R.string.route_calibration_help)
        row(R.string.route_target_a to { selectTarget(true) }, R.string.route_target_b to { selectTarget(false) })
        row(R.string.route_calibrate_a to { calibrate(true) }, R.string.route_calibrate_b to { calibrate(false) })
        calibrationView = TextView(context).apply { textSize = 14f; setPadding(0, dp(6), 0, dp(8)) }
        content.addView(calibrationView)
        title(R.string.route_actions)
        paragraph(R.string.route_record_help)
        readinessView = TextView(context).apply {
            textSize = 14f; setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(readinessView)
        button(if (r.positionMode == RoutePositionMode.MINIMAP) R.string.route_minimap_test else R.string.route_preview) { run(RouteOperation.PREVIEW) }
        button(R.string.route_record) { run(RouteOperation.RECORD) }
        replayButton = makeButton(context.getString(R.string.route_replay), primary = true) { run(RouteOperation.REPLAY) }
        content.addView(replayButton)
        button(R.string.route_reverse) { confirmReturn() }
        row(R.string.route_save to { save() }, R.string.route_delete to { delete() })
        title(R.string.route_saved)
        savedList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(savedList)
        val listForThisRender = savedList
        guarded {
            val routes = model.store.summaries()
            if (savedList !== listForThisRender) return@guarded
            savedList.removeAllViews()
            if (routes.isEmpty()) savedList.addView(TextView(context).apply { setText(R.string.route_empty) })
            routes.forEach { saved ->
                val selected = saved.id == model.route?.id
                val state = if (saved.ready) R.string.route_ready_label else if (saved.recordingComplete)
                    R.string.route_recorded_not_ready else R.string.route_draft_label
                val label = (if (selected) context.getString(R.string.route_loaded_prefix) else "") + saved.name + " · " +
                    context.getString(R.string.route_summary, saved.pointCount, context.getString(state))
                savedList.addView(makeButton(label, primary = selected) {
                    storage {
                        val loaded = model.store.load(saved.id)
                        if (loaded == null) { feedback(context.getString(R.string.route_load_missing), true); return@storage }
                        model.load(loaded)
                        feedback(context.getString(R.string.route_loaded, loaded.name))
                        render()
                    }
                }.apply {
                    isSelected = selected; isEnabled = !storageBusy
                    if (selected) loadedRouteButton = this
                })
            }
            updateReadiness()
        }
        updateReadiness()
        showFeedback()
        enableContent(!storageBusy)
        scroll.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun setMode(mode: RoutePositionMode) {
        model.setMode(mode)
        feedback(context.getString(R.string.route_selected, context.getString(if (mode == RoutePositionMode.MINIMAP)
            R.string.route_mode_minimap else R.string.route_mode_coordinates)))
        render()
    }

    private fun setControl(mode: RouteControl) {
        if (model.route?.control != mode) { model.route = model.route?.copy(control = mode); model.clearCalibration() }
        feedback(context.getString(R.string.route_selected, context.getString(if (mode == RouteControl.GROUND_TAP)
            R.string.route_ground else R.string.route_joystick)))
        render()
    }

    private fun selectArea(kind: Int) {
        val current = model.route ?: return
        overlayManager.navigateTo(context, ConditionAreaSelectorMenu(initialSelection = current.selectionFor(kind), onAreaSelected = { rect ->
            val area = RouteArea(rect.left, rect.top, rect.right, rect.bottom)
            val r = model.route ?: return@ConditionAreaSelectorMenu
            if (kind == 3) {
                if (!area.valid(r.screenWidth, r.screenHeight) || area.right - area.left !in 96..512 || area.bottom - area.top !in 96..512) {
                    feedback(context.getString(R.string.route_minimap_help), true); return@ConditionAreaSelectorMenu
                }
                // Capture after the selector has gone. The next screen is observe-only.
                model.route = r.copy(minimap = RouteMinimap(area), points = emptyList(), recordingComplete = false)
                model.clearCalibration()
                feedback(context.getString(R.string.route_area_updated))
                return@ConditionAreaSelectorMenu
            }
            model.route = when (kind) {
                0 -> r.copy(xArea = area, recordingComplete = false)
                1 -> r.copy(yArea = area, recordingComplete = false)
                else -> r.copy(mapArea = area, mapPng = "", recordingComplete = false)
            }
            model.clearCalibration()
            feedback(context.getString(R.string.route_area_updated))
        }), hideCurrent = true)
    }

    private fun capture() {
        val r = model.route ?: return
        if (!r.mapArea.valid(r.screenWidth, r.screenHeight)) { blocked(listOf(R.string.route_need_map_area)); return }
        launchOperation(R.string.route_capture, false) { control, report ->
            val png = model.runtime.captureMap(r.mapArea, r.screenWidth, r.screenHeight, control)
            model.route = model.route?.copy(mapPng = png)
            feedback(context.getString(R.string.route_capture_ok))
            report(RouteProgress(RouteMessage.DONE))
        }
    }

    private fun captureMinimap() {
        val r = model.route ?: return
        val area = r.minimap?.area ?: run { blocked(listOf(R.string.route_need_minimap)); return }
        launchOperation(R.string.route_minimap_capture, false) { control, report ->
            val config = model.runtime.captureMinimap(area, r.screenWidth, r.screenHeight, control)
            model.route = r.copy(minimap = config, points = emptyList(), recordingComplete = false, calibration = null)
            model.clearCalibration()
            feedback(context.getString(R.string.route_minimap_captured))
            report(RouteProgress(RouteMessage.DONE))
        }
    }

    private fun selectPoint(point: RoutePoint?, selected: (RoutePoint) -> Unit) {
        overlayManager.navigateTo(context, PositionSelectorMenu(itemBriefDescription = ClickDescription(
            position = point?.takeIf { it.x >= 0 && it.y >= 0 }?.let { PointF(it.x.toFloat(), it.y.toFloat()) }, pressDurationMs = 70),
            onConfirm = { description -> (description as? ClickDescription)?.position?.let { selected(RoutePoint(it.x.toDouble(), it.y.toDouble())) } }), hideCurrent = true)
    }
    private fun selectTarget(first: Boolean) {
        selectPoint(if (first) model.targetA else model.targetB) {
            if (first) { model.targetA = it; model.sampleA = null } else { model.targetB = it; model.sampleB = null }
            model.route = model.route?.copy(calibration = null)
            feedback(context.getString(if (first) R.string.route_target_a_updated else R.string.route_target_b_updated))
        }
    }
    private fun calibrate(first: Boolean) {
        val issues = model.operationIssues(RouteOperation.CALIBRATE)
        if (issues.isNotEmpty()) { blocked(issues); return }
        val r = model.route ?: return
        val target = (if (first) model.targetA else model.targetB) ?: run {
            blocked(listOf(if (first) R.string.route_need_target_a else R.string.route_need_target_b)); return
        }
        val offset = target - r.anchor
        if (offset.distance(RoutePoint(0.0, 0.0)) !in 16.0..300.0) { blocked(listOf(R.string.route_message_bad_calibration)); return }
        launchOperation(if (first) R.string.route_calibrate_a else R.string.route_calibrate_b, false) { control, report ->
            val result = model.runtime.run(r, RouteOperation.CALIBRATE, control, offset, report)
            if (!model.setSample(first, requireNotNull(result.sample))) throw RouteFailure(RouteMessage.BAD_CALIBRATION)
            if (model.saveCalibratedRecording()) {
                feedback(context.getString(R.string.route_calibrated_saved))
            } else feedback(context.getString(if (model.route?.calibration != null) R.string.route_calibrated_save_needed
                else if (first) R.string.route_calibration_a_done else R.string.route_calibration_b_done))
        }
    }

    private fun confirmReturn() {
        val issues = model.operationIssues(RouteOperation.RETURN)
        if (issues.isNotEmpty()) { blocked(issues); return }
        returnConfirmation?.dismiss()
        returnConfirmation = MaterialAlertDialogBuilder(context).setTitle(R.string.route_reverse)
            .setMessage(R.string.route_reverse_confirm)
            .setNegativeButton(R.string.route_cancel, null)
            .setPositiveButton(R.string.route_reverse_start) { _, _ -> run(RouteOperation.RETURN) }
            .create().apply {
                window?.setType(com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.OVERLAY_WINDOW_TYPE)
                show()
            }
    }

    private fun run(operation: RouteOperation) {
        val issues = model.operationIssues(operation)
        if (issues.isNotEmpty()) { blocked(issues); return }
        var r = model.route ?: return
        if (operation == RouteOperation.RECORD) {
            r = r.copy(id = UUID.randomUUID().toString(), points = emptyList(), recordingComplete = false)
            model.route = r
        }
        val route = r
        val label = when (operation) {
            RouteOperation.RECORD -> R.string.route_record
            RouteOperation.REPLAY -> R.string.route_replay
            RouteOperation.RETURN -> R.string.route_reverse
            else -> if (route.positionMode == RoutePositionMode.MINIMAP) R.string.route_minimap_test else R.string.route_preview
        }
        launchOperation(label, operation == RouteOperation.REPLAY || operation == RouteOperation.RETURN) { control, report ->
            val result = model.runtime.run(route, operation, control, report = report)
            result.route?.let {
                model.route = it
                if (operation != RouteOperation.PREVIEW)
                    report(RouteProgress(if (it.recordingComplete) RouteMessage.DONE else RouteMessage.SAVED_DRAFT,
                        position = it.points.lastOrNull(), count = it.points.size,
                        trace = if (it.positionMode == RoutePositionMode.MINIMAP) it.points.takeLast(120) else emptyList()))
            }
            if (operation == RouteOperation.RECORD) feedback(if (model.route?.recordingComplete == true)
                context.getString(R.string.route_recorded_feedback, model.route?.points?.size ?: 0)
                else context.getString(R.string.route_message_saved_draft))
            else if (operation == RouteOperation.REPLAY || operation == RouteOperation.RETURN) feedback(context.getString(
                if (control.stopped) R.string.route_stopped_feedback else if (operation == RouteOperation.RETURN)
                    R.string.route_message_return_complete else R.string.route_message_complete))
            else feedback(context.getString(if (route.positionMode == RoutePositionMode.MINIMAP && model.route?.minimap?.tested != true)
                R.string.route_message_localization_weak else R.string.route_message_done))
        }
    }

    private fun save() {
        val issues = model.configurationIssues()
        if (issues.isNotEmpty()) { blocked(issues); return }
        val route = model.route ?: return
        storage { model.store.save(route); feedback(context.getString(R.string.route_saved_feedback, route.name)); render() }
    }
    private fun delete() {
        val id = model.route?.id ?: return
        deleteConfirmation?.dismiss()
        deleteConfirmation = MaterialAlertDialogBuilder(context).setMessage(R.string.route_delete_confirm)
            .setNegativeButton(R.string.route_cancel, null).setPositiveButton(R.string.route_delete) { _, _ ->
                storage { model.store.delete(id); newRoute(); feedback(context.getString(R.string.route_deleted_feedback)); render() }
            }.create().apply {
                window?.setType(com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.OVERLAY_WINDOW_TYPE)
                show()
            }
    }

    private fun launchOperation(label: Int, pausable: Boolean,
        operation: suspend (RouteRunControl, (RouteProgress) -> Unit) -> Unit,
    ) {
        feedback(context.getString(R.string.route_starting, context.getString(label)))
        overlayManager.navigateTo(context, RouteRunMenu(pausable, model.route?.positionMode == RoutePositionMode.MINIMAP) { control, report ->
            try { operation(control, report) }
            catch (cancelled: CancellationException) {
                feedback(context.getString(R.string.route_stopped_feedback)); throw cancelled
            } catch (failure: RouteFailure) {
                feedback(context.getString(failure.reason.stringId()) +
                    (failure.diagnostics?.let { "\n" + it.statusText(context) } ?: ""), true); throw failure
            } catch (failure: Exception) {
                feedback(context.getString(R.string.route_error), true); throw failure
            }
        }, hideCurrent = true)
    }

    private fun updateReadiness() {
        if (::calibrationView.isInitialized) calibrationView.text = if (model.route?.calibration != null)
            context.getString(R.string.route_calibrated) else context.getString(R.string.route_calibration_pending) +
                "  A:${if (model.sampleA != null) "✓" else "—"} B:${if (model.sampleB != null) "✓" else "—"}"
        if (!::readinessView.isInitialized || !::replayButton.isInitialized) return
        val issues = model.operationIssues(RouteOperation.REPLAY)
        readinessView.text = if (issues.isEmpty()) context.getString(R.string.route_ready_help) + "\n" +
            context.getString(R.string.route_entry_help, model.route?.entryRadius() ?: 5.0) else
            context.getString(R.string.route_not_ready) + "\n" + issues.joinToString("\n") { "• " + context.getString(it) }
        readinessView.setTextColor(MaterialColors.getColor(readinessView, if (issues.isEmpty())
            com.google.android.material.R.attr.colorOnSurface else androidx.appcompat.R.attr.colorError))
        replayButton.setText(if (issues.isEmpty()) R.string.route_replay else R.string.route_check_replay)
        // A loaded route may have unsaved edits that invalidate its stored calibration.
        // Do not show "ready" in the selected card while the form says it is blocked.
        model.route?.let { route ->
            val state = if (issues.isEmpty()) R.string.route_ready_label else if (route.recordingComplete)
                R.string.route_recorded_not_ready else R.string.route_draft_label
            loadedRouteButton?.text = context.getString(R.string.route_loaded_summary, route.name,
                context.getString(R.string.route_summary, route.points.size, context.getString(state)))
        }
    }

    private fun blocked(issues: List<Int>) {
        val message = issues.distinct().joinToString("\n\n") { "• " + context.getString(it) }
        feedback(context.getString(R.string.route_not_ready) + " " + context.getString(issues.first()), true)
        updateReadiness()
        issueDialog?.dismiss()
        issueDialog = MaterialAlertDialogBuilder(context).setTitle(R.string.route_not_ready)
            .setMessage(message).setPositiveButton(android.R.string.ok, null).create().apply {
                window?.setType(com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.OVERLAY_WINDOW_TYPE)
                show()
            }
    }

    private fun feedback(text: String, error: Boolean = false) {
        model.feedback = text; model.feedbackIsError = error
        if (::feedbackView.isInitialized) showFeedback()
    }

    private fun showFeedback() {
        feedbackView.text = model.feedback ?: context.getString(R.string.route_feedback_hint)
        feedbackView.setBackgroundColor(MaterialColors.getColor(feedbackView, if (model.feedbackIsError)
            com.google.android.material.R.attr.colorErrorContainer else com.google.android.material.R.attr.colorSurfaceVariant))
        feedbackView.setTextColor(MaterialColors.getColor(feedbackView, if (model.feedbackIsError)
            com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /** Only one storage mutation/load can be in flight; no double saves or out-of-order selection. */
    private fun storage(block: suspend () -> Unit) {
        if (storageBusy) return
        storageBusy = true
        enableContent(false)
        busyIndicator.visibility = View.VISIBLE
        guarded {
            try { block() } finally {
                storageBusy = false
                enableContent(true)
                busyIndicator.visibility = View.GONE
            }
        }
    }

    private fun enableContent(enabled: Boolean) {
        fun apply(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
        }
        apply(content)
    }

    private fun guarded(block: suspend () -> Unit) { lifecycleScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { android.util.Log.w("RouteDialog", "Route storage failed", exception); feedback(context.getString(R.string.route_error), true) }
    } }
    private fun title(id: Int) { content.addView(TextView(context).apply {
        setText(id); textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(8))
        ViewCompat.setAccessibilityHeading(this, true)
    }) }
    private fun paragraph(id: Int) = paragraph(context.getString(id))
    private fun paragraph(text: String) { content.addView(TextView(context).apply { this.text = text; textSize = 14f; setPadding(0, dp(6), 0, dp(8)) }) }
    private fun button(id: Int, click: () -> Unit) { content.addView(makeButton(context.getString(id), click = click)) }
    private fun makeButton(label: String, primary: Boolean = false, click: () -> Unit) = MaterialButton(context, null,
        if (primary) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; minHeight = dp(48)
            if (primary) {
                backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary))
            }
            setOnClickListener {
                if (!storageBusy) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    feedback(context.getString(R.string.route_processing, text))
                    click()
                }
            }
        }
    private fun row(vararg buttons: Pair<Int, () -> Unit>) { content.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { (id, click) -> addView(makeButton(context.getString(id), click = click), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }) }
    }) }
    private fun areaText(area: RouteArea) = if (area.right > area.left) "${area.left},${area.top}–${area.right},${area.bottom}" else context.getString(R.string.route_pending)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
