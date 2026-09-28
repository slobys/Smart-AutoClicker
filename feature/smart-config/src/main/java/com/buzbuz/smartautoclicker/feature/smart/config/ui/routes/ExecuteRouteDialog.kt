/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.routes

import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.buzbuz.smartautoclicker.core.common.overlays.base.viewModels
import com.buzbuz.smartautoclicker.core.common.overlays.dialog.OverlayDialog
import com.buzbuz.smartautoclicker.core.domain.model.action.ExecuteRoute
import com.buzbuz.smartautoclicker.core.processing.routes.RouteStore
import com.buzbuz.smartautoclicker.core.processing.routes.RoutePositionMode
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.di.ScenarioConfigViewModelsEntryPoint
import com.buzbuz.smartautoclicker.feature.smart.config.domain.EditionRepository
import com.buzbuz.smartautoclicker.feature.smart.config.ui.action.OnActionConfigCompleteListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

class ExecuteRouteViewModel @Inject constructor(private val edition: EditionRepository, val store: RouteStore) : ViewModel() {
    fun action() = edition.editionState.getEditedAction<ExecuteRoute>()
    fun update(value: ExecuteRoute) = edition.updateEditedAction(value)
}

/** Route picker reuses the normal action save/delete contract. Selecting does not run a route. */
class ExecuteRouteDialog(private val listener: OnActionConfigCompleteListener) : OverlayDialog(R.style.ScenarioConfigTheme) {
    private val model: ExecuteRouteViewModel by viewModels(ScenarioConfigViewModelsEntryPoint::class.java, { executeRouteViewModel() })
    private lateinit var content: LinearLayout
    private var committed = false
    override fun onCreateView(): ViewGroup {
        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        return ScrollView(context).apply { addView(content) }
    }
    override fun onDialogCreated(dialog: BottomSheetDialog) = Unit
    override fun onStart() {
        super.onStart()
        val action = model.action() ?: return
        content.removeAllViews()
        content.addView(TextView(context).apply { setText(R.string.route_action_title); textSize = 22f })
        content.addView(TextView(context).apply { setText(R.string.route_action_help); setPadding(0, dp(12), 0, dp(12)) })
        input(R.string.route_name, action.name.orEmpty(), false) { text -> model.action()?.let { model.update(it.copy(name = text.take(60))) } }
        input(R.string.route_action_timeout, (action.timeoutMs / 1000).toString(), true) { text ->
            model.action()?.let { model.update(it.copy(timeoutMs = (text.toLongOrNull()?.takeIf { seconds -> seconds in 5..3600 } ?: 0) * 1000)) }
        }
        val selected = TextView(context).apply { setText(R.string.route_pending); setPadding(0, dp(12), 0, dp(12)) }
        content.addView(selected)
        val routes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(routes)
        button(content, R.string.route_save) {
            lifecycleScope.launch {
                val current = model.action() ?: return@launch
                if (!current.isComplete()) { toast(R.string.route_invalid); return@launch }
                try {
                    val route = model.store.load(current.routeId)
                    if (route == null || !route.recordingComplete || route.calibration == null || route.points.size < 2 ||
                        (route.positionMode == RoutePositionMode.MINIMAP && route.minimap?.tested != true)) { toast(R.string.route_invalid); return@launch }
                    listener.onConfirmClicked(); committed = true; back()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toast(R.string.route_error) }
            }
        }
        button(content, R.string.route_action_delete) { listener.onDeleteClicked(); committed = true; back() }
        button(content, R.string.route_cancel) { back() }
        lifecycleScope.launch {
            try {
                val all = model.store.summaries()
                selected.text = context.getString(R.string.route_selected, all.find { it.id == action.routeId }?.name ?: context.getString(R.string.route_pending))
                val ready = all.filter { it.ready }
                if (ready.isEmpty()) routes.addView(TextView(context).apply { setText(R.string.route_action_empty) })
                ready.forEach { route ->
                    routes.addView(MaterialButton(context).apply {
                        text = route.name; minHeight = dp(48); isAllCaps = false
                        setOnClickListener {
                            model.action()?.let { model.update(it.copy(routeId = route.id)) }
                            selected.text = context.getString(R.string.route_selected, route.name)
                        }
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { toast(R.string.route_error) }
        }
    }
    override fun back() { if (!committed) listener.onDismissClicked(); super.back() }
    private fun input(label: Int, initial: String, numeric: Boolean, changed: (String) -> Unit) {
        val field = TextInputLayout(context).apply { hint = context.getString(label) }
        field.addView(TextInputEditText(context).apply {
            setSingleLine(); if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial); doAfterTextChanged { changed(it.toString()) }
        }); content.addView(field)
    }
    private fun button(parent: LinearLayout, label: Int, click: () -> Unit) {
        parent.addView(MaterialButton(context).apply { setText(label); minHeight = dp(48); setOnClickListener { click() } })
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_LONG).show()
}
