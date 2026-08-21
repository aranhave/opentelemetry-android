# Native Crash Instrumentation

Status: development

The native crash instrumentation records fatal native signals and replays the persisted crash as an
`app.crash` event when the application next starts.

It uses one marker for the most recent crash and a separate context snapshot maintained while the
app is running. The signal handler records `SIGILL`, `SIGTRAP`, `SIGABRT`, `SIGBUS`, `SIGFPE`,
`SIGSEGV`, and `SIGSYS`, then restores the previous action for that signal and re-delivers it
through the kernel. This preserves the previous handler's signal mask, flags, and available fault
details without changing registrations for the other signals. Signals that were already ignored
remain ignored.

## Persisted marker format

The native handler writes the marker as UTF-8 text with a trailing newline:

```properties
signal.number=<positive integer>
timestamp.epoch_nanos=<positive integer>
```

The native writer and Kotlin reader must keep these keys and value formats in sync.

## Native stack snapshot

The signal handler also writes a fixed-size, versioned binary snapshot. It contains the signal and
timestamp, architecture-specific registers, up to 4 KiB of stack memory, and up to 128 executable
module segments. Module names, address ranges, and available ELF build IDs are prepared during
instrumentation installation, before the signal handlers are enabled. App-owned modules are
prioritized when the table is full. The exact binary contract and reader rules are documented in
[`SNAPSHOT_FORMAT.md`](SNAPSHOT_FORMAT.md).

The crash path uses static storage and bounded operations. It does not allocate memory, acquire
locks, call JNI, log, or inspect process maps. If the snapshot cannot be captured or persisted, the
existing text marker is still written so the crash event can be replayed without a native stack.

On the next launch, the reader validates the snapshot's size, magic, version, checksum,
architecture, bounds, and marker identity before recovering frames. It skips malformed module
entries and discards structurally corrupt or stale snapshots. Only confirmed program-counter,
link-register, and frame-pointer frames are emitted as module-relative addresses in
`exception.stacktrace`.

This is the smallest production slice selected after evaluating in-handler unwinding and minidump
capture. `libunwindstack` is not part of the public Android NDK, and its unwind path allocates and
uses locks. Crashpad provides a more complete out-of-process minidump design, but adds substantially
more build and runtime integration. Capture is kept separate from offline recovery so a CFI-aware
unwinder or Crashpad can be added later without changing the crash marker contract.

## Telemetry

The replayed event uses the original crash timestamp and includes:

* `exception.type`
* `exception.message`
* `exception.stacktrace`, when a valid native snapshot produces confirmed frames
* `session.id`, when available
* `service.version`, when available
* `os.name`
* `os.version`

The app and OS fields are read from the persisted crash-time context before it is replaced with the
new process context, so the replayed event describes the process that crashed.

## Installation

Building the native library requires CMake 3.22.1 or newer.

Add the instrumentation dependency:

```kotlin
implementation("io.opentelemetry.android.instrumentation:native-crash:1.6.0-alpha")
```

The module is discovered and installed automatically when it is present on the runtime classpath.
It replays any marker from the previous process and persists the current process context before
enabling the native signal handler.

## Limitations

Native frames contain module-relative addresses and available ELF build IDs, not source locations
or function names. Symbol upload and backend symbolication remain separate work tracked in
[#1089](https://github.com/open-telemetry/opentelemetry-android/issues/1089).

The offline recovery is best effort and does not interpret DWARF or compact unwind metadata. ARM64,
x86, and x86_64 use a bounded frame-pointer walk. ARM32 reports the program counter and link register
because its common frame layouts are not reliable enough for the generic walk.

The snapshot is limited to 128 executable segments and 4 KiB of stack memory. Modules loaded after
instrumentation installation are not present in the prepared table, so recovery may produce a
partial stack or no native frames while the crash event itself is still reported.

Crashes that happen before native crash instrumentation finishes initialization are not recorded.

Temporary marker or snapshot read failures retain the recovery files for the next launch. While a
retry is pending, the saved crash context is left intact and the signal handler remains disabled so
a new crash cannot overwrite it. Snapshot recovery falls back to the valid marker-only crash after
three failed attempts. Other exhausted recovery failures perform best-effort cleanup and allow the
handler to be installed again. Malformed data is discarded because retrying it cannot produce a
valid crash report.

Before emitting the replayed event, recovery durably records a delivery claim. If that claim cannot
be persisted, no event is emitted and the crash is abandoned with best-effort cleanup. Once delivery
is claimed, later recovery attempts only clean up the marker, snapshot, and retry state; they never
emit the event again. Snapshot cleanup is attempted even when marker deletion fails, while delivery
state is retained until the marker is gone. This is not an exporter acknowledgment: if the
application exits before telemetry is exported, the event may still be lost. A later change may add
support for preserving multiple consecutive startup crashes.

Debug and release builds compile the native handler for ARM32, ARM64, x86, and x86_64. The required
instrumented-test matrix runs the native handler on x86 at API 23 and x86_64 at API 36. ARM-specific
register capture, Thumb-address normalization, and tagged-address recovery still require validation
on ARM devices before this feature is considered production-ready.
