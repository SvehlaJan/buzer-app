package app.buzer.android.observability

import android.content.Context
import app.buzer.android.BuildConfig
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticRedactor
import io.sentry.Breadcrumb
import io.sentry.IScope
import io.sentry.Sentry
import io.sentry.SentryAttribute
import io.sentry.SentryAttributes
import io.sentry.SentryEvent
import io.sentry.SentryFeedbackOptions
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.logger.SentryLogParameters
import io.sentry.protocol.Contexts

object SentryEventSanitizer {
    private val redactor = DiagnosticRedactor()

    fun sanitizeEvent(event: SentryEvent): SentryEvent {
        event.request = null
        event.user = null

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

    fun sanitizeLog(event: SentryLogEvent): SentryLogEvent {
        event.body = redactor.redactString(event.body)

        event.attributes?.toMap().orEmpty().forEach { (key, attribute) ->
            val value = if (DiagnosticRedactor.isCredentialKey(key)) {
                "[REDACTED]"
            } else {
                redactor.redactAny(attribute.value)
            }
            event.setAttribute(key, SentryLogEventAttributeValue(attribute.type, value))
        }
        return event
    }

}

class SentryAndroidObservability : AndroidObservability {
    override fun addLog(record: DiagnosticLogRecord) {
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
            record.attributes.forEach { (key, value) ->
                setData(key, value)
            }
        }
        Sentry.addBreadcrumb(breadcrumb)

        val structuredAttributes = buildList {
            record.attributes.forEach { (key, value) -> add(SentryAttribute.stringAttribute(key, value)) }
            record.throwableType?.let { add(SentryAttribute.stringAttribute("throwable_type", it)) }
            record.throwableMessage?.let { add(SentryAttribute.stringAttribute("throwable_message", it)) }
        }
        val logLevel = when (record.severity.lowercase()) {
            "error" -> SentryLogLevel.ERROR
            "warn", "warning" -> SentryLogLevel.WARN
            "debug" -> SentryLogLevel.DEBUG
            else -> SentryLogLevel.INFO
        }
        Sentry.logger().log(
            logLevel,
            SentryLogParameters.create(SentryAttributes.of(*structuredAttributes.toTypedArray())),
            record.message
        )
    }

    override fun captureHandled(
        marker: String,
        throwable: Throwable,
        context: DiagnosticCorrelation
    ) {
        val event = SentryEvent(throwable).apply {
            level = SentryLevel.ERROR
            setTag("marker", marker)
            context.deviceId?.takeIf { it.isNotBlank() }?.let { setTag("device_id", it) }
            context.taskId?.takeIf { it.isNotBlank() }?.let { setTag("task_id", it) }
            context.occurrenceKey?.takeIf { it.isNotBlank() }?.let { setTag("occurrence_key", it) }
            context.actionId?.takeIf { it.isNotBlank() }?.let { setTag("action_id", it) }
            context.requestId?.takeIf { it.isNotBlank() }?.let { setTag("request_id", it) }
            context.screen?.takeIf { it.isNotBlank() }?.let { setTag("screen", it) }
            context.blockerType?.takeIf { it.isNotBlank() }?.let { setTag("blocker_type", it) }
            context.appSection?.takeIf { it.isNotBlank() }?.let { setTag("app_section", it) }
        }
        Sentry.captureEvent(event)
    }

    override fun showFeedback() {
        Sentry.feedback().show()
    }

    override fun setScreenContext(context: ScreenContext?) {
        Sentry.configureScope { scope -> applyScreenContext(scope, context) }
    }

}

/**
 * Scope tags reach feedback reports, which carry no breadcrumbs, so a report names the screen
 * and edited task it was sent from.
 */
internal fun applyScreenContext(scope: IScope, context: ScreenContext?) {
    if (context == null) {
        scope.removeTag(SCREEN_TAG)
        scope.removeTag(TASK_ID_TAG)
        return
    }
    scope.setTag(SCREEN_TAG, context.screen)
    if (context.taskId != null) {
        scope.setTag(TASK_ID_TAG, context.taskId)
    } else {
        scope.removeTag(TASK_ID_TAG)
    }
}

private const val SCREEN_TAG = "screen"
private const val TASK_ID_TAG = "task_id"

object ObservabilityRuntime {
    @Volatile
    var instance: AndroidObservability = NoOpAndroidObservability
        internal set

    fun configureSentryOptions(
        options: SentryAndroidOptions,
        dsn: String,
        environment: String,
        release: String
    ) {
        options.dsn = dsn
        options.release = release
        options.environment = environment
        options.sampleRate = 1.0
        options.tracesSampleRate = null
        options.sessionReplay.onErrorSampleRate = 1.0
        options.sessionReplay.sessionSampleRate = 0.0
        options.isAnrEnabled = true
        options.isSendDefaultPii = false
        options.isAttachScreenshot = true
        options.isAttachViewHierarchy = true
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
            SentryEventSanitizer.sanitizeEvent(event)
        }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
            SentryEventSanitizer.sanitizeBreadcrumb(breadcrumb)
        }
        options.logs.isEnabled = true
        options.logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback { event ->
            SentryEventSanitizer.sanitizeLog(event)
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
            onFormOpen = Runnable { AppLog.info("bug_report_form_opened") }
            onSubmitSuccess = SentryFeedbackOptions.SentryFeedbackCallback {
                AppLog.info("bug_report_submitted")
            }
        }
    }

    fun initialize(
        context: Context,
        dsn: String = BuildConfig.SENTRY_ANDROID_DSN,
        environment: String = BuildConfig.SENTRY_ENVIRONMENT,
        release: String = BuildConfig.SENTRY_RELEASE
    ): AndroidObservability {
        if (dsn.isBlank()) {
            val noOp = NoOpAndroidObservability
            instance = noOp
            AppLog.installSink { record ->
                instance.addLog(record)
            }
            return noOp
        }

        SentryAndroid.init(context) { options: SentryAndroidOptions ->
            configureSentryOptions(options, dsn, environment, release)
        }

        val sentryObservability = SentryAndroidObservability()
        instance = sentryObservability
        AppLog.installSink { record ->
            instance.addLog(record)
        }
        return sentryObservability
    }

    internal fun resetForTests(newAdapter: AndroidObservability = NoOpAndroidObservability) {
        instance = newAdapter
        AppLog.installSink { record ->
            instance.addLog(record)
        }
    }
}
