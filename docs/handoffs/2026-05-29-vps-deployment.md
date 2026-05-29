# Handoff: VPS Deployment Preparation

Date: 2026-05-29
Agent: Codex GPT-5
Plan/task attempted: user-requested VPS deployment preparation and GitHub push-to-deploy setup.

## Scope

- Prepared the backend for deployment from a fresh clone on a VPS.
- Added a production Docker image, Docker Compose service, Caddy example, deployment scripts, backup script, GitHub Actions workflow, and VPS runbook.
- Added unauthenticated backend health check for reverse proxy and deployment verification.

## Files Changed

- `.github/workflows/deploy-vps.yml`
- `backend/.dockerignore`
- `backend/Dockerfile`
- `backend/package.json`
- `backend/src/http/routes.ts`
- `backend/test/http/routes.test.ts`
- `backend/tsconfig.build.json`
- `deploy/Caddyfile.example`
- `deploy/docker-compose.yml`
- `docs/handoffs/2026-05-29-vps-deployment.md`
- `docs/notes/vps-deployment.md`
- `scripts/backup-sqlite.sh`
- `scripts/deploy-vps.sh`

## Commands Run

- `git status --short`
- `git branch --show-current`
- `git log --oneline -5`
- `git worktree add .worktrees/vps-deployment -b codex/vps-deployment`
- `npm install`
- `npm test`
- `npx ctx7@latest library "Docker Compose" "Deploy Node.js TypeScript service with Docker Compose persistent SQLite volume reverse proxy systemd VPS"`
- `npx ctx7@latest docs "/docker/compose" "Deploy Node.js TypeScript service with Docker Compose persistent SQLite volume reverse proxy systemd VPS"`
- `npx ctx7@latest library "GitHub Webhooks" "Verify webhook signatures X-Hub-Signature-256 push event redeploy VPS endpoint"`
- `npx ctx7@latest docs "/octokit/webhooks" "Verify webhook signatures X-Hub-Signature-256 push event redeploy VPS endpoint"`
- `npx ctx7@latest library "Caddy" "Caddyfile reverse proxy HTTPS to local Docker Compose service"`
- `npx ctx7@latest docs "/websites/caddyserver_caddyfile" "Caddyfile reverse proxy HTTPS to local Docker Compose service"`
- `npx ctx7@latest library "GitHub Actions" "Deploy to VPS over SSH on push workflow secrets environment variables"`
- `npx ctx7@latest docs "/websites/github_en_actions" "Deploy to VPS over SSH on push workflow secrets environment variables"`
- `npx ctx7@latest library "Puppeteer" "Docker Node image install system Chromium PUPPETEER_EXECUTABLE_PATH skip browser download"`
- `npx ctx7@latest docs "/puppeteer/puppeteer" "Docker Node image install system Chromium PUPPETEER_EXECUTABLE_PATH skip browser download"`
- `npm test -- test/http/routes.test.ts`
- `chmod +x scripts/deploy-vps.sh scripts/backup-sqlite.sh`
- `npm run typecheck`
- `npm run build`
- `ruby -e "require 'yaml'; YAML.load_file('deploy/docker-compose.yml'); YAML.load_file('.github/workflows/deploy-vps.yml'); puts 'yaml ok'"`
- `node -e "import('./dist/server.js').then(() => console.log('server module import ok'))"`
- `bash -n scripts/deploy-vps.sh`
- `bash -n scripts/backup-sqlite.sh`
- `git diff --check`
- `docker compose -f deploy/docker-compose.yml config`

## Verification

- Command: `npm test`
- Result: PASS before changes
- Evidence: 13 test files passed with 35 tests after installing worktree dependencies.

- Command: `npm test -- test/http/routes.test.ts`
- Result: FAIL then PASS
- Evidence: new health test initially failed with 401 because `/health` was protected by the bearer hook; after explicitly bypassing auth for `/health`, 6 route tests passed.

- Command: `npm test`
- Result: PASS after changes
- Evidence: 13 test files passed with 36 tests.

- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited with status 0.

- Command: `npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json && cp src/db/schema.sql dist/db/schema.sql` exited with status 0.

- Command: `ls backend/dist/db/migrations.js backend/dist/db/schema.sql backend/dist/server.js`
- Result: PASS
- Evidence: all three built runtime files were present.

- Command: `ruby -e "require 'yaml'; YAML.load_file('deploy/docker-compose.yml'); YAML.load_file('.github/workflows/deploy-vps.yml'); puts 'yaml ok'"`
- Result: PASS
- Evidence: printed `yaml ok`.

- Command: `node -e "import('./dist/server.js').then(() => console.log('server module import ok'))"`
- Result: PASS
- Evidence: printed `server module import ok`.

- Command: `bash -n scripts/deploy-vps.sh`
- Result: PASS
- Evidence: exited with status 0.

- Command: `bash -n scripts/backup-sqlite.sh`
- Result: PASS
- Evidence: exited with status 0.

- Command: `git diff --check`
- Result: PASS
- Evidence: exited with status 0.

- Command: `docker compose -f deploy/docker-compose.yml config`
- Result: SKIPPED
- Evidence: local environment does not have a `docker` command.

## Manual Verification

- Reviewed deployment scripts for secret handling and destructive operations.
- GitHub Actions uses SSH with a required `VPS_KNOWN_HOSTS` secret instead of ad hoc host trust.
- Deploy script uses `git pull --ff-only` rather than hard reset, so unexpected local VPS changes fail deployment instead of being silently discarded.

## Known Failures Or Skipped Checks

- Docker image build was not run because the local environment does not have Docker installed.
- No live VPS deploy was performed.
- No live GitHub Actions run was performed.
- No real WhatsApp QR login or message send was performed.

## Assumptions

- Production branch is currently `master`.
- The first VPS deployment will use `/srv/buzer/repo`, `/srv/buzer/data`, and `/srv/buzer/.env`.
- The backend should bind publicly only through Caddy; Docker publishes the service on `127.0.0.1:3001`.
- GitHub Actions push-to-deploy is preferred over an inbound GitHub webhook daemon for the first service.

## Next Recommended Step

- Run local verification for Docker/Compose syntax and backend tests.
- Commit this deployment slice.
- On the VPS, follow `docs/notes/vps-deployment.md` with Claude Code and deploy first with `WHATSAPP_DRY_RUN=true`.
