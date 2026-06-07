package app.buzer.android.sync

import android.content.Context
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.state.SnapshotDiagnostics
import java.io.File
import kotlinx.serialization.json.Json

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
    }
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
        directory.mkdirs()
        file.writeText(json.encodeToString(TasksSnapshotResponse.serializer(), snapshot))
        logger(
            "snapshot_store save path=${file.absolutePath} bytes=${file.length()} ${SnapshotDiagnostics.describe(snapshot)}"
        )
    }

    override fun load(): TasksSnapshotResponse? {
        if (!file.exists()) {
            logger("snapshot_store load miss path=${file.absolutePath}")
            return null
        }
        val snapshot = json.decodeFromString(TasksSnapshotResponse.serializer(), file.readText())
        logger(
            "snapshot_store load hit path=${file.absolutePath} bytes=${file.length()} ${SnapshotDiagnostics.describe(snapshot)}"
        )
        return snapshot
    }
}
