package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectsResponse
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.SerializationException

class BuzerApiClientJsonTest {
    @Test
    fun createTaskRequestOmitsNullableFieldsWhenAbsent() {
        val body = buzerApiJson.encodeToString(
            CreateTaskRequest(
                title = "Strict task",
                projectId = "project-personal",
                assigneeId = null,
                dueDatetime = "2026-06-13T12:00:00Z",
                durationMinutes = 30
            )
        )

        assertContains(body, """"projectId":"project-personal"""")
        assertFalse(body.contains("assigneeId"))
    }

    @Test
    fun createTaskRequestEncodesExplicitNullSchedule() {
        val body = buzerApiJson.encodeToString(
            CreateTaskRequest(
                title = "Unscheduled shared task",
                projectId = "project-family",
                assigneeId = "user-wife",
                dueDatetime = null,
                durationMinutes = null
            )
        )

        assertContains(body, "\"dueDatetime\":null")
        assertContains(body, "\"durationMinutes\":null")
    }

    @Test
    fun updateTaskRequestEncodesAllEditableTodoistFields() {
        val body = buzerApiJson.encodeToString(
            UpdateTaskRequest(
                expectedOccurrenceKey = "occurrence-1",
                rootPatch = RootTaskPatch(
                    expectedTaskRevision = "r:3",
                    title = "Edited task",
                    description = NullablePatch.Value("Updated details"),
                    projectId = "project-family",
                    assigneeId = NullablePatch.Value("user-me"),
                    dueDatetime = NullablePatch.Value("2026-06-13T13:00:00Z"),
                    durationMinutes = NullablePatch.Value(45)
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
    fun rootTaskPatchDistinguishesScheduleClearFromAbsence() {
        val clearBody = buzerApiJson.encodeToString(
            UpdateTaskRequest(
                expectedOccurrenceKey = "occurrence-1",
                rootPatch = RootTaskPatch(
                    expectedTaskRevision = "r:3",
                    dueDatetime = NullablePatch.Value(null),
                    durationMinutes = NullablePatch.Value(null)
                )
            )
        )
        val unchangedBody = buzerApiJson.encodeToString(
            UpdateTaskRequest(
                expectedOccurrenceKey = "occurrence-1",
                rootPatch = RootTaskPatch(expectedTaskRevision = "r:3")
            )
        )

        assertContains(clearBody, "\"dueDatetime\":null")
        assertContains(clearBody, "\"durationMinutes\":null")
        assertFalse(unchangedBody.contains("dueDatetime"))
        assertFalse(unchangedBody.contains("durationMinutes"))
    }

    @Test
    fun rootOnlyUpdateTaskRequestEncodesEmptyChildMutations() {
        val body = buzerApiJson.encodeToString(
            UpdateTaskRequest(
                expectedOccurrenceKey = "occurrence-1",
                rootPatch = RootTaskPatch(expectedTaskRevision = "r:3", title = "Edited task")
            )
        )

        assertContains(body, "\"childMutations\":[]")
    }

    @Test
    fun taskSnapshotDecodesDescription() {
        val response = buzerApiJson.decodeFromString<TasksSnapshotResponse>(
            """
            {
              "scheduleVersion": 1,
              "snapshotGeneration": 1,
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
        assertEquals(1, response.snapshotGeneration)
    }

    @Test
    fun networkSnapshotWithoutGenerationIsRejected() {
        assertFailsWith<SerializationException> {
            buzerApiJson.decodeFromString<TasksSnapshotResponse>(
                """{"scheduleVersion":1,"serverTime":"2026-06-13T10:00:00Z","health":[],"tasks":[]}"""
            )
        }
    }

    @Test
    fun projectsResponseDecodesProjectCapabilitiesAndCollaborators() {
        val response = buzerApiJson.decodeFromString<ProjectsResponse>(
            """
            {
              "projects": [
                {
                  "id": "project-family",
                  "name": "Family",
                  "canAssignTasks": true,
                  "shared": true,
                  "isInboxProject": false,
                  "collaboratorsReady": true,
                  "collaborators": [
                    {
                      "id": "user-me",
                      "displayName": "Me",
                      "email": null,
                      "isCurrentUser": true
                    },
                    {
                      "id": "user-wife",
                      "displayName": "Wife",
                      "email": "wife@example.test",
                      "isCurrentUser": false
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("project-family", response.projects.single().id)
        assertEquals(true, response.projects.single().canAssignTasks)
        assertEquals(true, response.projects.single().collaboratorsReady)
        assertEquals("Wife", response.projects.single().collaborators[1].displayName)
        assertEquals("wife@example.test", response.projects.single().collaborators[1].email)
    }
}
