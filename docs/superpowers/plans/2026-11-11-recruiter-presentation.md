# Recruiter presentation implementation plan

> **For agentic workers:** Execute inline with the approved interview/design as the
> authority; request an independent read-only review before integration. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Buzer's ownership and engineering decisions easy for hiring readers to assess.

**Architecture:** Documentation and GitHub metadata only. Use the existing E2E backend
and a temporary Android emulator session for current screenshots; application code is
unchanged.

**Tech Stack:** GitHub Markdown/Mermaid, existing Android debug build, existing TypeScript E2E server.

## Task 1: Workspace and source evidence

- [x] Read AGENTS.md, approved product spec, recent handoffs, and workspace convention.
- [x] Inspect status/worktrees and create `.worktrees/recruiter-presentation` on
  `feature/recruiter-presentation`; preserve the existing primary-checkout review note.
- [x] Record the approved interview as the design above. Inspect current build/config,
  decisions, fixture server, and screenshot tooling before documenting commands.

## Task 2: Current app captures

Files: `docs/images/home.png`, `task-editor.png`, `start-blocker.png`, `README.md`.

- [x] Start the existing API 36 emulator in read-only mode and local E2E server.
- [x] Build/install the unchanged APK with fixture URL/token/assignee and Sentry disabled.
- [x] Capture and visually inspect Home, task editor, and Start blocker; record exact provenance.

## Task 3: Reader-facing documentation

Files: root `README.md`, `docs/case-studies/simplifying-mutations.md`,
`docs/notes/local-development.md`.

- [x] Write the approved introduction, product outcome, ownership, AI workflow including
  Matt Pocock and earlier Superpowers use, architecture, testing, development, and roadmap.
- [x] Write the mutation case study with source/code/test links, explicit costs, and
  supporting observability/scope examples; avoid unverified metrics and outcomes.
- [x] Replace removed harness instructions and stale toolchain prose in local-development notes.

## Task 4: Verification and integration

- [x] Validate relative Markdown/HTML links and images, whitespace, and public claims;
  render the README and inspect screenshots and diagram.
- [x] Obtain independent read-only review and resolve actionable findings.
- [x] Apply approved GitHub description/topics and verify remote values.
- [ ] Write delivery handoff, record skipped app suites, and commit only scoped changes.
- [ ] Integrate the reviewed documentation and record publication/CI status.

No existing application behavior or test expectations change. There are no unit/UI
tests to migrate; screenshot capture builds do not constitute a full suite run.
