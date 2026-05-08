# KMP Version Selection

Date: 2026-05-08
Status: Initial Android shell selection

## Documentation Sources

- Context7 resolved Kotlin Multiplatform docs to `/jetbrains/kotlin-multiplatform-dev-docs`.
- Context7 resolved Ktor docs to `/ktorio/ktor-documentation`.

## Selected Versions

- Gradle wrapper: 8.14.3
- Android Gradle Plugin: 8.7.0
- Kotlin: 2.3.0
- Compose Multiplatform: 1.7.3
- AndroidX Activity Compose: 1.9.3
- Ktor Client: 3.4.1
- Kotlinx Serialization JSON: 1.10.0
- Kotlinx Datetime: 0.7.1

## Notes

- KMP docs showed the current source-set dependency shape and current library coordinates for `commonMain`, `commonTest`, and Android-specific Ktor engines.
- Ktor docs showed `ContentNegotiation`, kotlinx JSON serialization, typed `body()` deserialization, JSON `setBody`, and bearer-token request headers.
- Kotlin was bumped to 2.3.0 after verification showed Ktor 3.4.1 and Kotlinx Serialization 1.10.0 carry Kotlin 2.3 metadata.
- AGP and Compose versions were selected conservatively from locally cached/current-compatible versions to keep the Android shell buildable in this worktree while using the Ktor and kotlinx versions surfaced by Context7.
