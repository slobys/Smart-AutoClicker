package com.buzbuz.smartautoclicker.scenarios.history

import android.app.Dialog
import android.os.Bundle
import android.widget.ScrollView
import android.widget.ListView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.buzbuz.smartautoclicker.R
import com.buzbuz.smartautoclicker.core.processing.data.ExecutionHistoryStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class ExecutionHistoryDialog : DialogFragment() {
    @Inject lateinit var history: ExecutionHistoryStore
    private var exportId: String? = null
    private lateinit var sessionList: ListView
    private var detailsDialog: androidx.appcompat.app.AlertDialog? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId
        if (uri != null && id != null) {
            val app = requireContext().applicationContext
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        app.contentResolver.openOutputStream(uri)?.use { history.exportSession(id, it) } != null
                    } catch (_: java.io.IOException) { false }
                      catch (_: IllegalArgumentException) { false }
                      catch (_: SecurityException) { false }
                }
                Toast.makeText(app, if (success) R.string.execution_history_export_ok else R.string.execution_history_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportId = savedInstanceState?.getString("exportId")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportId", exportId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        sessionList = ListView(context)
        val empty = TextView(context).apply {
            setText(R.string.execution_history_empty)
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val content = FrameLayout(context).apply {
            val height = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            addView(sessionList, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height))
            addView(empty)
        }
        sessionList.emptyView = empty
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.execution_history_title).setView(content)
            .setNegativeButton(android.R.string.ok, null).create()
    }

    override fun onDestroyView() {
        detailsDialog?.dismiss()
        detailsDialog = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val sessions = history.listSessions()
            if (!isAdded) return@launch
            val labels = sessions.map {
                "${it.name}\n${DateFormat.getDateTimeInstance().format(Date(it.startedAt))} · ${getString(R.string.execution_history_count, it.count)}"
            }
            sessionList.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
            sessionList.setOnItemClickListener { _, _, position, _ -> showSession(sessions[position].id) }
        }
    }

    private fun showSession(id: String) {
        lifecycleScope.launch {
            val source = history.readSession(id) ?: return@launch
            if (!isAdded) return@launch
            val data = JSONObject(source)
            val actions = data.optJSONArray("actions")
            val description = buildString {
                appendLine(getString(R.string.execution_history_export_warning))
                for (index in (actions?.length() ?: 0) - 1 downTo 0) {
                    val row = actions!!.getJSONObject(index)
                    appendLine("\n${row.optString("result")} · ${row.optLong("durationMs")} ms · ${row.optString("event")} → ${row.optString("action")}")
                    if (row.optString("detail").isNotBlank()) appendLine(row.optString("detail"))
                }
            }
            val text = TextView(requireContext()).apply {
                this.text = description
                setTextIsSelectable(true)
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
            detailsDialog?.dismiss()
            detailsDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(data.optString("name"))
                .setView(ScrollView(requireContext()).apply { addView(text) })
                .setPositiveButton(R.string.execution_history_export) { _, _ ->
                    exportId = id
                    export.launch("Klickr-diagnostics-$id.zip")
                }
                .setNegativeButton(android.R.string.ok, null).show()
        }
    }
}
