/*
 * Nuts consent bridge
 * Copyright (C) 2020 Nuts community
 *
 *  This program is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package nl.nuts.consent.bridge

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

private const val FILE_EXTENSION = "stamp"

/**
 * Controls access to different files used for storing the latest event timestamp of Corda events
 */
@Service
class StateFileStorageControl {

    @Autowired
    lateinit var eventMetaProperties: EventMetaProperties

    val stateFileStorages: MutableMap<String, StateFileStorage> = mutableMapOf()

    /**
     * Writes latest timestamp to file
     * Precaution should be taken since this is a locking call
     */
    fun writeTimestamp(eventType: String, timestamp: Long) = getOrCreateStateFileStorage(eventType).writeTimestamp(timestamp)

    /**
     * Reads latest timestamp from file
     * Precaution should be taken since this is a locking call
     */
    fun readTimestamp(eventType: String): Long = getOrCreateStateFileStorage(eventType).readTimestamp()

    private fun getOrCreateStateFileStorage(eventType: String) : StateFileStorage {
        synchronized(this) {
            var stateFileStorage = stateFileStorages[eventType]
            if (stateFileStorage == null) {
                stateFileStorage = StateFileStorage(eventTypeFileLocation(eventType))
                stateFileStorages[eventType] = stateFileStorage
            }
            return stateFileStorage
        }
    }

    private fun eventTypeFileLocation(eventType: String): File {
        val dir = File(eventMetaProperties.location)
        if (!dir.exists()) {
            dir.mkdirs() // throws SecurityException is access denied
        }

        val file = dir.resolve("$eventType.$FILE_EXTENSION")
        if (!file.exists()) {
            file.createNewFile() // throws IOException if denied/wrong
        }

        return file
    }
}

/**
 * Handles writing and reading a timestamp to/from a given file with locking
 */
class StateFileStorage(val file: File) {

    /**
     * Writes latest timestamp to file if larger then existing timestamp
     */
    fun writeTimestamp(timestamp: Long) {
        withFileLock { stream ->
            val currentTimestamp = readTimestamp(stream)
            if (timestamp > currentTimestamp) {
                stream.seek(0)
                // overwrites everything
                stream.writeBytes("$timestamp")
            }
        }
    }

    /**
     * Reads latest timestamp from file
     */
    fun readTimestamp(): Long {
        var currentTimestamp = 0L

        withFileLock { stream ->
            currentTimestamp = readTimestamp(stream)
        }

        return currentTimestamp
    }

    private fun readTimestamp(stream: RandomAccessFile): Long {
        var currentTimestamp = 0L

        stream.seek(0)
        val b = ByteArray(13)
        try {
            val read = stream.read(b)
            if (read >= 13) {
                currentTimestamp = String(b).trim().toLong()
            }
        } catch (e: EOFException) {
            // use 0L as value
        } catch (e: NumberFormatException) {
            // use 0L as value
        }
        return currentTimestamp
    }

    private fun withFileLock(block: (stream: RandomAccessFile) -> Unit) {
        synchronized(this) {
            RandomAccessFile(file, "rw").use { stream ->
                stream.channel.use { channel ->
                    // IllegalState or IOException can be thrown
                    var lock: FileLock? = null
                    try {
                        lock = channel.lock()
                        block(stream)
                    } finally {
                        lock?.release()
                    }
                }
            }
        }
    }
}
