package app.buzer.shared.validation

data class ValidationResult(val valid: Boolean, val errors: List<String>)

object TaskValidation {
    fun validate(
        title: String,
        creatorId: String?,
        assigneeId: String?,
        dueDatetime: String?,
        durationMinutes: Int?,
        currentUserId: String
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val createdByUser = creatorId == currentUserId
        val assignedToUser = assigneeId == currentUserId

        if (title.isBlank()) errors += "title_required"
        if (createdByUser && assigneeId == null) errors += "assignee_required_for_created_task"
        if (assignedToUser && dueDatetime == null) errors += "due_datetime_required"
        if (assignedToUser && durationMinutes == null) errors += "duration_required"
        if (assignedToUser && durationMinutes != null && durationMinutes <= 0) {
            errors += "duration_must_be_positive_minutes"
        }

        return ValidationResult(errors.isEmpty(), errors)
    }
}
