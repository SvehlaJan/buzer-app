package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectsResponse
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BuzerApiClientJsonTest {
    @Test
    fun createTaskRequestOmitsNullableFieldsWhenAbsent() {
        val body = buzerApiJson.encodeToString(
            CreateTaskRequest(
                title = "Strict task",
                projectId = "project-personal",
                assigneeId = null,
                dueDatetime = "2026-06-13T12:00:00Z",
                durationMinutes = 30,
                actionId = "action-create-1",
                deviceId = "device-1"
            )
        )

        assertContains(body, """"projectId":"project-personal"""")
        assertFalse(body.contains("assigneeId"))
    }

    @Test
    fun taskRequestsEncodeDescriptionWhenPresent() {
        val createBody = buzerApiJson.encodeToString(
            CreateTaskRequest(
                title = "Strict task",
                description = "Gate code: 2468",
                projectId = "project-personal",
                assigneeId = null,
                dueDatetime = "2026-06-13T12:00:00Z",
                durationMinutes = 30,
                actionId = "action-create-2",
                deviceId = "device-1"
            )
        )
        val rescheduleBody = buzerApiJson.encodeToString(
            RescheduleTaskRequest(
                dueDatetime = "2026-06-13T13:00:00Z",
                durationMinutes = 45,
                description = "  Updated gate code: 1357  ",
                actionId = "action-reschedule-1",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1"
            )
        )

        assertContains(createBody, """"description":"Gate code: 2468"""")
        assertContains(rescheduleBody, """"description":"Updated gate code: 1357"""")
    }

    @Test
    fun updateTaskRequestEncodesAllEditableTodoistFields() {
        val body = buzerApiJson.encodeToString(
            UpdateTaskRequest(
                actionId = "action-edit-1",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1",
                rootPatch = RootTaskPatch(
                    expectedTaskRevision = "r:3",
                    title = "Edited task",
                    description = "Updated details",
                    projectId = "project-family",
                    assigneeId = "user-me",
                    dueDatetime = "2026-06-13T13:00:00Z",
                    durationMinutes = 45
                )
            )
        )

        assertContains(body, """"title":"Edited task"""")
        assertContains(body, """"description":"Updated details"""")
        assertContains(body, """"projectId":"project-family"""")
        assertContains(body, """"assigneeId":"user-me"""")
        assertContains(body, """"dueDatetime":"2026-06-13T13:00:00Z"""")
        assertContains(body, """"durationMinutes":45""")
    }

    @Test
    fun rescheduleTaskRequestEncodesDescriptionPatchSemantics() {
        val unchangedBody = buzerApiJson.encodeToString(
            RescheduleTaskRequest(
                dueDatetime = "2026-06-13T13:00:00Z",
                durationMinutes = 45,
                actionId = "action-reschedule-1",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1"
            )
        )
        val clearNullBody = buzerApiJson.encodeToString(
            RescheduleTaskRequest.clearDescription(
                dueDatetime = "2026-06-13T13:00:00Z",
                durationMinutes = 45,
                actionId = "action-reschedule-2",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1"
            )
        )
        val clearBlankBody = buzerApiJson.encodeToString(
            RescheduleTaskRequest.setDescription(
                dueDatetime = "2026-06-13T13:00:00Z",
                durationMinutes = 45,
                description = "   ",
                actionId = "action-reschedule-3",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1"
            )
        )
        val setBody = buzerApiJson.encodeToString(
            RescheduleTaskRequest.setDescription(
                dueDatetime = "2026-06-13T13:00:00Z",
                durationMinutes = 45,
                description = "  Updated gate code: 1357  ",
                actionId = "action-reschedule-4",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1"
            )
        )

        assertFalse(unchangedBody.contains("description"))
        assertContains(clearNullBody, """"description":null""")
        assertContains(clearBlankBody, """"description":""""")
        assertContains(setBody, """"description":"Updated gate code: 1357"""")
    }

    @Test
    fun taskSnapshotDecodesDescription() {
        val response = buzerApiJson.decodeFromString<TasksSnapshotResponse>(
            """
            {
              "scheduleVersion": 1,
              "serverTime": "2026-06-13T10:00:00Z",
              "tasks": [
                {
                  "id": "task-1",
                  "todoistTaskId": "todoist-1",
                  "title": "Pick up parcel",
                  "description": "Bring printed form",
                  "projectId": null,
                  "assigneeId": "user-self",
                  "creatorId": "wife",
                  "dueDatetime": "2026-06-13T12:00:00Z",
                  "durationMinutes": 30,
                  "startAt": "2026-06-13T12:00:00Z",
                  "state": "planned",
                  "isAssignedToUser": true,
                  "isCreatedByUser": false,
                  "isCompleted": false,
                  "isDeleted": false,
                  "validationErrors": [],
                  "startedAt": null,
                  "startDismissCount": 0,
                  "deadlineExtensionCount": 0,
                  "escalationSentAt": null,
                  "updatedAt": "2026-06-13T10:00:00Z"
                }
              ],
              "health": []
            }
            """.trimIndent()
        )

        assertEquals("Bring printed form", response.tasks.single().description)
    }

    @Test
    fun projectsResponseDecodesProjectCapabilities() {
        val response = buzerApiJson.decodeFromString<ProjectsResponse>(
            """
            {
              "projects": [
                {
                  "id": "project-family",
                  "name": "Family",
                  "canAssignTasks": true,
                  "shared": true,
                  "isInboxProject": false
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("project-family", response.projects.single().id)
        assertEquals(true, response.projects.single().canAssignTasks)
    }
}
