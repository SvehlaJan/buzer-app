package app.buzer.android.observability

import android.content.Context
import app.buzer.android.BuildConfig
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticRedactor
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Contexts
import io.sentry.protocol.SentryId

object SentryEventSanitizer {
    private val redactor = DiagnosticRedactor()

    fun sanitizeEvent(event: SentryEvent): SentryEvent {
        event.message?.let { msg ->
            msg.formatted?.let { formatted ->
                msg.formatted = redactor.redactString(formatted)
            }
            msg.message?.let { raw ->
                msg.message = redactor.redactString(raw)
            }
        }

        event.exceptions?.forEach { ex ->
            ex.value = ex.value?.let { redactor.redactString(it) }
            ex.type = ex.type?.let { redactor.redactString(it) }
        }

        val tagsCopy = event.tags?.toMap() ?: emptyMap()
        tagsCopy.forEach { (key, value) ->
            if (DiagnosticRedactor.isCredentialKey(key)) {
                event.setTag(key, "[REDACTED]")
            } else {
                event.setTag(key, redactor.redactString(value))
            }
        }

        sanitizeContexts(event.contexts)

        event.breadcrumbs?.forEach { breadcrumb ->
            sanitizeBreadcrumb(breadcrumb)
        }

        event.fingerprints = event.fingerprints?.map { redactor.redactString(it) }

        val extrasCopy = event.extras?.toMap() ?: emptyMap()
        extrasCopy.forEach { (key, value) ->
            if (DiagnosticRedactor.isCredentialKey(key)) {
                event.setExtra(key, "[REDACTED]")
            } else {
                event.setExtra(key, redactor.redactAny(value))
            }
        }

        return event
    }

    fun sanitizeContexts(contexts: Contexts): Contexts {
        val contextKeys = contexts.keys().toList()
        for (key in contextKeys) {
            val value = contexts[key]
            if (DiagnosticRedactor.isCredentialKey(key)) {
                contexts[key] = "[REDACTED]"
            } else {
                val redacted = redactor.redactAny(value)
                if (redacted != null) {
                    contexts[key] = redacted
                }
            }
        }
        return contexts
    }

    fun sanitizeBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = breadcrumb.message?.let { redactor.redactString(it) }

        val dataKeys = breadcrumb.data.keys.toList()
        for (key in dataKeys) {
            val value = breadcrumb.getData(key)
            if (DiagnosticRedactor.isCredentialKey(key)) {
                breadcrumb.setData(key, "[REDACTED]")
            } else {
                breadcrumb.setData(key, redactor.redactAny(value))
            }
        }
        return breadcrumb
    }

    fun sanitizeAttachment(attachment: DiagnosticAttachment): DiagnosticAttachment {
        val isText = attachment.contentType?.startsWith("text/") == true ||
            attachment.contentType?.contains("json") == true ||
            attachment.pathname.endsWith(".json", ignoreCase = true) ||
            attachment.pathname.endsWith(".txt", ignoreCase = true) ||
            attachment.pathname.endsWith(".log", ignoreCase = true)

        val sanitizedBytes = if (isText) {
            val raw = runCatching { attachment.bytes.decodeToString() }.getOrNull()
            if (raw != null) {
                redactor.redactString(raw).toByteArray(Charsets.UTF_8)
            } else {
                attachment.bytes
            }
        } else {
            attachment.bytes
        }

        val sanitizedPath = redactor.redactString(attachment.pathname)
        return DiagnosticAttachment(
            pathname = sanitizedPath,
            bytes = sanitizedBytes,
            contentType = attachment.contentType
        )
    }

    fun formatSentryId(sentryId: SentryId): String {
        return sentryId.toString().replace("-", "").lowercase()
    }
}

class SentryAndroidObservability : AndroidObservability {
    override fun isEnabled(): Boolean = Sentry.isEnabled()

