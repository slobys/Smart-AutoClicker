/*
 * Copyright (C) 2023 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.feature.backup.data

import android.content.ContentResolver
import android.graphics.Point
import android.net.Uri
import android.util.Log

import com.buzbuz.smartautoclicker.core.database.entity.CompleteScenario
import com.buzbuz.smartautoclicker.core.dumb.data.database.DumbScenarioWithActions
import com.buzbuz.smartautoclicker.feature.backup.data.dumb.DumbBackupDataSource
import com.buzbuz.smartautoclicker.feature.backup.data.ext.BackupSizeLimitExceededException
import com.buzbuz.smartautoclicker.feature.backup.data.ext.SizeLimitedInputStream
import com.buzbuz.smartautoclicker.feature.backup.data.smart.SmartBackupDataSource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** [BackupEngine] internal implementation. */
internal class BackupEngine(appDataDir: File, private val contentResolver: ContentResolver) {

    private val dumbBackupDataSource: DumbBackupDataSource = DumbBackupDataSource(appDataDir)
    private val smartBackupDataSource: SmartBackupDataSource = SmartBackupDataSource(appDataDir)

    /**
     * Creates a new backup file.
     *
     * @param zipFileUri the uri of the file to write the backup into. Must be retrieved using the DocumentProvider.
     * @param smartScenarios the scenarios to backup.
     * @param screenSize the size of this device screen.
     * @param progress the object notified about the backup progress.
     */
    suspend fun createBackup(
        zipFileUri: Uri,
        smartScenarios: List<CompleteScenario>,
        dumbScenarios: List<DumbScenarioWithActions>,
        screenSize: Point,
        progress: BackupProgress,
    ) {
        Log.d(TAG, "Create backup: $zipFileUri for scenarios: $smartScenarios")
        dumbBackupDataSource.reset()
        smartBackupDataSource.reset()

        var currentProgress = 0
        progress.onProgressChanged(currentProgress, smartScenarios.size)

        // Create the zip file containing the scenarios and their events conditions.
        withContext(Dispatchers.IO) {
            try {
                ZipOutputStream(contentResolver.openOutputStream(zipFileUri, "wt")).use { zipStream ->
                    dumbScenarios.forEach { dumbScenario ->
                        Log.d(TAG, "Backup dumb scenario ${dumbScenario.scenario.id}")

                        dumbBackupDataSource.addScenarioToZipFile(zipStream, dumbScenario, screenSize)

                        currentProgress++
                        progress.onProgressChanged(currentProgress, smartScenarios.size)
                    }

                    smartScenarios.forEach { completeScenario ->
                        Log.d(TAG, "Backup smart scenario ${completeScenario.scenario.id}")

                        smartBackupDataSource.addScenarioToZipFile(zipStream, completeScenario, screenSize)

                        currentProgress++
                        progress.onProgressChanged(currentProgress, smartScenarios.size)
                    }
                }

                progress.onCompleted(dumbScenarios, smartScenarios, 0, false)
            } catch (ioEx: IOException) {
                Log.e(TAG, "Error while creating backup archive.")
                progress.onError()
            } catch (isEx: IllegalStateException) {
                Log.e(TAG, "Error while creating backup archive, target folder can't be written")
                progress.onError()
            } catch (secEx: SecurityException) {
                Log.e(TAG, "Error while creating backup archive, permission is denied")
                progress.onError()
            }
        }
    }

