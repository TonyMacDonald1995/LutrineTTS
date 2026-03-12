package com.lutrinecreations

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class Storage(private val dataDir: String) {

    private val logger = LoggerFactory.getLogger(Storage::class.java)
    private val mutex = Mutex()
    private val dataFile = File(dataDir, "data.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var data: AppData = load()
        private set

    private fun load(): AppData {
        return try {
            if (dataFile.exists()) {
                json.decodeFromString<AppData>(dataFile.readText()).also {
                    logger.info("Loaded data: {} guild(s), {} user(s)", it.guildSettings.size, it.userPreferences.size)
                }
            } else {
                logger.info("No existing data file found, starting fresh")
                AppData()
            }
        } catch (e: Exception) {
            logger.error("Failed to load data from {}, starting fresh", dataFile.absolutePath, e)
            AppData()
        }
    }

    suspend fun save(transform: (AppData) -> AppData) {
        mutex.withLock {
            data = transform(data)
            try {
                val dir = File(dataDir)
                dir.mkdirs()

                // Atomic write: write to temp file, then rename
                val tempFile = File(dataDir, "data.json.tmp")
                tempFile.writeText(json.encodeToString(data))
                if (!tempFile.renameTo(dataFile)) {
                    // renameTo can fail on some systems; fall back to copy + delete
                    tempFile.copyTo(dataFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                logger.error("Failed to save data", e)
            }
        }
    }
}