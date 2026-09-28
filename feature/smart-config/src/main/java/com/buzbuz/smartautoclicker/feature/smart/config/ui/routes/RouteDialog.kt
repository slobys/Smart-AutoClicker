/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.graphics.PointF
import android.graphics.Typeface
import android.text.InputFilter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private var deleteConfirmation: AlertDialog? = null

    override fun onCreateView(): ViewGroup {
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        return ScrollView(context).apply { addView(content, ViewGroup.LayoutParams(-1, -2)) }
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
        super.onStop()
    }

    private fun newRoute() {
        val size = displayConfigManager.displayConfig.sizePx
        model.newRoute(size.x, size.y, context.getString(R.string.route_new))
    }

    private fun render() {
        val r = model.route ?: return
        content.removeAllViews()
        title(R.string.routes_title)
        row(R.string.route_close to { back() }, R.string.route_new to { newRoute(); render() })
        paragraph(R.string.route_help)
        val field = TextInputLayout(context).apply { hint = context.getString(R.string.route_name) }
        val name = TextInputEditText(context).apply {
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(60)); setText(r.name)
            doAfterTextChanged { model.route = model.route?.copy(name = it.toString().trim()) }
        }
        field.addView(name); content.addView(field)
        paragraph(context.getString(R.string.route_summary, r.points.size,
            context.getString(if (r.recordingComplete) R.string.route_complete_label else R.string.route_draft_label)))
        title(R.string.route_setup)
        row(R.string.route_mode_coordinates to { model.setMode(RoutePositionMode.COORDINATES); render() },
            R.string.route_mode_minimap to { model.setMode(RoutePositionMode.MINIMAP); render() })
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
        button(R.string.route_anchor) { selectPoint(r.anchor) { model.route = model.route?.copy(anchor = it); model.clearCalibration() } }
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
                    }
                }
            }
            durationField.addView(duration); content.addView(durationField)
        }
        paragraph(R.string.route_calibration_help)
        row(R.string.route_target_a to { selectTarget(true) }, R.string.route_target_b to { selectTarget(false) })
        row(R.string.route_calibrate_a to { calibrate(true) }, R.string.route_calibrate_b to { calibrate(false) })
        paragraph(if (r.calibration != null) context.getString(R.string.route_calibrated) else
            context.getString(R.string.route_calibration_pending) + "  A:${if (model.sampleA != null) "✓" else "—"} B:${if (model.sampleB != null) "✓" else "—"}")
        title(R.string.route_actions)
        paragraph(R.string.route_record_help)
        button(if (r.positionMode == RoutePositionMode.MINIMAP) R.string.route_minimap_test else R.string.route_preview) { run(RouteOperation.PREVIEW) }
        row(R.string.route_record to { run(RouteOperation.RECORD) }, R.string.route_replay to { run(RouteOperation.REPLAY) })
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
            routes.forEach { saved -> savedList.addView(makeButton(saved.name + " · " + context.getString(R.string.route_summary,
                saved.pointCount, context.getString(if (saved.recordingComplete) R.string.route_complete_label else R.string.route_draft_label))) {
                    guarded { model.store.load(saved.id)?.let(model::load); render() }
                }) }
        }
    }

    private fun setControl(mode: RouteControl) {
        if (model.route?.control != mode) { model.route = model.route?.copy(control = mode); model.clearCalibration() }
        render()
    }

    private fun selectArea(kind: Int) {
        overlayManager.navigateTo(context, ConditionAreaSelectorMenu(onAreaSelected = { rect ->
            val area = RouteArea(rect.left, rect.top, rect.right, rect.bottom)
            val r = model.route ?: return@ConditionAreaSelectorMenu
            if (kind == 3) {
                if (!area.valid(r.screenWidth, r.screenHeight) || area.right - area.left !in 96..512 || area.bottom - area.top !in 96..512) {
                    toast(R.string.route_minimap_help); return@ConditionAreaSelectorMenu
                }
                // Capture after the selector has gone. The next screen is observe-only.
                model.route = r.copy(minimap = RouteMinimap(area), points = emptyList(), recordingComplete = false)
                model.clearCalibration()
                return@ConditionAreaSelectorMenu
            }
            model.route = when (kind) {
                0 -> r.copy(xArea = area, recordingComplete = false)
                1 -> r.copy(yArea = area, recordingComplete = false)
                else -> r.copy(mapArea = area, mapPng = "", recordingComplete = false)
            }
            model.clearCalibration()
        }), hideCurrent = true)
    }

    private fun capture() {
        val r = model.route ?: return
        if (!r.mapArea.valid(r.screenWidth, r.screenHeight)) { toast(R.string.route_invalid); return }
        overlayManager.navigateTo(context, RouteRunMenu(false) { control, report ->
            val png = model.runtime.captureMap(r.mapArea, r.screenWidth, r.screenHeight, control)
            model.route = model.route?.copy(mapPng = png)
            report(RouteProgress(RouteMessage.DONE))
        }, hideCurrent = true)
    }

    private fun captureMinimap() {
        val r = model.route ?: return
        val area = r.minimap?.area ?: run { toast(R.string.route_invalid); return }
        overlayManager.navigateTo(context, RouteRunMenu(false) { control, report ->
            val config = model.runtime.captureMinimap(area, r.screenWidth, r.screenHeight, control)
            model.route = r.copy(minimap = config, points = emptyList(), recordingComplete = false, calibration = null)
            model.clearCalibration()
            report(RouteProgress(RouteMessage.DONE))
        }, hideCurrent = true)
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
        }
    }
    private fun calibrate(first: Boolean) {
        if (!model.configured()) { toast(R.string.route_invalid); return }
        val r = model.route ?: return
        val target = (if (first) model.targetA else model.targetB) ?: run { toast(R.string.route_invalid); return }
        val offset = target - r.anchor
        if (offset.distance(RoutePoint(0.0, 0.0)) !in 16.0..300.0) { toast(R.string.route_message_bad_calibration); return }
        overlayManager.navigateTo(context, RouteRunMenu(false, r.positionMode == RoutePositionMode.MINIMAP) { control, report ->
            val result = model.runtime.run(r, RouteOperation.CALIBRATE, control, offset, report)
            if (!model.setSample(first, requireNotNull(result.sample))) throw RouteFailure(RouteMessage.BAD_CALIBRATION)
        }, hideCurrent = true)
    }

    private fun run(operation: RouteOperation) {
        if (!model.configured()) { toast(R.string.route_invalid); return }
        var r = model.route ?: return
        if (operation != RouteOperation.PREVIEW && r.positionMode == RoutePositionMode.MINIMAP && r.minimap?.tested != true) {
            toast(R.string.route_minimap_test_help); return
        }
        if (operation == RouteOperation.REPLAY && (r.calibration == null || !r.recordingComplete || r.points.size < 2)) {
            toast(if (r.calibration == null) R.string.route_message_bad_calibration else R.string.route_message_saved_draft); return
        }
        if (operation == RouteOperation.RECORD) {
            r = r.copy(id = UUID.randomUUID().toString(), points = emptyList(), recordingComplete = false)
            model.route = r
        }
        val route = r
        overlayManager.navigateTo(context, RouteRunMenu(operation == RouteOperation.REPLAY,
            route.positionMode == RoutePositionMode.MINIMAP) { control, report ->
            val result = model.runtime.run(route, operation, control, report = report)
            result.route?.let {
                model.route = it
                if (operation != RouteOperation.PREVIEW)
                    report(RouteProgress(if (it.recordingComplete) RouteMessage.DONE else RouteMessage.SAVED_DRAFT,
                        position = it.points.lastOrNull(), count = it.points.size,
                        trace = if (it.positionMode == RoutePositionMode.MINIMAP) it.points.takeLast(120) else emptyList()))
            }
        }, hideCurrent = true)
    }

    private fun save() {
        if (!model.configured()) { toast(R.string.route_invalid); return }
        val route = model.route ?: return
        guarded { model.store.save(route); toast(R.string.route_ok); render() }
    }
    private fun delete() {
        val id = model.route?.id ?: return
        deleteConfirmation?.dismiss()
        deleteConfirmation = MaterialAlertDialogBuilder(context).setMessage(R.string.route_delete_confirm)
            .setNegativeButton(R.string.route_cancel, null).setPositiveButton(R.string.route_delete) { _, _ ->
                guarded { model.store.delete(id); newRoute(); render() }
            }.create().apply {
                window?.setType(com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.OVERLAY_WINDOW_TYPE)
                show()
            }
    }

    private fun guarded(block: suspend () -> Unit) { lifecycleScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { android.util.Log.w("RouteDialog", "Route storage failed", exception); toast(R.string.route_error) }
    } }
    private fun title(id: Int) { content.addView(TextView(context).apply {
        setText(id); textSize = 20f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(8))
    }) }
    private fun paragraph(id: Int) = paragraph(context.getString(id))
    private fun paragraph(text: String) { content.addView(TextView(context).apply { this.text = text; textSize = 14f; setPadding(0, dp(6), 0, dp(8)) }) }
    private fun button(id: Int, click: () -> Unit) { content.addView(makeButton(context.getString(id), click)) }
    private fun makeButton(label: String, click: () -> Unit) = MaterialButton(context, null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; minHeight = dp(48); setOnClickListener { click() }
        }
    private fun row(vararg buttons: Pair<Int, () -> Unit>) { content.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { (id, click) -> addView(makeButton(context.getString(id), click), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) }) }
    }) }
    private fun areaText(area: RouteArea) = if (area.right > area.left) "${area.left},${area.top}–${area.right},${area.bottom}" else context.getString(R.string.route_pending)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun toast(id: Int) { Toast.makeText(context, id, Toast.LENGTH_LONG).show() }
}
