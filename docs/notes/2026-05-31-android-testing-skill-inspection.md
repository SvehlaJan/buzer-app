# Android Testing Skill Inspection

Date: 2026-05-31

## Installed Skill

- Command: `android skills add testing-setup`
- Result: installed successfully.
- Installed paths:
  - `/Users/svehla/.codex/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.agents/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.claude/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.gemini/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.gemini/antigravity/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.cursor/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.copilot/skills/testing-setup/SKILL.md`
  - `/Users/svehla/.codeium/windsurf/skills/testing-setup/SKILL.md`

## Useful Guidance

- Analyze the existing project stack before adding any new test framework.
- Respect the current testing frameworks instead of replacing them.
- Prefer fakes for slow or problematic dependencies in UI and integration tests.
- Keep end-to-end tests low in number and focused on major journeys.
- Use UI Automator when tests need platform features, notifications, or system UI.
- Screenshot tests are useful for visual regression but should not be used as behavior tests.

## Not Useful For Buzer Yet

- Installing a dependency injection framework is premature for the current MVP test harness.
- Robolectric, Compose UI testing, screenshot plugins, Dropshots, and Jacoco are not needed for the first emulator workflow slice.
- Navigation, screenshot, and window-size test suites should wait until the core enforcement and fake-backend smoke path is reliable.

## Decisions

- The first Buzer emulator workflow remains host-driven with ADB and Android CLI.
- AndroidX UI Automator will be introduced only when raw Android CLI layout assertions become brittle.
- Fake backend tests are the right first use of fakes because they avoid live Todoist and WhatsApp side effects.
- The installed `testing-setup` skill is useful as a testing strategy checklist, not as a mandate to add frameworks immediately.
