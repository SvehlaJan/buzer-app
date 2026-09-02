package app.buzer.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
enum class CommitmentState {
    planned,
    pre_start_warning,
    start_blocker_active,
    started,
    deadline_blocker_active,
    completed,
    invalid,
    deleted
}

@Serializable
data class TaskSnapshotItem(
    val id: String,
    val todoistTaskId: String,
    val title: String,
    val description: String? = null,
    val projectId: String?,
    val assigneeId: String?,
    val creatorId: String?,
    val dueDatetime: String?,
    val dueDate: String? = null,
    val durationMinutes: Int?,
    val startAt: String?,
    val state: CommitmentState,
    val isAssignedToUser: Boolean,
    val isCreatedByUser: Boolean,
    val isCompleted: Boolean,
    val isDeleted: Boolean,
    val validationErrors: List<String>,
    val startedAt: String?,
    val startDismissCount: Int,
    val deadlineExtensionCount: Int,
    val escalationSentAt: String?,
    val updatedAt: String,
    val occurrenceKey: String = "",
    val taskRevision: String = "",
    val activityLogEntries: List<ActivityLogEntry> = emptyList(),
    val recurrence: TaskRecurrence = TaskRecurrence(),
    val checklistItems: List<ChecklistItem> = emptyList(),
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val hierarchyStatus: HierarchyStatus = HierarchyStatus.valid,
    val hierarchyViolation: String? = null
)

@Serializable
enum class RecurrenceKind { none, supported, custom }

@Serializable
enum class RecurrencePreset { never, daily, weekdays, weekly, monthly, yearly }

@Serializable
enum class HierarchyStatus { valid, unsupported }

@Serializable
data class TaskRecurrence(
    val kind: RecurrenceKind = RecurrenceKind.none,
    val preset: RecurrencePreset? = RecurrencePreset.never,
    val rule: String? = null,
    val language: String? = null,
    val timeZone: String? = null,
    val displayLabel: String = "Never"
)

@Serializable
data class ActivityLogEntry(
    val timestamp: String,
    val timeZone: String,
    val message: String
)

@Serializable
data class ChecklistItem(
    val id: String,
    val title: String,
    val description: String? = null,
    val checked: Boolean,
    val order: Int,
    val childRevision: String
)

@Serializable
data class TasksSnapshotResponse(
    val scheduleVersion: Long,
    val serverTime: String,
    val tasks: List<TaskSnapshotItem>,
    val health: List<HealthStatus>,
    val snapshotGeneration: Long
)

@Serializable
data class ProjectCollaborator(
    val id: String,
    val displayName: String,
    val email: String? = null,
    val isCurrentUser: Boolean
)

@Serializable
data class ProjectItem(
    val id: String,
    val name: String,
    val canAssignTasks: Boolean,
    val shared: Boolean,
    val isInboxProject: Boolean,
    val collaborators: List<ProjectCollaborator> = emptyList(),
    val collaboratorsReady: Boolean = false
)

@Serializable
data class ProjectsResponse(
    val projects: List<ProjectItem>
)

@Serializable(with = CreateTaskRequestSerializer::class)
data class CreateTaskRequest(
    val title: String,
    val description: String? = null,
    val projectId: String,
    val assigneeId: String? = null,
    val dueDatetime: String? = null,
    val durationMinutes: Int? = null,
    val recurrencePreset: RecurrencePreset = RecurrencePreset.never,
    val timeZone: String? = null,
    val checklistDrafts: List<ChecklistDraft> = emptyList()
) {
    init {
        requireNonBlank("title", title)
        requireNonBlank("projectId", projectId)
    }
}

