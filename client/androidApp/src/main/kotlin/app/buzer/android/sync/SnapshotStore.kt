package app.buzer.android.sync

import android.content.Context
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.state.SnapshotDiagnostics
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface SnapshotStorage {
    fun save(snapshot: TasksSnapshotResponse)
    fun load(): TasksSnapshotResponse?
}

class SnapshotStore(
    private val directory: File,
    private val fileName: String = "latest_snapshot.json",
    private val logger: (String) -> Unit = {},
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val atomicWriter: (File, String) -> Unit = ::writeAtomically
) : SnapshotStorage {
    constructor(
        context: Context,
        fileName: String = "latest_snapshot.json",
        json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    ) : this(context.filesDir, fileName, AppLog::debug, json)

    private val file: File
        get() = directory.resolve(fileName)

    override fun save(snapshot: TasksSnapshotResponse) {
        atomicWriter(file, json.encodeToString(TasksSnapshotResponse.serializer(), snapshot))
        logger(
            "snapshot_store save path=${file.absolutePath} bytes=${file.length()} ${SnapshotDiagnostics.describe(snapshot)}"
        )
    }

    override fun load(): TasksSnapshotResponse? {
        if (!file.exists()) {
            logger("snapshot_store load miss path=${file.absolutePath}")
            return null
        }
        val rawSnapshot = file.readText()
        val decoded = json.parseToJsonElement(rawSnapshot)
        val snapshot = if (decoded is JsonObject && "snapshotGeneration" !in decoded) {
            val migrated = json.decodeFromJsonElement(
                TasksSnapshotResponse.serializer(),
                JsonObject(decoded + ("snapshotGeneration" to JsonPrimitive(0)))
            )
            runCatching {
                atomicWriter(file, json.encodeToString(TasksSnapshotResponse.serializer(), migrated))
            }.onSuccess {
                logger("snapshot_store legacy generation migration path=${file.absolutePath} generation=0")
            }.onFailure { error ->
                logger(
                    "snapshot_store legacy generation migration failed path=${file.absolutePath} " +
                        "generation=0 message=${error.message ?: error::class.simpleName}"
                )
            }
            migrated
        } else {
            json.decodeFromJsonElement(TasksSnapshotResponse.serializer(), decoded)
        }
        logger(
            "snapshot_store load hit path=${file.absolutePath} bytes=${file.length()} ${SnapshotDiagnostics.describe(snapshot)}"
        )
        return snapshot
    }
}

/** Writes beside the target then renames within the same directory, never truncating the cache in place. */
private fun writeAtomically(target: File, contents: String) {
    val parent = target.parentFile ?: throw IllegalStateException("Snapshot target has no parent directory")
    parent.mkdirs()
    val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
    try {
        temporary.writeText(contents)
        if (!temporary.renameTo(target)) {
            throw IOException("Could not atomically replace snapshot at ${target.absolutePath}")
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
