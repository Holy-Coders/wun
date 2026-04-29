# wun-android

Phase 0 stub of the Wun Android client. Intentionally empty -- no Gradle
scaffolding yet, since the AGP / SDK setup is non-trivial and the spike
doesn't exercise it.

The real library lands in **phase 3** of the project brief (see the
[top-level README](../README.md)) and will mirror the iOS architecture:

- OkHttp SSE client
- Tree mirror + patch applicator
- Open `WunComponentRegistry` interface
- Jetpack Compose renderers for the foundational vocabulary
- Action dispatcher
- Hotwire Native Android for WebFrame fallback at component granularity

User-defined components are first-class: a separate Gradle artifact can
register additional renderers against the same registry.
