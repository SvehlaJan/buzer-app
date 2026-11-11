# Recruiter presentation design

Date: 2026-11-11
Status: approved by the owner in the repository-presentation interview.

## Audience and story

Target AI-powered software builder and general senior software engineer roles. Show
product ownership, specification authorship/review, architectural judgment, and
leadership of AI implementation. Use the owner's approved witty Todoist introduction
and qualitative outcome: easier task capture and finishing work by blocking time.

## Deliverables

- A reader-facing README with current app screenshots, ownership, AI workflow,
  selected engineering evidence, architecture, verification, setup, and roadmap.
- A focused case study on removing durable mutation reconciliation. Explain the
  original motivation, failure, decision, retained safeguards, and accepted costs.
  Link native-Sentry simplification and Home scope control as supporting examples.
- Three current Android screenshots using only the local E2E backend and fixture data.
  Record capture provenance; do not substitute old design mockups for current output.
- Repair stale local-development instructions reached from the README.
- Set GitHub description and relevant topics. No invented homepage or license.

## Claims and boundaries

AI performed implementation; the owner authored/reviewed specs, owned tradeoffs, and
initiated both mutation and Sentry simplification. Describe Linear idea capture,
agent research, requirements interviews, and reviewed PRD/technical specifications as
the owner's workflow. Mention enjoyment of Matt Pocock's skills and framework, and
earlier use of Superpowers. Voice input remains roadmap work.

The shipped client targets Android. This is a personal single-user tool, not a
multi-user service. Do not claim measured productivity gains or net code reduction
for the mutation redesign. Do not change application behavior or contact real
Todoist/WhatsApp for documentation captures.

## Verification

Check local links/images, render the README with Mermaid support, inspect all three
screenshots, and confirm metadata remotely. Build a fixture-configured APK for
captures. Existing backend/JVM/instrumentation behavior is unchanged; no new tests
or application-suite rerun is required solely for documentation edits. Record exact
commands, limitations, and final branch/publication state in the delivery handoff.