object CreateTaskRequestSerializer : KSerializer<CreateTaskRequest> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CreateTaskRequest") {
        element<String>("title")
        element<String?>("description", isOptional = true)
        element<String>("projectId")
        element<String?>("assigneeId", isOptional = true)
        element<String?>("dueDatetime")
        element<Int?>("durationMinutes")
        element<String>("recurrencePreset", isOptional = true)
        element<String?>("timeZone", isOptional = true)
        element("checklistDrafts", ListSerializer(ChecklistDraft.serializer()).descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: CreateTaskRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("CreateTaskRequestSerializer supports JSON only")
        jsonEncoder.encodeJsonElement(buildJsonObject {
            put("title", value.title)
            value.description?.let { put("description", it) }
            put("projectId", value.projectId)
            value.assigneeId?.let { put("assigneeId", it) }
            put("dueDatetime", value.dueDatetime?.let(::JsonPrimitive) ?: JsonNull)
            put("durationMinutes", value.durationMinutes?.let(::JsonPrimitive) ?: JsonNull)
            if (value.recurrencePreset != RecurrencePreset.never) {
                put("recurrencePreset", value.recurrencePreset.name)
            }
            value.timeZone?.let { put("timeZone", it) }
            if (value.checklistDrafts.isNotEmpty()) {
                put(
                    "checklistDrafts",
                    jsonEncoder.json.encodeToJsonElement(
                        ListSerializer(ChecklistDraft.serializer()),
                        value.checklistDrafts
                    )
                )
            }
        })
    }

    override fun deserialize(decoder: Decoder): CreateTaskRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CreateTaskRequestSerializer supports JSON only")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        return CreateTaskRequest(
            title = requiredString(element, "title"),
            description = element["description"]?.jsonPrimitive?.contentOrNull,
            projectId = requiredString(element, "projectId"),
            assigneeId = element["assigneeId"]?.jsonPrimitive?.contentOrNull,
            dueDatetime = element["dueDatetime"]?.jsonPrimitive?.contentOrNull,
            durationMinutes = element["durationMinutes"]?.let {
                if (it is JsonNull) null else it.jsonPrimitive.int
            },
            recurrencePreset = element["recurrencePreset"]?.jsonPrimitive?.contentOrNull
                ?.let(RecurrencePreset::valueOf) ?: RecurrencePreset.never,
            timeZone = element["timeZone"]?.jsonPrimitive?.contentOrNull,
            checklistDrafts = element["checklistDrafts"]?.let {
                jsonDecoder.json.decodeFromJsonElement(ListSerializer(ChecklistDraft.serializer()), it)
            } ?: emptyList()
        )
    }

    private fun requiredString(element: JsonObject, key: String): String =
        element[key]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Missing $key")
}

@Serializable
data class UpdateTaskRequest(
    val expectedOccurrenceKey: String? = null,
    val rootPatch: RootTaskPatch? = null,
    @EncodeDefault
    val childMutations: List<ChecklistMutation> = emptyList()
) {
    init {
        expectedOccurrenceKey?.let { requireNonBlank("expectedOccurrenceKey", it) }
    }
}

@Serializable
data class ChecklistDraft(
    val clientMutationId: String,
    val title: String,
    val description: String? = null
) {
    init {
        requireNonBlank("clientMutationId", clientMutationId)
        requireNonBlank("title", title)
    }
}

sealed interface NullablePatch<out T> {
    data object Absent : NullablePatch<Nothing>
    data class Value<T>(val value: T?) : NullablePatch<T>
}

@Serializable(with = RootTaskPatchSerializer::class)
data class RootTaskPatch(
    val expectedTaskRevision: String,
    val title: String? = null,
    val description: NullablePatch<String> = NullablePatch.Absent,
    val projectId: String? = null,
    val assigneeId: NullablePatch<String> = NullablePatch.Absent,
    val dueDatetime: NullablePatch<String> = NullablePatch.Absent,
    val durationMinutes: NullablePatch<Int> = NullablePatch.Absent,
    val recurrencePreset: RecurrencePreset? = null,
    val timeZone: String? = null
) {
    init {
        requireNonBlank("expectedTaskRevision", expectedTaskRevision)
    }
}