    override fun setContext(context: DiagnosticCorrelation) {
        Sentry.configureScope { scope ->
            fun setOrRemoveTag(key: String, value: String?) {
                if (value != null) {
                    scope.setTag(key, value)
                } else {
                    scope.removeTag(key)
                }
            }

            setOrRemoveTag("device_id", context.deviceId)
            setOrRemoveTag("task_id", context.taskId)
            setOrRemoveTag("occurrence_key", context.occurrenceKey)
            setOrRemoveTag("action_id", context.actionId)
            setOrRemoveTag("request_id", context.requestId)
            setOrRemoveTag("trace_id", context.traceId)
            setOrRemoveTag("screen", context.screen)
            setOrRemoveTag("blocker_type", context.blockerType)
            setOrRemoveTag("app_section", context.appSection)
            setOrRemoveTag("report_session_id", context.reportSessionId)

            val diagnosticMap = buildMap<String, Any?> {
                context.deviceId?.let { put("device_id", it) }
                context.taskId?.let { put("task_id", it) }
                context.occurrenceKey?.let { put("occurrence_key", it) }
                context.actionId?.let { put("action_id", it) }
                context.requestId?.let { put("request_id", it) }
                context.traceId?.let { put("trace_id", it) }
                context.screen?.let { put("screen", it) }
                context.blockerType?.let { put("blocker_type", it) }
                context.appSection?.let { put("app_section", it) }
                context.reportSessionId?.let { put("report_session_id", it) }
            }
            if (diagnosticMap.isNotEmpty()) {
                scope.setContexts("diagnostic", diagnosticMap)
            } else {
                scope.removeContexts("diagnostic")
            }
        }
    }

    override fun addLog(record: DiagnosticLogRecord, throwable: Throwable?) {
        val breadcrumb = Breadcrumb().apply {
            category = record.tag
            message = record.message
            level = when (record.severity.lowercase()) {
                "error" -> SentryLevel.ERROR
                "warn", "warning" -> SentryLevel.WARNING
                "info" -> SentryLevel.INFO
                "debug" -> SentryLevel.DEBUG
                else -> SentryLevel.INFO
            }
            record.throwableType?.let { setData("throwable_type", it) }
            record.throwableMessage?.let { setData("throwable_message", it) }
        }
        Sentry.addBreadcrumb(breadcrumb)
    }

    override fun captureHandled(
        marker: String,
        throwable: Throwable,
        context: DiagnosticCorrelation
    ): String? {
        val event = SentryEvent(throwable).apply {
            setTag("marker", marker)
            context.deviceId?.let { setTag("device_id", it) }
            context.taskId?.let { setTag("task_id", it) }
            context.occurrenceKey?.let { setTag("occurrence_key", it) }
            context.actionId?.let { setTag("action_id", it) }
            context.requestId?.let { setTag("request_id", it) }
            context.traceId?.let { setTag("trace_id", it) }
            context.screen?.let { setTag("screen", it) }
            context.blockerType?.let { setTag("blocker_type", it) }
            context.appSection?.let { setTag("app_section", it) }
            context.reportSessionId?.let { setTag("report_session_id", it) }
        }
        val sentryId = Sentry.captureEvent(event)
        return if (sentryId != SentryId.EMPTY_ID) SentryEventSanitizer.formatSentryId(sentryId) else null
    }

    override fun newEventId(): String {
        return SentryEventSanitizer.formatSentryId(SentryId())
    }

    override fun captureDiagnosticEvent(
        eventId: String,
        reportSessionId: String,
        submitted: Boolean,
        context: DiagnosticCorrelation,
        attachments: List<DiagnosticAttachment>
    ): String {
        val sentryEventId = runCatching { SentryId(eventId) }.getOrDefault(SentryId())
        val event = SentryEvent().apply {
            this.eventId = sentryEventId
            level = SentryLevel.INFO
            message = io.sentry.protocol.Message().apply {
                formatted = "buzer.android.manual_report"
            }
            setTag("report_session_id", reportSessionId)
            setTag("android_diagnostic_event_id", eventId)
            setTag("feedback_submitted", submitted.toString())
            setTag("capsule_schema_version", "1")
            context.deviceId?.let { setTag("device_id", it) }
            context.taskId?.let { setTag("task_id", it) }
            context.occurrenceKey?.let { setTag("occurrence_key", it) }
            context.actionId?.let { setTag("action_id", it) }
            context.requestId?.let { setTag("request_id", it) }
            context.traceId?.let { setTag("trace_id", it) }
            context.screen?.let { setTag("screen", it) }
            context.blockerType?.let { setTag("blocker_type", it) }
            context.appSection?.let { setTag("app_section", it) }

            val diagnosticMap = buildMap<String, Any?> {
                put("report_session_id", reportSessionId)
                put("android_diagnostic_event_id", eventId)
                put("feedback_submitted", submitted)
                put("capsule_schema_version", "1")
                context.deviceId?.let { put("device_id", it) }
                context.taskId?.let { put("task_id", it) }
                context.occurrenceKey?.let { put("occurrence_key", it) }
                context.actionId?.let { put("action_id", it) }
                context.requestId?.let { put("request_id", it) }
                context.traceId?.let { put("trace_id", it) }
                context.screen?.let { put("screen", it) }
                context.blockerType?.let { put("blocker_type", it) }
                context.appSection?.let { put("app_section", it) }
            }
            contexts["diagnostic"] = diagnosticMap
        }

        val hint = Hint()
        attachments.forEach { att ->
            val sanitized = SentryEventSanitizer.sanitizeAttachment(att)
            hint.addAttachment(
                Attachment(
                    sanitized.bytes,
                    sanitized.pathname,
                    sanitized.contentType
                )
            )
        }

        val capturedId = Sentry.captureEvent(event, hint)
        return SentryEventSanitizer.formatSentryId(capturedId)
    }