    /**
     * Loads a backup file.
     *
     * @param zipFileUri the uri of the file to load the backup from. Must be retrieved using the DocumentProvider.
     * @param screenSize the size of this device screen.
     * @param progress the object notified about the backup import progress.
     */
    suspend fun loadBackup(zipFileUri: Uri, screenSize: Point, progress: BackupProgress) {
        Log.i(TAG, "Load backup: $zipFileUri")

        dumbBackupDataSource.reset()
        smartBackupDataSource.reset()

        var currentProgress = 0
        var entryCount = 0
        var totalUncompressedBytes = 0L
        progress.onProgressChanged(currentProgress, null)

        withContext(Dispatchers.IO) {
            try {
                ZipInputStream(contentResolver.openInputStream(zipFileUri)).use { zipStream ->
                    generateSequence { zipStream.nextEntry }
                        .forEach { zipEntry ->
                            if (zipEntry.isDirectory) return@forEach

                            entryCount++
                            if (entryCount > MAX_BACKUP_ENTRY_COUNT) {
                                throw BackupSizeLimitExceededException("Backup contains too many entries")
                            }
                            if (zipEntry.size > MAX_BACKUP_ENTRY_UNCOMPRESSED_BYTES) {
                                throw BackupSizeLimitExceededException("Backup entry is too large: ${zipEntry.name}")
                            }

                            val limitedEntryStream = SizeLimitedInputStream(
                                source = zipStream,
                                maxBytes = MAX_BACKUP_ENTRY_UNCOMPRESSED_BYTES,
                                onBytesRead = { count ->
                                    totalUncompressedBytes += count
                                    if (totalUncompressedBytes > MAX_BACKUP_TOTAL_UNCOMPRESSED_BYTES) {
                                        throw BackupSizeLimitExceededException("Backup uncompressed content is too large")
                                    }
                                },
                            )

                            Log.d(TAG, "Extracting file ${zipEntry.name}")
                            when {
                                dumbBackupDataSource.extractFromZip(limitedEntryStream, zipEntry.name) -> {
                                    Log.d(TAG, "Dumb scenario file ${zipEntry.name} extracted.")

                                    currentProgress++
                                    progress.onProgressChanged(currentProgress, null)
                                }

                                smartBackupDataSource.extractFromZip(limitedEntryStream, zipEntry.name) -> {
                                    if (smartBackupDataSource.isScenarioBackupFileZipEntry(zipEntry.name)) {
                                        Log.d(TAG, "Smart scenario file ${zipEntry.name} extracted")

                                        currentProgress++
                                        progress.onProgressChanged(currentProgress, null)
                                    }
                                }

                                else -> Log.w(TAG, "Nothing found to handle zip entry ${zipEntry.name}")
                            }

                            // Always consume the complete entry through the limiter. Some entries can be skipped
                            // because their local file already exists, and unknown future entries must still count
                            // toward the archive limits instead of bypassing zip-bomb protection.
                            limitedEntryStream.consumeRemaining()
                        }
                }

                progress.onVerification?.invoke()
                dumbBackupDataSource.verifyExtractedScenarios(screenSize)
                smartBackupDataSource.verifyExtractedScenarios(screenSize)

                Log.i(TAG, "Backup loading completed: $zipFileUri")
                Log.i(TAG, "Inserting extracted scenarios into database")

                progress.onCompleted(
                    dumbBackupDataSource.validBackups,
                    smartBackupDataSource.validBackups,
                    dumbBackupDataSource.failureCount + smartBackupDataSource.failureCount,
                    smartBackupDataSource.screenCompatWarning,
                )
            } catch (ioEx: IOException) {
                Log.e(TAG, "Error while loading backup archive", ioEx)
                progress.onError()
            } catch (secEx: SecurityException) {
                Log.e(TAG, "Error while loading backup archive, permission is denied", secEx)
                progress.onError()
            } catch (iaEx: IllegalArgumentException) {
                Log.e(TAG, "Error while loading backup archive, file is invalid", iaEx)
                progress.onError()
            } catch (npEx: NullPointerException) {
                Log.e(TAG, "Error while loading backup archive, file path is null", npEx)
                progress.onError()
            }
        }
    }
}

/** Tag for logs. */
private const val TAG = "BackupEngine"
private const val MAX_BACKUP_ENTRY_COUNT = 4096
private const val MAX_BACKUP_ENTRY_UNCOMPRESSED_BYTES = 16L * 1024L * 1024L
private const val MAX_BACKUP_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L

private fun SizeLimitedInputStream.consumeRemaining() {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (read(buffer) >= 0) {
        // Reading through the size-limited stream is the required work here.
    }
}
