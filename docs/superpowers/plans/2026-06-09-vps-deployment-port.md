# VPS Manual Deployment Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the useful deployment work from the stale `codex/vps-deployment` branch onto current `master` for manual MVP deployment on the Tailscale-only VPS.

**Architecture:** The backend remains deployed from `/home/skclaw/buzer-app` using the root `Dockerfile` and root `docker-compose.yml`. Manual deploy uses `scripts/deploy-vps.sh` over Tailscale SSH, resets the VPS checkout to `origin/master`, rebuilds the container, and waits for `/health`. Runtime data remains in existing Docker named volumes.

**Tech Stack:** Node.js 22, Fastify, SQLite, Docker, Docker Compose, Tailscale SSH.

---

## Scope

- Add production backend Docker image support.
- Add Docker Compose deployment support for the current VPS.
- Add a public unauthenticated health endpoint suitable for deploy checks.
- Preserve exact backend dependency versions from current `master`.
- Preserve the current deployed host port `18432`.
- Preserve existing Docker named volumes for SQLite and WhatsApp sessions.
- Document the VPS runbook and backup flow.
- Do not add an active GitHub Actions workflow for MVP.

## Tasks

- [x] Review the stale VPS branch and current `master`.
- [x] Verify the deployed VPS health endpoint behavior.
- [x] Add failing route test for unauthenticated `/health`.
- [x] Implement `/health` with a SQLite check and auth bypass.
- [x] Update root Dockerfile and `.dockerignore`.
- [x] Update root Compose deployment, deploy script, and backup script.
- [x] Update backend build output to copy `schema.sql` into `dist/db/`.
- [x] Update VPS deployment notes for `/home/skclaw/buzer-app`, Tailscale hostname `oc-vps.taila50381.ts.net`, and host port `18432`.
- [x] Add future GitHub Actions over Tailscale note without adding an active workflow.
- [ ] Run backend verification.
- [ ] Run Docker/Compose verification where available.
- [ ] Deploy manually on the VPS.
- [ ] Write final handoff.
- [ ] Commit the deployment port.

## Notes

The current deployed container responds on port `18432`, but `/health` returns `401` because the deployed code still protects it with bearer auth. The ported code intentionally makes `/health` unauthenticated and checks SQLite with `select 1`.
