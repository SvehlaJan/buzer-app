package app.buzer.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
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
    val hierarchyViolation: String? = null,
    val reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none
)

@Serializable
enum class RecurrenceKind { none, supported, custom }

@Serializable
enum class RecurrencePreset { never, daily, weekdays, weekly, monthly, yearly }

@Serializable
enum class HierarchyStatus { valid, unsupported }

@Serializable
enum class ReconciliationStatus { none, pending, confirmed }

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
    val health: List<HealthStatus>
)

@Serializable
data class ProjectItem(
    val id: String,
    val name: String,
    val canAssignTasks: Boolean,
    val shared: Boolean,
    val isInboxProject: Boolean
)

@Serializable
data class ProjectsResponse(
    val projects: List<ProjectItem>
)

@Serializable
data class CreateTaskRequest(
    val title: String,
    val description: String? = null,
    val projectId: String,
    val assigneeId: String? = null,
    val dueDatetime: String,
    val durationMinutes: Int,
    val actionId: String,
    val deviceId: String,
    val recurrencePreset: RecurrencePreset = RecurrencePreset.never,
    val timeZone: String? = null,
    val checklistDrafts: List<ChecklistDraft> = emptyList()
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
    }
}

@Serializable
data class UpdateTaskRequest(
    val actionId: String,
    val deviceId: String,
    val expectedOccurrenceKey: String,
    val rootPatch: RootTaskPatch? = null,
    val childMutations: List<ChecklistMutation> = emptyList()
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
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
    }
}

@Serializable(with = RootTaskPatchSerializer::class)
data class RootTaskPatch(
    val expectedTaskRevision: String,
    val title: String? = null,
    val description: String? = null,
    val projectId: String? = null,
    val assigneeId: String? = null,
    val dueDatetime: String? = null,
    val durationMinutes: Int? = null,
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
            value.description?.let { put("description", it) }
            value.projectId?.let { put("projectId", it) }
            put("assigneeId", value.assigneeId?.let(::JsonPrimitive) ?: JsonNull)
            value.dueDatetime?.let { put("dueDatetime", it) }
            value.durationMinutes?.let { put("durationMinutes", it) }
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
            description = value["description"]?.jsonPrimitive?.contentOrNull,
            projectId = value["projectId"]?.jsonPrimitive?.contentOrNull,
            assigneeId = value["assigneeId"]?.jsonPrimitive?.contentOrNull,
            dueDatetime = value["dueDatetime"]?.jsonPrimitive?.contentOrNull,
            durationMinutes = value["durationMinutes"]?.jsonPrimitive?.int,
            recurrencePreset = value["recurrencePreset"]?.jsonPrimitive?.contentOrNull?.let(RecurrencePreset::valueOf),
            timeZone = value["timeZone"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun requiredString(value: JsonObject, key: String): String =
        value[key]?.jsonPrimitive?.contentOrNull ?: throw SerializationException("Missing $key")
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
        }
    }

    @Serializable
    @kotlinx.serialization.SerialName("edit")
    data class Edit(
        override val clientMutationId: String,
        val childId: String,
        val expectedChildRevision: String,
        val title: String? = null,
        val description: String? = null
    ) : ChecklistMutation() {
        init {
            requireNonBlank("clientMutationId", clientMutationId)
            requireNonBlank("expectedChildRevision", expectedChildRevision)
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
            requireNonBlank("expectedChildRevision", expectedChildRevision)
        }
    }
}

@Serializable
data class TaskActionRequest(
    val actionId: String,
    val deviceId: String,
    val expectedOccurrenceKey: String
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
    }
}

@Serializable
data class AddChecklistItemRequest(
    val actionId: String,
    val deviceId: String,
    val expectedOccurrenceKey: String,
    val clientMutationId: String,
    val title: String,
    val description: String? = null
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("clientMutationId", clientMutationId)
    }
}

@Serializable
data class UpdateChecklistItemRequest(
    val actionId: String,
    val deviceId: String,
    val expectedOccurrenceKey: String,
    val expectedChildRevision: String,
    val title: String? = null,
    val description: String? = null
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("expectedChildRevision", expectedChildRevision)
    }
}

@Serializable
data class FocusedChecklistMutationRequest(
    val actionId: String,
    val deviceId: String,
    val expectedOccurrenceKey: String,
    val expectedChildRevision: String,
    val checked: Boolean
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
        requireNonBlank("expectedChildRevision", expectedChildRevision)
    }
}

@Serializable
enum class MutationOutcome { completed, partial, reconciliation_pending, failed, stale_child }

