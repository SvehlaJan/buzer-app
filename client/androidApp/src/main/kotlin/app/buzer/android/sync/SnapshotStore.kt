package app.buzer.android.sync

import android.content.Context
import app.buzer.shared.model.TasksSnapshotResponse
import java.io.File
import kotlinx.serialization.json.Json

interface SnapshotStorage {
    fun save(snapshot: TasksSnapshotResponse)
    fun load(): TasksSnapshotResponse?
}

class SnapshotStore(
    private val directory: File,
    private val fileName: String = "latest_snapshot.json",
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
    ) : this(context.filesDir, fileName, json)

    private val file: File
        get() = directory.resolve(fileName)

    override fun save(snapshot: TasksSnapshotResponse) {
        directory.mkdirs()
        file.writeText(json.encodeToString(TasksSnapshotResponse.serializer(), snapshot))
    }

    override fun load(): TasksSnapshotResponse? {
        if (!file.exists()) return null
        return json.decodeFromString(TasksSnapshotResponse.serializer(), file.readText())
    }
}