    override fun showFeedback(associatedEventId: String?) {
        val sentryId = associatedEventId?.takeIf { it.isNotBlank() }?.let { runCatching { SentryId(it) }.getOrNull() }
        if (sentryId != null) {
            Sentry.feedback().show(sentryId) {}
        } else {
            Sentry.feedback().show()
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        return runCatching {
            Sentry.flush(timeoutMillis)
            true
        }.getOrDefault(false)
    }
}

object ObservabilityRuntime {
    @Volatile
    var instance: AndroidObservability = NoOpAndroidObservability
        internal set

    @Volatile
    var coordinator: ManualReportCoordinator? = null
        internal set

    fun initialize(
        context: Context,
        dsn: String = BuildConfig.SENTRY_ANDROID_DSN,
        environment: String = BuildConfig.SENTRY_ENVIRONMENT,
        release: String = BuildConfig.SENTRY_RELEASE
    ): AndroidObservability {
        val manualCoordinator = ManualReportCoordinator(
            observability = object : AndroidObservability {
                override fun isEnabled(): Boolean = instance.isEnabled()
                override fun setContext(context: DiagnosticCorrelation) = instance.setContext(context)
                override fun addLog(record: DiagnosticLogRecord, throwable: Throwable?) = instance.addLog(record, throwable)
                override fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation): String? =
                    instance.captureHandled(marker, throwable, context)
                override fun newEventId(): String = instance.newEventId()
                override fun captureDiagnosticEvent(
                    eventId: String,
                    reportSessionId: String,
                    submitted: Boolean,
                    context: DiagnosticCorrelation,
                    attachments: List<DiagnosticAttachment>
                ): String = instance.captureDiagnosticEvent(eventId, reportSessionId, submitted, context, attachments)
                override fun showFeedback(associatedEventId: String?) = instance.showFeedback(associatedEventId)
                override fun flush(timeoutMillis: Long): Boolean = instance.flush(timeoutMillis)
            }
        )
        coordinator = manualCoordinator

        if (dsn.isBlank()) {
            val noOp = NoOpAndroidObservability
            instance = noOp
            AppLog.installSink { record, throwable ->
                instance.addLog(record, throwable)
            }
            return noOp
        }

        SentryAndroid.init(context) { options: SentryAndroidOptions ->
            options.dsn = dsn
            options.release = release
            options.environment = environment
            options.sampleRate = 1.0
            options.tracesSampleRate = 1.0
            options.sessionReplay.onErrorSampleRate = 1.0
            options.sessionReplay.sessionSampleRate = 0.0
            options.isAnrEnabled = true
            options.isSendDefaultPii = true
            options.isAttachScreenshot = true
            options.isAttachViewHierarchy = true
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                SentryEventSanitizer.sanitizeEvent(event)
            }
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                SentryEventSanitizer.sanitizeBreadcrumb(breadcrumb)
            }
            options.feedbackOptions.apply {
                isUseShakeGesture = true
                isUseSentryUser = false
                isShowName = false
                isShowEmail = false
                isShowBranding = false
                formTitle = "Report a bug"
                messageLabel = "What went wrong?"
                messagePlaceholder = "What did you expect, and what happened?"
                submitButtonLabel = "Send report"
                successMessageText = "Report sent. Thank you."
                onFormOpen = Runnable { coordinator?.onFormOpen() }
                onFormClose = Runnable { coordinator?.onFormClose() }
                onSubmitSuccess = io.sentry.SentryFeedbackOptions.SentryFeedbackCallback {
                    coordinator?.onSubmitSuccess(it)
                }
                onSubmitError = io.sentry.SentryFeedbackOptions.SentryFeedbackCallback {
                    coordinator?.onSubmitError(it)
                }
            }
        }

        val sentryObservability = SentryAndroidObservability()
        instance = sentryObservability
        AppLog.installSink { record, throwable ->
            instance.addLog(record, throwable)
        }
        return sentryObservability
    }

    fun showFeedback(associatedEventId: String? = null) {
        instance.showFeedback(associatedEventId)
    }

    internal fun resetForTests(newAdapter: AndroidObservability = NoOpAndroidObservability) {
        instance = newAdapter
        coordinator = null
        AppLog.installSink { record, throwable ->
            instance.addLog(record, throwable)
        }
    }
}
