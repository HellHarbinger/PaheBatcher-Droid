# AGENTS.md — PaheBatcher Android Development Rules

## 1. Mission

Build a production-grade native Android implementation of PaheBatcher according to `SPEC.md`.

The goal is:

> PaheBatcher behavior + Android-native implementation + modern Android UX

This is not:

> a website wrapper + download button

---

# 2. Mandatory workflow

Never attempt to implement the entire application in one operation.

For every task:

1. Read `SPEC.md`.
2. Read the relevant existing source code.
3. Inspect tests related to the requested functionality.
4. Determine existing behavior.
5. Design the Android equivalent.
6. Implement the smallest complete increment.
7. Build.
8. Run relevant tests.
9. Fix failures.
10. Re-run tests.
11. Update documentation.
12. Stop.

Do not continue into unrelated phases unless explicitly instructed.

---

# 3. Repository-first development

The Python PaheBatcher repository is the behavioral reference.

Never assume how the Python application works.

Inspect the actual implementation.

Before porting a subsystem, identify:

* entry points
* inputs
* outputs
* network requests
* parsing behavior
* error behavior
* retry behavior
* caching
* persistence
* concurrency
* lifecycle assumptions
* tests

Prefer reproducing behavior over rewriting the architecture based on assumptions.

---

# 4. Android architecture

Keep the following separation:

UI
↓
ViewModel
↓
Use Case
↓
Repository
↓
Domain implementation
↓
Network / Database / Storage / Download Engine / Player

UI components must not directly perform:

* HTTP requests
* HLS parsing
* downloads
* database writes
* Cloudflare handling
* media assembly

---

# 5. Production code rules

Production code must not contain:

* fake responses
* fake URLs
* sample anime data
* placeholder downloads
* fake progress
* fake notifications
* TODO implementations
* empty service methods
* disabled functionality disguised as completed functionality

Mocks are allowed in tests.

Mocks must never accidentally become production implementations.

---

# 6. Phase discipline

Only implement the phase explicitly requested.

Do not build the complete UI before the underlying functionality exists.

Do not implement later functionality merely because it is convenient during the current phase.

If a later dependency must be introduced, create only the required interface or minimal infrastructure.

---

# 7. Build verification

After meaningful code changes:

Run the appropriate Gradle build.

Run relevant unit tests.

Run lint/static analysis where appropriate.

Fix actual failures.

Do not suppress errors merely to obtain a successful build.

Never claim that functionality works unless it has been verified.

---

# 8. Testing

Every non-trivial subsystem must have tests.

Prefer testing:

* domain logic
* parsing
* state transitions
* persistence
* retry behavior
* concurrency boundaries
* filesystem behavior

For download functionality, explicitly test process interruption and resume.

---

# 9. Persistent state

UI state is not authoritative.

Persistent download state must live in the database.

The application must be able to reconstruct download state after:

* Activity recreation
* configuration change
* process death
* app restart
* network interruption
* device reboot where Android permits continuation

---

# 10. Network behavior

Never assume URLs are permanent.

Never persist expiring stream URLs indefinitely.

Never log:

* cookies
* authorization headers
* session tokens
* credentials

Retry only appropriate transient failures.

Respect bounded concurrency.

Avoid unnecessary requests.

---

# 11. Cloudflare/session handling

Do not fake Cloudflare success.

Do not invent cookies.

Do not hard-code a fake User-Agent.

Keep session handling behind an explicit abstraction.

The WebView session and native HTTP session must have clearly defined synchronization behavior.

---

# 12. Download engine

The download engine must be independently testable.

Maintain the distinction between:

* concurrent episodes
* concurrent HLS segments per episode

Use bounded concurrency.

Never create unbounded coroutines or threads.

Never load an entire episode into memory.

Segment writes must be atomic.

Incomplete segments must never be considered completed.

---

# 13. Android lifecycle

Never assume an Activity or process remains alive.

Long-running operations must use appropriate Android mechanisms.

Use:

* WorkManager
* foreground services where required
* persistent database state
* lifecycle-aware observation

Avoid unnecessary:

* polling
* wake locks
* WebView lifetime
* background services

---

# 14. Documentation

Maintain:

`PARITY.md`

* feature-by-feature Python → Android status.

`DECISIONS.md`

* significant architectural decisions.

`LIMITATIONS.md`

* genuine Android limitations or unresolved technical differences.

`ROADMAP.md`

* phase progress.

Do not claim 100% parity unless it has actually been verified.

---

# 15. When encountering ambiguity

Do not guess when the repository can answer the question.

Search the source.

Search the tests.

Trace the call flow.

Only then make an implementation decision.

For significant decisions, document the reasoning.

---

# 16. When encountering a blocker

Do not replace the blocked implementation with a fake.

Instead:

1. Explain the blocker.
2. Identify the affected functionality.
3. Identify possible Android-native alternatives.
4. Implement the closest verified alternative if appropriate.
5. Document the difference.

---

# 17. Dependency and toolchain verification

Before introducing or upgrading any infrastructure dependency (Gradle, AGP, Kotlin, AndroidX, Compose, Material 3, OkHttp, Room, Media3, or any other library):

1. Identify the currently installed versions of JDK, Gradle, AGP, and Kotlin.
2. Verify the candidate dependency version is compatible with all of the following:
   * JDK 25
   * compileSdk = 37
   * targetSdk = 37
   * minSdk = 32
   * The current Gradle version
   * The current AGP version
   * The current Kotlin version
3. Verify the candidate is the latest **stable** release of its line.
4. Do not select a version that requires downgrading any existing infrastructure component.
5. Do not select an alpha/beta/RC version unless no stable release supports the required toolchain.
6. If a compatibility conflict is found, document it in `DECISIONS.md` with the resolution.

Do not upgrade a dependency merely because a newer version exists. Only upgrade when there is a functional reason or when the current version is unsupported.

---

# 18. Code quality

Prefer:

* simple Kotlin
* clear interfaces
* immutable state where practical
* structured concurrency
* lifecycle-aware components
* testable classes
* bounded resource usage

Avoid unnecessary abstractions.

Do not introduce frameworks merely for architectural fashion.

---

# 19. Final rule

The agent must optimize for a functioning application, not for the appearance of completion.

A smaller verified implementation is preferable to a large implementation containing unverified functionality.

---

# 20. APK deployment rule

After every successful Android debug build that produces an installable APK:

1. Run `adb devices` and enumerate every device whose state is `device`.
2. If multiple devices are connected, do NOT install to only one.
3. Install the latest debug APK to EACH connected device using its serial: `adb -s <serial> install -r`.
4. Do not use an ambiguous `adb install` command when multiple devices are connected.
5. If a device is offline/unauthorized, report it separately and never claim deployment succeeded for it.
6. If no ADB device is connected, report that installation could not be performed.
7. After installation, verify the package is installed on every target device.
8. Do not uninstall the existing app unless required.
9. Do not reset application data.
