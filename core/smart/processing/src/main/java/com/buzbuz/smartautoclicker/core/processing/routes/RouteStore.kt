/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.buzbuz.smartautoclicker.core.processing.routes

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Private, versioned and bounded; never evicts a user's route to make space. */
@Singleton
class RouteStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "routes")

    suspend fun list(): List<RecordedRoute> = withContext(Dispatchers.IO) {
        synchronized(this@RouteStore) {
            directory.listFiles().orEmpty().filter { it.extension == "json" && it.nameWithoutExtension.matches(ROUTE_ID) }
                .take(50).mapNotNull { file ->
                    try { decode(AtomicFile(file).openRead().use { stream ->
                        val bytes = stream.readBytesBounded()
                        bytes.toString(Charsets.UTF_8)
                    }) } catch (ex: Exception) { Log.w("RouteStore", "Unreadable route ${file.name}", ex); null }
                }.sortedBy { it.name }
        }
    }

    suspend fun save(route: RecordedRoute) = withContext(Dispatchers.IO) {
        require(route.valid())
        synchronized(this@RouteStore) {
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "${route.id}.json")
            check(file.exists() || directory.listFiles().orEmpty().count { it.extension == "json" } < 50)
            val bytes = encode(route).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (ex: Exception) { atomic.failWrite(output); throw ex }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        require(id.matches(ROUTE_ID))
        synchronized(this@RouteStore) { AtomicFile(File(directory, "$id.json")).delete() }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = read(chunk)
            if (count < 0) break
            require(buffer.size() + count <= MAX_BYTES)
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        internal fun encode(r: RecordedRoute): String = JSONObject().apply {
            put("version", 1); put("id", r.id); put("name", r.name)
            put("width", r.screenWidth); put("height", r.screenHeight)
            put("x", r.xArea.json()); put("y", r.yArea.json()); put("map", r.mapArea.json())
            put("mapPng", r.mapPng); put("anchor", r.anchor.json()); put("control", r.control.name)
            put("tolerance", r.tolerance); put("complete", r.recordingComplete)
            put("points", JSONArray().apply { r.points.forEach { put(it.json()) } })
            r.calibration?.let { c -> put("calibration", JSONArray().put(c.first.json()).put(c.second.json())) }
        }.toString()

        internal fun decode(text: String): RecordedRoute {
            require(text.length <= MAX_BYTES)
            val j = JSONObject(text)
            require(j.getInt("version") == 1)
            val points = j.getJSONArray("points")
            require(points.length() <= MAX_ROUTE_POINTS)
            return RecordedRoute(
                j.getString("id"), j.getString("name"), j.getInt("width"), j.getInt("height"),
                j.getJSONArray("x").area(), j.getJSONArray("y").area(), j.getJSONArray("map").area(),
                j.getString("mapPng"), j.getJSONArray("anchor").point(), RouteControl.valueOf(j.getString("control")),
                j.optJSONArray("calibration")?.let { RouteCalibration(it.getJSONArray(0).sample(), it.getJSONArray(1).sample()) },
                j.getDouble("tolerance"), List(points.length()) { points.getJSONArray(it).point() }, j.getBoolean("complete"),
            ).also { require(it.valid()) }
        }
        private fun RoutePoint.json() = JSONArray().put(x).put(y)
        private fun RouteArea.json() = JSONArray().put(left).put(top).put(right).put(bottom)
        private fun RouteCalibrationSample.json() = JSONArray().put(screenDelta.json()).put(mapDelta.json())
        private fun JSONArray.point() = RoutePoint(getDouble(0), getDouble(1))
        private fun JSONArray.area() = RouteArea(getInt(0), getInt(1), getInt(2), getInt(3))
        private fun JSONArray.sample() = RouteCalibrationSample(getJSONArray(0).point(), getJSONArray(1).point())
    }
}
