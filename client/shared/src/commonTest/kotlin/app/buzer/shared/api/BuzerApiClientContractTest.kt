package app.buzer.shared.api

import app.buzer.shared.model.ActivityLogEntry
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.ChecklistMutationOutcome
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.model.UpdateChecklistItemRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class BuzerApiClientContractTest {
    @Test
    fun snapshotDecodesFixedRecurringChecklistFields() {
        val response = buzerApiJson.decodeFromString<TasksSnapshotResponse>(
            """
            {
              "scheduleVersion": 7,
              "serverTime": "2026-07-16T10:00:00Z",
              "health": [],
              "tasks": [{
                "id": "root-1", "todoistTaskId": "todoist-root-1", "title": "Plan release",
                "description": "User notes", "projectId": "project-1", "assigneeId": null,
                "creatorId": "user-1", "dueDatetime": "2026-07-16T18:00:00Z", "dueDate": "2026-07-16",
                "durationMinutes": 60, "startAt": "2026-07-16T18:00:00Z", "state": "started",
                "isAssignedToUser": true, "isCreatedByUser": true, "isCompleted": false, "isDeleted": false,
                "validationErrors": [], "startedAt": "2026-07-16T18:00:00Z", "startDismissCount": 1,
                "deadlineExtensionCount": 2, "escalationSentAt": null, "updatedAt": "2026-07-16T10:00:00Z",
                "occurrenceKey": "occurrence-opaque", "taskRevision": "r:9",
                "activityLogEntries": [{"timestamp":"2026-07-16 18:05","timeZone":"Europe/Bratislava","message":"Extended +30 min (60 → 90 min)"}],
                "recurrence": {"kind":"supported","preset":"weekly","rule":"every Monday at 18:00","language":"en","timeZone":"Europe/Bratislava","displayLabel":"Every Monday"},
                "checklistItems": [{"id":"child-1","title":"Confirm notes","description":null,"checked":true,"order":1,"childRevision":"r:4"}],
                "checkedCount": 1, "totalCount": 1, "hierarchyStatus": "valid", "hierarchyViolation": null,
                "reconciliationStatus": "confirmed"
              }]
            }
            """.trimIndent()
        )

        val task = response.tasks.single()
        assertEquals("occurrence-opaque", task.occurrenceKey)
        assertEquals("r:9", task.taskRevision)
        assertEquals("Every Monday", task.recurrence.displayLabel)
        assertEquals(RecurrenceKind.supported, task.recurrence.kind)
        assertEquals(RecurrencePreset.weekly, task.recurrence.preset)
        assertEquals("r:4", task.checklistItems.single().childRevision)
        assertEquals(1, task.checkedCount)
        assertEquals("Extended +30 min (60 → 90 min)", task.activityLogEntries.single().message)
        assertEquals(null, task.assigneeId)
    }

    @Test
    fun recurrenceEnumsEncodeTheirFixedWireValues() {
        val body = buzerApiJson.encodeToString(
            TaskRecurrence(
                kind = RecurrenceKind.custom,
                preset = null,
                rule = "every 2 days at 10:00",
                language = "en",
                timeZone = "Europe/Bratislava",
                displayLabel = "Custom Todoist recurrence"
            )
        )

        assertContains(body, "\"kind\":\"custom\"")
        assertContains(buzerApiJson.encodeToString(RecurrencePreset.weekdays), "\"weekdays\"")
    }

    @Test
    fun createRequestSerializesActionMetadataRecurrenceAndDrafts() {
        val body = buzerApiJson.encodeToString(
            CreateTaskRequest(
                title = "Prepare release",
                projectId = "project-1",
                assigneeId = "user-1",
                dueDatetime = "2026-07-16T18:00:00Z",
                durationMinutes = 60,
                actionId = "action-create-1",
                deviceId = "device-1",
                recurrencePreset = RecurrencePreset.weekly,
                timeZone = "Europe/Bratislava",
                checklistDrafts = listOf(ChecklistDraft("draft-1", "Confirm notes", null))
            )
        )

        assertContains(body, "\"actionId\":\"action-create-1\"")
        assertContains(body, "\"deviceId\":\"device-1\"")
        assertContains(body, "\"recurrencePreset\":\"weekly\"")
        assertContains(body, "\"clientMutationId\":\"draft-1\"")
    }

    @Test
    fun updateRequestSerializesExplicitRootAndChildRevisionsIncludingNullAssignee() {
        val body = buzerApiJson.encodeToString(
            UpdateTaskRequest(
                actionId = "action-edit-1",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1",
                rootPatch = RootTaskPatch(
                    expectedTaskRevision = "r:5",
                    title = "Renamed",
                    assigneeId = NullablePatch.Value(null)
                ),
                childMutations = listOf(
                    ChecklistMutation.Check(
                        clientMutationId = "check-1",
                        childId = "child-1",
                        expectedChildRevision = "r:3"
                    ),
                    ChecklistMutation.Uncheck(
                        clientMutationId = "uncheck-1",
                        childId = "child-2",
                        expectedChildRevision = "r:4"
                    )
                )
            )
        )

        assertContains(body, "\"expectedOccurrenceKey\":\"occurrence-1\"")
        assertContains(body, "\"expectedTaskRevision\":\"r:5\"")
        assertContains(body, "\"assigneeId\":null")
        assertContains(body, "\"type\":\"check\"")
        assertContains(body, "\"type\":\"uncheck\"")
        assertContains(body, "\"expectedChildRevision\":\"r:4\"")
    }

    @Test
    fun mutationResponseDecodesPartialChildOutcomes() {
        val response = buzerApiJson.decodeFromString<MutationResponse>(
            """
            {
              "actionId": "action-create-1", "outcome": "partial",
              "childOutcomes": [
                {"clientMutationId":"draft-1","childId":"child-1","outcome":"completed","errorCode":null},
                {"clientMutationId":"draft-2","childId":null,"outcome":"failed","errorCode":"simulated_child_failure"}
              ],
              "snapshot": {"scheduleVersion":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}
            }
            """.trimIndent()
        )

        assertEquals(MutationOutcome.partial, response.outcome)
        assertEquals("simulated_child_failure", response.childOutcomes[1].errorCode)
        assertEquals(null, response.childOutcomes[1].childId)
    }

    @Test
    fun childOutcomeDecodesStaleChildWithoutRejectingTheWholeResponse() {
        val response = buzerApiJson.decodeFromString<MutationResponse>(
            """
            {
              "actionId":"action-edit-1", "outcome":"partial",
              "childOutcomes":[{"clientMutationId":"check-1","childId":"child-1","outcome":"stale_child","errorCode":"stale_child"}],
              "snapshot":{"scheduleVersion":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}
            }
            """.trimIndent()
        )

        assertEquals(MutationOutcome.stale_child, response.childOutcomes.single().outcome)
    }

    @Test
    fun fixedMutationRequestsRejectBlankActionMetadata() {
        assertFailsWith<IllegalArgumentException> {
            CreateTaskRequest(
                title = "Prepare release",
                projectId = "project-1",
                dueDatetime = "2026-07-16T18:00:00Z",
                durationMinutes = 60,
                actionId = " ",
                deviceId = "device-1"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TaskActionRequest(
                actionId = "action-start-1",
                deviceId = "device-1",
                expectedOccurrenceKey = " "
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateTaskRequest(
                actionId = "action-edit-1",
                deviceId = " ",
                expectedOccurrenceKey = "occurrence-1"
            )
        }
    }

    @Test
    fun checklistMutationsRejectBlankMutationIdsAndRevisions() {
        assertFailsWith<IllegalArgumentException> {
            ChecklistMutation.Add(clientMutationId = " ", title = "Confirm notes")
        }
        assertFailsWith<IllegalArgumentException> {
            ChecklistMutation.Edit(
                clientMutationId = "edit-1",
                childId = "child-1",
                expectedChildRevision = " ",
                title = "Updated title"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChecklistMutation.Delete(
                clientMutationId = "delete-1",
                childId = "child-1",
                expectedChildRevision = " "
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChecklistMutation.Check(
                clientMutationId = " ",
                childId = "child-1",
                expectedChildRevision = "r:2"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChecklistMutation.Uncheck(
                clientMutationId = "uncheck-1",
                childId = "child-1",
                expectedChildRevision = " "
            )
        }
    }

    @Test
    fun rootPatchKeepsAbsentFieldsDistinctFromExplicitNullableClears() {
        val titleOnly = buzerApiJson.encodeToString(
            RootTaskPatch(expectedTaskRevision = "r:5", title = "Renamed")
        )
        val clearNullableFields = buzerApiJson.encodeToString(
            RootTaskPatch(
                expectedTaskRevision = "r:5",
                description = NullablePatch.Value(null),
                assigneeId = NullablePatch.Value(null)
            )
        )

        assertFalse(titleOnly.contains("description"))
        assertFalse(titleOnly.contains("assigneeId"))
        assertContains(clearNullableFields, "\"description\":null")
        assertContains(clearNullableFields, "\"assigneeId\":null")
    }

    @Test
    fun checklistEditRequestsRequireNonblankTitles() {
        assertFailsWith<IllegalArgumentException> {
            ChecklistMutation.Edit(
                clientMutationId = "edit-1",
                childId = "child-1",
                expectedChildRevision = "r:2",
                title = " "
            )
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateChecklistItemRequest(
                actionId = "action-edit-1",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-1",
                expectedChildRevision = "r:2",
                title = " "
            )
        }
    }

    @Test
    fun focusedChecklistRequestSerializesReusableActionAndChildRevision() {
        val request = FocusedChecklistMutationRequest(
            actionId = "action-check-1",
            deviceId = "device-1",
            expectedOccurrenceKey = "occurrence-1",
            expectedChildRevision = "r:8"
        )

        val body = buzerApiJson.encodeToString(request)

        assertContains(body, "\"actionId\":\"action-check-1\"")
        assertContains(body, "\"expectedChildRevision\":\"r:8\"")
        assertFalse(body.contains("checked"))
        assertFalse(body.contains("buzer"))
    }
}
