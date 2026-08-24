# Sentry Structured Logs Design

Date: 2026-08-24

Status: Approved in conversation on 2026-08-24.

Extends: `docs/superpowers/specs/2026-08-22-lean-observability-design.md`

## Goal

Make recent Android application logs searchable beside native Sentry feedback without
adding report-session state, diagnostic events, attachment assembly, or event-ID
correlation.

## Design

Keep Sentry's native shake gesture and feedback form unchanged. Enable Sentry's
structured Logs product in the existing Android SDK configuration and have the existing
`AppLog` sink send each record to both destinations:

- a breadcrumb, for context attached automatically to crashes and handled events;
- a structured Sentry log, for independent search around a feedback submission.

The feedback callbacks emit stable `bug_report_form_opened` and
`bug_report_submitted` log messages. A report is investigated by its timestamp,
environment, release, replay, and nearby logs. Logs are not expected to appear inline
inside the feedback issue and no attempt is made to associate them with an event ID.

## Safety And Failure Behavior

Structured logs are sanitized before transport with the existing Kotlin diagnostic
redactor. Logging remains best-effort and may not affect feedback, UI, enforcement, or
other product behavior. A blank DSN continues to disable the Sentry sink while Kermit and
Logcat remain available.

## Testing

Sentry-specific logic is intentionally low priority for this one-user application. Add
only configuration-level assertions that structured logs and the two feedback callbacks
are enabled, then run the existing Android JVM/build checks. Live usefulness is verified
by submitting a physical-device report and finding the marker and surrounding records in
Sentry Logs.

## Explicit Non-Goals

- No custom feedback UI.
- No event-ID reservation or association.
- No manual diagnostic event.
- No report coordinator, local log buffer, attachment, or backend report endpoint.
- No automated test transport or mock Sentry server.