@Serializable
data class ChecklistMutationOutcome(
    val clientMutationId: String? = null,
    val childId: String? = null,
    val outcome: MutationOutcome,
    val errorCode: String? = null
)

@Serializable
data class MutationResponse(
    val actionId: String,
    val outcome: MutationOutcome,
    val snapshot: TasksSnapshotResponse,
    val childOutcomes: List<ChecklistMutationOutcome> = emptyList(),
    val snoozedUntil: String? = null
)

@Serializable(with = RescheduleTaskRequestSerializer::class)
data class RescheduleTaskRequest(
    val dueDatetime: String,
    val durationMinutes: Int,
    val description: String? = null,
    val descriptionUpdate: DescriptionUpdate = if (description == null) DescriptionUpdate.Unchanged else DescriptionUpdate.Set,
    val actionId: String,
    val deviceId: String,
    val expectedOccurrenceKey: String
) {
    init {
        requireNonBlank("actionId", actionId)
        requireNonBlank("deviceId", deviceId)
        requireNonBlank("expectedOccurrenceKey", expectedOccurrenceKey)
    }
    enum class DescriptionUpdate {
        Unchanged,
        Set,
        Clear
    }

    companion object {
        fun clearDescription(
            dueDatetime: String,
            durationMinutes: Int,
            actionId: String,
            deviceId: String,
            expectedOccurrenceKey: String
        ): RescheduleTaskRequest =
            RescheduleTaskRequest(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                description = null,
                descriptionUpdate = DescriptionUpdate.Clear,
                actionId = actionId,
                deviceId = deviceId,
                expectedOccurrenceKey = expectedOccurrenceKey
            )

        fun setDescription(
            dueDatetime: String,
            durationMinutes: Int,
            description: String,
            actionId: String,
            deviceId: String,
            expectedOccurrenceKey: String
        ): RescheduleTaskRequest =
            RescheduleTaskRequest(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                description = description,
                descriptionUpdate = DescriptionUpdate.Set,
                actionId = actionId,
                deviceId = deviceId,
                expectedOccurrenceKey = expectedOccurrenceKey
            )
    }
}

object RescheduleTaskRequestSerializer : KSerializer<RescheduleTaskRequest> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RescheduleTaskRequest") {
        element<String>("dueDatetime")
        element<Int>("durationMinutes")
        element<String?>("description", isOptional = true)
        element<String>("actionId")
        element<String>("deviceId")
        element<String>("expectedOccurrenceKey")
    }

    override fun serialize(encoder: Encoder, value: RescheduleTaskRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RescheduleTaskRequestSerializer supports JSON only")
        val element = buildJsonObject {
            put("dueDatetime", value.dueDatetime)
            put("durationMinutes", value.durationMinutes)
            put("actionId", value.actionId)
            put("deviceId", value.deviceId)
            put("expectedOccurrenceKey", value.expectedOccurrenceKey)
            when (value.descriptionUpdate) {
                RescheduleTaskRequest.DescriptionUpdate.Unchanged -> Unit
                RescheduleTaskRequest.DescriptionUpdate.Clear -> put("description", JsonNull)
                RescheduleTaskRequest.DescriptionUpdate.Set -> put(
                    "description",
                    JsonPrimitive(value.description.orEmpty().trim())
                )
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): RescheduleTaskRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RescheduleTaskRequestSerializer supports JSON only")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val dueDatetime = requiredString(element, "dueDatetime")
        val durationMinutes = element["durationMinutes"]?.jsonPrimitive?.int
            ?: throw SerializationException("Missing durationMinutes")
        val actionId = element["actionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val deviceId = element["deviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expectedOccurrenceKey = element["expectedOccurrenceKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!element.containsKey("description")) {
            return RescheduleTaskRequest(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                actionId = actionId,
                deviceId = deviceId,
                expectedOccurrenceKey = expectedOccurrenceKey
            )
        }
        val descriptionElement = element["description"]
        return if (descriptionElement == null || descriptionElement is JsonNull) {
            RescheduleTaskRequest.clearDescription(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                actionId = actionId,
                deviceId = deviceId,
                expectedOccurrenceKey = expectedOccurrenceKey
            )
        } else {
            RescheduleTaskRequest.setDescription(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                description = descriptionElement.jsonPrimitive.contentOrNull.orEmpty(),
                actionId = actionId,
                deviceId = deviceId,
                expectedOccurrenceKey = expectedOccurrenceKey
            )
        }
    }

    private fun requiredString(element: JsonObject, key: String): String =
        element[key]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Missing $key")
}

private fun requireNonBlank(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
}
