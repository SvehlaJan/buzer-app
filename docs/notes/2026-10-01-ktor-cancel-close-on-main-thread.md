# Ktor 3.5.1+ Closes Android-Engine Responses On The Cancelling Thread

Date: 2026-10-01

## Finding

Ktor 3.5.1 (KTOR-9629, "Close RawSourceChannel when Job is canceled") added this to
`io.ktor.utils.io.jvm.javaio.RawSourceChannel`:

```kotlin
job.invokeOnCompletion(onCancelling = true) { cause ->
    if (cause != null) closeSource(cause.asCancellationException())
}
```

The handler runs synchronously on whichever thread cancels the request job. Compose cancels a
`rememberCoroutineScope` on the main thread when the composable leaves composition, so an
in-flight request made from such a scope (for example the task editor's project load) gets its
response stream closed on main. With `ktor-client-android` (HttpURLConnection, Android's bundled
OkHttp) closing a partly read body drains it from the socket (`Util.discard`), and StrictMode
throws `NetworkOnMainThreadException`. The exception escapes the completion handler as
`CompletionHandlerException` out of `WrappedComposition.dispose` on the main thread. It failed
the instrumentation tests below; in a normal app run the same uncaught main-thread exception
would crash the app (not observed on a device, since 3.5.1+ never shipped to one).

Observed on 2026-10-01 during the dependency upgrade: the full `scripts/e2e-real-backend.sh` run
on Ktor 3.6.0 failed 3 of 121 tests, all with this trace:

- `AddTaskFakeBackendTest.addTask_optionalDetailsAreSubmittedAsDescription`
- `AddTaskFakeBackendTest.addTask_sharedProjectSelectsMeByDefaultAndCreatesTask`
- `EditTaskFakeBackendTest.editTask_projectAndAssigneeStayDraftOnlyUntilOneFullRootUpdate`

Ktor fixed the same pattern for the OkHttp engine only (KTOR-9773, GitHub ktorio/ktor#5793:
"Close the OkHttp response body off the cancelling thread"). Verified by diffing
`ktor-io/jvm/src/io/ktor/utils/io/jvm/javaio/Reading.kt`: 3.5.0 has no cancel-time close; 3.5.1,
3.5.2 and 3.6.0 all have it.

## Decision

Hold Ktor at 3.5.0 (`client/gradle/libs.versions.toml`) and keep the Android engine.

## Revisit When

- A Ktor release moves `RawSourceChannel`'s cancel-time close off the cancelling thread (or the
  Android engine dispatches it), or
- the client switches to the OkHttp engine, which has the KTOR-9773 fix.

Re-run the full `scripts/e2e-real-backend.sh` suite after any Ktor bump; the three tests above
reproduce the crash.
