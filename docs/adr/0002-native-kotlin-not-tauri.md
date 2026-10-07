---
status: accepted
---

# Native Kotlin with a KMP-ready core, not Tauri

The app will be built as a native Android app in Kotlin, with all audio
concerns (capture, VAD, STT, TTS, foreground service, Bluetooth routing) in an
Android module and everything else — turn state machine, silence and guard
policy, phrase grammar, Corpus storage — in a pure-Kotlin module that imports
nothing from `android.*`, so it can lift into Kotlin Multiplatform when the
iPhone client arrives. We are not using Tauri.

## Considered options

The project's initial brainstorm concluded that Tauri 2 was "defensible,
potentially advantageous", on the grounds that it shares a web UI and a Rust
core across Android, iOS and a future desktop control panel, confining
duplication to the genuinely non-portable audio layer.

We rejected it because the leverage does not apply to this app. Tauri's value
is a shared WebView UI plus shared Rust logic, and stsloop v1 is eyes-free: it
has essentially no UI. Almost the entire product is the audio layer — the part
Tauri cannot share — so the shared layer would be nearly empty while the costs
(a third language, a plugin bridge, less mature mobile tooling, and native
build configuration still living in Gradle and Xcode) are paid immediately.
Worse, the plugin bridge would sit exactly between us and the layer we most
need to iterate on: silence thresholds, guard intervals and feedback
suppression.

For the stated goal of eventual iPhone support, Kotlin Multiplatform strictly
dominates Tauri here. The shared logic ends up in Kotlin — the same language as
the Android audio code — so there is no FFI boundary and no third runtime. The
brainstorm noted KMP was "the more conventional low-risk architecture" and then
recommended Tauri anyway, largely on the strength of a future desktop control
panel. But the corpus-review and classifier-development tooling has no reason
to share a codebase with the phone app: the Corpus syncs to the Linux box as
plain files, and that tooling can be a web page or a script.

KMP from day one was also considered and rejected for v1, because it means
building and debugging two audio backends before we know whether the loop's
behaviour is right at all.

## Consequences

- `:core` must be disciplined about keeping Android types out, or the KMP lift
  later will not be cheap. This needs enforcing, not hoping.
- iOS is genuinely deferred, not merely unimplemented. There will be a real
  port cost for the Swift audio shell.
- No shared UI exists for a future desktop control panel; it will be built
  separately against the hub.
