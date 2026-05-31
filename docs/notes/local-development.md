# Local Development Notes

Date: 2026-05-08

## Backend

The backend lives in `backend/`. It uses SQLite for MVP. Runtime databases and session folders are ignored by Git.

Expected local commands after backend setup:

```bash
cd backend
npm install
npm test
npm run dev
```

## Android Client

The client lives in `client/`. It is a Kotlin Multiplatform / Compose Multiplatform project with Android-native enforcement code.

Expected local commands after client setup:

```bash
cd client
./gradlew test
./gradlew :androidApp:assembleDebug
```

## Android Debug Backend Configuration

The Android debug app reads VPS backend settings from ignored `client/local.properties`:

```properties
BUZER_BACKEND_BASE_URL=https://your-vps.example
BUZER_BACKEND_TOKEN=your-dev-token
```

Do not commit real values. If either value is blank, the Android app shows a local configuration error instead of calling the backend.

## Secrets

Secrets stay out of Git. Use `.env.example` files for documented variable names only.

## Handoffs

Each implementation session writes a handoff in `docs/handoffs/` before stopping.
