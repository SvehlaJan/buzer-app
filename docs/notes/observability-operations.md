# Observability Operations

Date: 2026-08-21

This runbook records external Sentry/GitHub/VPS configuration for the approved
observability implementation. Values that are safe to commit are recorded here. DSNs,
auth tokens, API tokens, and other credentials must remain in their designated secret
stores and must never be added to this file.

## Hosted Sentry Inventory

Provisioned by the repository owner on 2026-08-21:

| Resource | Slug | Project ID | Platform |
| --- | --- | ---: | --- |
| Organization | `jans-gi` | — | Hosted Sentry |
| Android project | `buzer-android` | `4511950606172240` | Android |
| Backend project | `buzer-backend` | `4511950617641040` | Node.js / Fastify |

The backend remains a Node.js Sentry project even though the application framework is
Fastify. The implementation must verify Fastify 5.8.5 behavior against the selected
Sentry Node SDK rather than assuming framework auto-instrumentation is complete.

Canonical runtime environments:

- Android local/canary: `development-device`
- Android production: `production-device`
- Backend local: `development-backend`
- Backend VPS: `production-backend`

Canonical release identity for both projects:

```text
app.buzer@${VERSION_NAME}+${GIT_SHA_12}
```

## External Setup Checklist

- [x] Create/select hosted Sentry organization.
  - Operator: repository owner
  - Date: 2026-08-21
  - Evidence: organization slug `jans-gi`
- [x] Create `buzer-android` and `buzer-backend` projects.
  - Operator: repository owner
  - Date: 2026-08-21
  - Evidence: project IDs recorded in the inventory above
- [ ] Store the Android DSN in ignored `client/local.properties` as
  `SENTRY_ANDROID_DSN`; never commit its value.
- [ ] Store the backend DSN in the VPS `.env` as `SENTRY_DSN`; never commit its value.
- [ ] Create a least-privilege source-map/release auth token after the upload command and
  required scopes are finalized.
- [ ] Create a separate least-privilege triage API token after the triage script and
  required read scopes are finalized.
- [ ] Configure GitHub repository secrets/variables using the exact names in the final
  workflow. Record names and locations here, never values.
- [ ] Install Sentry's GitHub repository integration for commit/release correlation.
  Do not enable automatic Sentry-to-GitHub issue creation; the repository's deduplicated
  triage job owns issue creation.
- [ ] Configure immediate Android fatal, ANR, enforcement-critical, data-consistency, and
  manual-feedback alerts after canary event tags/grouping are verified.
- [ ] Configure Todoist poll alerts for three consecutive failures or 15 minutes without
  a success after canary event tags/grouping are verified.
- [ ] Configure immediate WhatsApp send-failure and five-minute disconnection alerts
  after canary event tags/grouping are verified.
- [ ] Exclude `feedback_submitted=false` diagnostic events from paging.
- [ ] Configure notification destination, ownership/routing, retention, quota, and
  discarded-event monitoring.
- [ ] Run the manual triage workflow twice and verify the second run updates the existing
  GitHub issue instead of creating a duplicate.
- [ ] Deploy backend Sentry environment changes and source maps.
- [ ] Install the canary Android build on the physical target device.
- [ ] Complete and record every physical-device/VPS canary in Task 13 of
  `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`.

## Secret Locations By Name

These are location contracts, not confirmation that a value has been installed:

| Value | Location | Commit? |
| --- | --- | --- |
| Android DSN | ignored `client/local.properties` / `SENTRY_ANDROID_DSN` | Never |
| Backend DSN | VPS `.env` / `SENTRY_DSN` | Never |
| Source-map token | operator or CI secret store / `SENTRY_AUTH_TOKEN` | Never |
| Triage token | GitHub Actions secret store / final workflow secret name | Never |
| Organization slug | GitHub Actions variable / `SENTRY_ORG` | Safe |
| Android project ID | GitHub Actions variable / `SENTRY_ANDROID_PROJECT_ID` | Safe |
| Backend project ID | GitHub Actions variable / `SENTRY_BACKEND_PROJECT_ID` | Safe |

`SENTRY_RELEASE` is generated from the version and Git SHA. Do not maintain it manually.
