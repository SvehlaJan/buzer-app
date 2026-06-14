package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectsResponse
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
                durationMinutes = 30
            )
        )

        assertContains(body, """"projectId":"project-personal"""")
        assertFalse(body.contains("assigneeId"))
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