object RootTaskPatchSerializer : KSerializer<RootTaskPatch> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RootTaskPatch") {
        element<String>("expectedTaskRevision")
        element<String?>("title", isOptional = true)
        element<String?>("description", isOptional = true)
        element<String?>("projectId", isOptional = true)
        element<String?>("assigneeId", isOptional = true)
        element<String?>("dueDatetime", isOptional = true)
        element<Int?>("durationMinutes", isOptional = true)
        element<RecurrencePreset?>("recurrencePreset", isOptional = true)
        element<String?>("timeZone", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: RootTaskPatch) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RootTaskPatchSerializer supports JSON only")
        jsonEncoder.encodeJsonElement(buildJsonObject {
            put("expectedTaskRevision", value.expectedTaskRevision)
            value.title?.let { put("title", it) }
            value.description.putNullableStringPatch("description", this)
            value.projectId?.let { put("projectId", it) }
            value.assigneeId.putNullableStringPatch("assigneeId", this)
            value.dueDatetime.putNullableStringPatch("dueDatetime", this)
            value.durationMinutes.putNullableIntPatch("durationMinutes", this)
            value.recurrencePreset?.let { put("recurrencePreset", it.name) }
            value.timeZone?.let { put("timeZone", it) }
        })
    }

    override fun deserialize(decoder: Decoder): RootTaskPatch {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RootTaskPatchSerializer supports JSON only")
        val value = jsonDecoder.decodeJsonElement().jsonObject
        return RootTaskPatch(
            expectedTaskRevision = requiredString(value, "expectedTaskRevision"),
            title = value["title"]?.jsonPrimitive?.contentOrNull,
            description = value.nullableStringPatch("description"),
            projectId = value["projectId"]?.jsonPrimitive?.contentOrNull,
            assigneeId = value.nullableStringPatch("assigneeId"),
            dueDatetime = value.nullableStringPatch("dueDatetime"),
            durationMinutes = value.nullableIntPatch("durationMinutes"),
            recurrencePreset = value["recurrencePreset"]?.jsonPrimitive?.contentOrNull?.let(RecurrencePreset::valueOf),
            timeZone = value["timeZone"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun requiredString(value: JsonObject, key: String): String =
        value[key]?.jsonPrimitive?.contentOrNull ?: throw SerializationException("Missing $key")

    private fun JsonObject.nullableStringPatch(key: String): NullablePatch<String> =
        if (containsKey(key)) NullablePatch.Value(this[key]?.jsonPrimitive?.contentOrNull) else NullablePatch.Absent

    private fun JsonObject.nullableIntPatch(key: String): NullablePatch<Int> =
        if (!containsKey(key)) {
            NullablePatch.Absent
        } else {
            val element = getValue(key)
            NullablePatch.Value(if (element is JsonNull) null else element.jsonPrimitive.int)
        }

    private fun NullablePatch<String>.putNullableStringPatch(key: String, target: kotlinx.serialization.json.JsonObjectBuilder) {
        when (this) {
            NullablePatch.Absent -> Unit
            is NullablePatch.Value -> target.put(key, value?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun NullablePatch<Int>.putNullableIntPatch(key: String, target: kotlinx.serialization.json.JsonObjectBuilder) {
        when (this) {
            NullablePatch.Absent -> Unit
            is NullablePatch.Value -> target.put(key, value?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}

@Serializable
sealed class ChecklistMutation {
    abstract val clientMutationId: String

    @Serializable
    @kotlinx.serialization.SerialName("add")
    data class Add(
        override val clientMutationId: String,
        val title: String,
        val description: String? = null
    ) : ChecklistMutation() {
        init {
            requireNonBlank("clientMutationId", clientMutationId)
            requireNonBlank("title", title)
        }
    }

    @Serializable
    @kotlinx.serialization.SerialName("edit")
    data class Edit(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String,
        val title: String,
        val description: String? = null
    ) : ChecklistMutation() {
        init {
            requireNonBlank("clientMutationId", clientMutationId)
            requireNonBlank("childId", childId)
            requireNonBlank("expectedChildRevision", expectedChildRevision)
            requireNonBlank("title", title)
        }
    }

    @Serializable
    @kotlinx.serialization.SerialName("delete")
    data class Delete(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String
    ) : ChecklistMutation() {
        init {
            requireNonBlank("clientMutationId", clientMutationId)
            requireNonBlank("childId", childId)
            requireNonBlank("expectedChildRevision", expectedChildRevision)
        }
    }

    @Serializable
    @kotlinx.serialization.SerialName("check")
    data class Check(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String
    ) : ChecklistMutation() {
        init {
            requireNonBlank("clientMutationId", clientMutationId)
            requireNonBlank("childId", childId)
            requireNonBlank("expectedChildRevision", expectedChildRevision)
        }
    }

    @Serializable
    @kotlinx.serialization.SerialName("uncheck")
    data class Uncheck(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String
    ) : ChecklistMutation() {
        init {
            requireNonBlank("clientMutationId", clientMutationId)
            requireNonBlank("childId", childId)
            requireNonBlank("expectedChildRevision", expectedChildRevision)
        }
    }
}

@Serializable
data class TaskActionRequest(
    val expectedOccurrenceKey: String,
    val expectedTaskRevision: String? = null
) {
    init {
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        expectedTaskRevision?.let { requireNonBlank("expectedTaskRevision", it) }
    }
}

@Serializable
data class ExtendTaskRequest(
    val expectedOccurrenceKey: String,
    val expectedTaskRevision: String
) {
    init {
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("expectedTaskRevision", expectedTaskRevision)
    }
}

@Serializable
data class AddChecklistItemRequest(
    val expectedOccurrenceKey: String,
    val clientMutationId: String,
    val title: String,
    val description: String? = null
) {
    init {
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("clientMutationId", clientMutationId)
        requireNonBlank("title", title)
    }
}

@Serializable
data class UpdateChecklistItemRequest(
    val expectedOccurrenceKey: String,
    val expectedChildRevision: String,
    val title: String,
    val description: String? = null
) {
    init {
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("expectedChildRevision", expectedChildRevision)
        requireNonBlank("title", title)
    }
}

@Serializable
data class FocusedChecklistMutationRequest(
    val expectedOccurrenceKey: String,
    val expectedChildRevision: String
) {
    init {
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("expectedChildRevision", expectedChildRevision)
    }
}

@Serializable
data class MutationResponse(
    val snapshot: TasksSnapshotResponse,
    val snoozedUntil: String? = null
)

private fun requireNonBlank(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
}
