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
    val updatedAt: String
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
    val durationMinutes: Int
)

@Serializable(with = RescheduleTaskRequestSerializer::class)
data class RescheduleTaskRequest(
    val dueDatetime: String,
    val durationMinutes: Int,
    val description: String? = null,
    val descriptionUpdate: DescriptionUpdate = if (description == null) DescriptionUpdate.Unchanged else DescriptionUpdate.Set
) {
    enum class DescriptionUpdate {
        Unchanged,
        Set,
        Clear
    }

    companion object {
        fun clearDescription(
            dueDatetime: String,
            durationMinutes: Int
        ): RescheduleTaskRequest =
            RescheduleTaskRequest(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                description = null,
                descriptionUpdate = DescriptionUpdate.Clear
            )

        fun setDescription(
            dueDatetime: String,
            durationMinutes: Int,
            description: String
        ): RescheduleTaskRequest =
            RescheduleTaskRequest(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                description = description,
                descriptionUpdate = DescriptionUpdate.Set
            )
    }
}

object RescheduleTaskRequestSerializer : KSerializer<RescheduleTaskRequest> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RescheduleTaskRequest") {
        element<String>("dueDatetime")
        element<Int>("durationMinutes")
        element<String?>("description", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: RescheduleTaskRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RescheduleTaskRequestSerializer supports JSON only")
        val element = buildJsonObject {
            put("dueDatetime", value.dueDatetime)
            put("durationMinutes", value.durationMinutes)
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
        if (!element.containsKey("description")) {
            return RescheduleTaskRequest(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes
            )
        }
        val descriptionElement = element["description"]
        return if (descriptionElement == null || descriptionElement is JsonNull) {
            RescheduleTaskRequest.clearDescription(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes
            )
        } else {
            RescheduleTaskRequest.setDescription(
                dueDatetime = dueDatetime,
                durationMinutes = durationMinutes,
                description = descriptionElement.jsonPrimitive.contentOrNull.orEmpty()
            )
        }
    }

    private fun requiredString(element: JsonObject, key: String): String =
        element[key]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("Missing $key")
}
