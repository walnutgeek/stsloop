# Architecture for an Audio-First Personal Context Hub

## Executive answer

The proposed product is feasible, but no single existing application combines all of its important parts: interruptible conversational audio, personalized podcast triage, browser-page context, and two-way control of Herdr sessions. Existing products cover slices of the idea: Snipd offers podcast summaries, chapters, highlights, transcript search, and hands-free chapter skipping; LiveKit Agents and Pipecat provide interruptible voice-agent plumbing; Home Assistant demonstrates an open, local-first voice pipeline and on-device Android wake words; and Herdr already exposes the agent-state and control APIs needed for a connector.[^1][^2][^3][^4][^5]

For the fastest path to adoption, build **Android first**, use a **native Kotlin app** for audio/media/background integration, run the conversational orchestrator on the Linux box in **Python with LiveKit Agents or Pipecat**, and build the Brave extension in **TypeScript as a Chromium Manifest V3 WebExtension**. Do not force the whole system into Rust or Go. A polyglot monorepo with a small versioned protocol will ship faster and contain less risky platform glue.

The central product abstraction should not be “a general voice assistant.” It should be an **audio session with a queue of interruptible content units and tools**. A content unit can be a podcast segment, an answer, a browser-page summary, a Herdr status update, or a prompt asking for approval. The conversation engine decides what to enqueue; a deterministic playback controller owns pause, skip, rewind, interruption, and resume.

## Product shape

### Three operating modes

| Mode | Primary interaction | Allowed actions | Safety model |
|---|---|---|---|
| **Drive / eyes-free** | Headset button, wake phrase, speech | Play, pause, skip, “why is this relevant?”, save, summarize, read Herdr status, dictate a prompt | No visual dependency; short responses; destructive actions require explicit spoken confirmation |
| **Screen companion** | Voice plus the currently selected browser tab or Herdr pane | Explain selected text, summarize page, inspect agent, draft or send input | Phone remains the microphone and speaker; desktop supplies context and optional confirmation UI |
| **Review / desk** | Voice plus rich phone/desktop UI | Edit interests, inspect transcripts, review queued actions, manage connectors | Full history and audit log; unrestricted browsing but still scoped tool permissions |

These modes should be explicit state-machine policies rather than one large system prompt. Driving mode, for example, should limit tool sets, response length, confirmation rules, and UI behavior before an LLM is involved.

### Podcast “fire-hose” behavior

A good first implementation should not ask an LLM to manipulate a raw two-hour audio stream in real time. Instead:

1. Read the podcast RSS feed and prefer Podcasting 2.0 transcripts and chapters when present. The namespace supports linked transcript files in VTT, SRT, HTML, text, and JSON, while external JSON chapters can be changed without modifying the audio file.[^6][^7]
2. If no timed transcript exists, download or stream the episode to the Linux box, transcribe it, and retain timestamped segments.
3. Split the transcript into coherent topic units, typically bounded by publisher chapters, speaker/topic changes, and silence.
4. Score each unit against a user-interest profile and recent feedback. Keep the scoring explainable: matched topics, novelty, speaker, show preference, and exploration bonus.
5. Build a queue of source time ranges. Stream the original podcast audio—not synthesized summaries—when playing a selected clip.
6. Treat “boring,” “skip,” “more detail,” “start earlier,” and “save this” as immediate playback intents. Update the preference model asynchronously so interruption latency never depends on an LLM request.

Snipd is the closest consumer reference: it provides AI chapters, transcript search, episode summaries, short highlights, automatic clipping, hands-free chapter skipping, and intro/outro skipping. It does not provide the Linux/browser/Herdr tool fabric described here, so it is a useful UX benchmark rather than a complete foundation.[^8][^2]

The open-standard path matters. Podcasting 2.0 timed transcripts are commonly VTT or SRT, and the specification also defines JSON segments with start/end times and speaker fields. That gives the system precise seek ranges without depending on a proprietary podcast catalog.[^9][^10]

## Recommended architecture

```text
Android app
  ├─ Media3 player + MediaSession
  ├─ foreground audio/microphone service
  ├─ wake/push-to-talk + local VAD
  ├─ LiveKit/WebRTC audio and data channel
  └─ deterministic playback state machine
              │
       Tailscale / TLS
              │
Linux personal hub
  ├─ voice runtime (LiveKit Agents or Pipecat)
  ├─ intent/router and session memory
  ├─ podcast ingest/transcription/indexer
  ├─ tool gateway + policy/confirmation layer
  ├─ Herdr connector
  ├─ browser-bridge endpoint
  └─ LLM/STT/TTS adapters, local or cloud
         │                    │
Herdr local socket/CLI   Brave MV3 extension
```

### Phone client

Use Kotlin, Jetpack Compose, Media3, `MediaSession`, and a foreground service. Android recommends Media3 for media playback, and Android Auto/Automotive integrates phone media apps through `MediaLibraryService` or `MediaBrowserService` plus `MediaSession`.[^11][^12]

The client should own:

- Audio input and output routing, Bluetooth/headset controls, focus, and ducking.
- Media playback and exact source timestamps.
- A local playback state machine and a small command grammar for zero-latency controls such as “stop,” “skip,” and “back 30 seconds.”
- Connection recovery, downloaded clip caching, and offline command buffering.
- Notification controls and later Android Auto support.
- Optional on-device wake-word detection. Home Assistant’s Android companion app is a useful reference because it uses microWakeWord locally and only sends audio after detection, including while locked or backgrounded.[^3]

Android requires careful lifecycle handling. On Android 14 and later, microphone use must be declared as a microphone foreground-service type with the appropriate permission, and the service normally has to be started while the app is visible or through an allowed user action. Start the drive session from a visible “Start drive mode” action, maintain an ongoing notification, and avoid promising covert always-on capture.[^13][^14]

### Real-time voice runtime

**Default choice: LiveKit Agents on the Linux host.** It is open source, supports Python and Node.js, combines WebRTC transport with STT-LLM-TTS orchestration, and includes turn detection and interruption handling. Its interruption behavior tracks what the user actually heard and truncates conversational history when speech is interrupted, which is important when podcast clips and synthesized answers share one session.[^15][^16][^17][^1]

**Alternative: Pipecat.** Pipecat is an open-source Python pipeline framework for voice and multimodal agents, with WebRTC/WebSocket transports and explicit frame processors. Its interruption path cancels in-flight LLM/TTS work, drains queued bot audio, and preserves only spoken text in context, making it especially attractive if low-level media mixing and custom frame routing are more important than LiveKit’s integrated room model.[^4][^18]

| Framework | Best fit | Strength | Cost |
|---|---|---|---|
| **LiveKit Agents** | Fastest production-quality phone-to-server voice prototype | Transport, sessions, turn detection, barge-in, mobile/WebRTC ecosystem in one stack | More framework opinion and infrastructure |
| **Pipecat** | Highly custom audio pipeline and experiments | Transparent frame pipeline, strong interruption semantics, many service adapters | More transport/deployment choices to assemble |
| **Home Assistant Assist** | Wake-word and local-home-assistant reference | Open pipeline for wake word, STT, intent, and TTS over WebSocket | Not naturally a long-form podcast/session orchestrator |
| **OpenVoiceOS** | Skill-based local assistant reference | Privacy-focused plugin/skill ecosystem and local processing | Less aligned with synchronized streaming media and mobile-first UX |

Home Assistant’s pipeline stages—wake word, STT, intent recognition, and TTS—are exposed through a WebSocket API and are worth studying for local voice components. OpenVoiceOS is another useful skills-and-plugins reference, but neither should be the application’s primary orchestration core.[^19][^20]

### Linux tool gateway

Run one long-lived personal-hub service on Linux. It should expose a narrow, authenticated domain protocol to the phone and map those calls to connectors. Suggested initial operations:

```text
podcast.search(query)
podcast.play_segment(episode_id, start_ms, end_ms)
podcast.feedback(segment_id, interested | skip | save)
context.list_sources()
context.get_active_browser_tab()
context.get_browser_selection()
herdr.list_agents()
herdr.read_agent(agent_id, tail_lines)
herdr.prompt_agent(agent_id, text, idempotency_key)
herdr.wait_agent(agent_id, target_state)
```

Use JSON Schema or Protobuf for these domain messages. MCP can be added behind the gateway for tool discovery and reuse—the protocol has official SDKs for TypeScript, Python, Kotlin, Go, Rust, and Swift—but the mobile client should not directly receive an unrestricted MCP tool catalog. A narrow gateway makes authorization, confirmations, retries, and stable mobile UX much easier.[^21][^22]

Use Tailscale for the first private deployment. Tailscale Serve can proxy a localhost service to other devices in the tailnet over HTTPS while retaining tailnet access controls. This avoids building public account infrastructure before product value is established.[^23][^24]

### Herdr connector

Herdr is unusually well suited to this project. It is a background terminal server whose panes and coding agents remain running after clients disconnect, and it supports remote attachment over SSH. More importantly, it exposes both CLI wrappers and a newline-delimited JSON local socket API.[^25][^26]

The connector should begin as a separate Linux process and use CLI JSON output for request/response operations. Use the raw socket only for long-lived subscriptions. Herdr’s API can list, inspect, read, prompt, wait on, focus, and start agents; it can also subscribe to state/output events.[^5][^27]

Recommended mapping:

| Voice request | Herdr operation |
|---|---|
| “Which agents need me?” | `agent.list`, filter `blocked` or `done` |
| “Read the last response from the payments agent” | `agent.read` with a bounded tail |
| “Tell it to run the tests” | `agent.prompt`, after confirmation in driving mode |
| “Let me know when it finishes” | `agent.wait` or event subscription |
| “What is happening in that pane?” | `agent.explain` or read a bounded snapshot |

Herdr identifies agent state as `blocked`, `working`, `done`, `idle`, or `unknown`, and its integrations can report state directly rather than relying only on terminal-screen detection. Avoid screen scraping when semantic APIs are available. For arbitrary terminals, Herdr can expose read-only newline-delimited terminal frames, but ANSI reconstruction should be a fallback rather than the first integration.[^28][^29][^30]

Herdr plugins can be written as shell, JavaScript, Lua, Rust, or any executable command, and the entire Herdr CLI acts as their API. Even so, the mobile bridge should be a supervised daemon rather than a Herdr startup hook, because Herdr documents startup hooks as one-shot initialization commands rather than supervised daemons.[^31][^32]

### Brave/browser connector

Build a Chromium Manifest V3 WebExtension in TypeScript. Brave supports nearly all Chromium-compatible extensions and Chrome Web Store extensions, so a Chrome-targeted build is the correct first target. Keep browser-specific code behind a tiny adapter; WebExtensions are substantially compatible with Firefox, although API and manifest differences remain.[^33][^34][^35][^36]

Extension components:

- **Content script:** extracts selected text, page title, canonical URL, readable text, headings, and optionally accessibility-tree-like element descriptors.
- **Service worker:** owns authentication, tab selection, commands, and connection to the local bridge.
- **Side panel/popup:** lets the user mark the current tab as the active context source and approve sensitive actions.
- **Context menu/shortcut:** “Send selection to audio hub.”

For the Linux handoff, use either:

1. **Native Messaging—recommended for a durable desktop product.** Chrome starts a registered native host and communicates with length-prefixed JSON over stdin/stdout. Content scripts relay through the extension service worker.[^37]
2. **Authenticated localhost WebSocket—recommended for the fastest prototype.** It is easier to iterate but requires origin/token checks, a pairing flow, and careful local-port security. Chromium extension service workers are normally terminated after inactivity; WebSocket traffic can extend their lifetime, but the connection needs periodic traffic inside the activity window.[^38][^39]

Do not send whole pages continuously. Store a per-tab context snapshot only after the user marks a tab, selects text, or invokes a command. This reduces privacy risk, token cost, and accidental leakage from unrelated tabs.

## Android versus iPhone

### Recommendation

Start with Android. This matches the existing phone workflow and gives the quickest route to background media, headset controls, custom foreground services, and Android Auto. Android Auto’s media model explicitly supports audio-first apps, voice controls, and podcast playback through standard media sessions.[^40][^41]

| Concern | Android | iPhone |
|---|---|---|
| Background playback | Strong with Media3 and foreground media service | Strong with `AVAudioSession` background-audio capability |
| Simultaneous playback/recording | Feasible, but service/permission lifecycle is strict | Feasible with `playAndRecord`; audio-session behavior must be managed carefully |
| Car integration | Android Auto uses standard media service/session architecture | CarPlay requires category entitlement approval |
| Default-assistant path | Possible through `VoiceInteractionService`, though only one default assistant is active | Much more controlled system-assistant integration |
| Prototype friction | Lower for sideloading and system experimentation | Higher due signing, background policy, and entitlement review |

On iOS, `AVAudioSession.playAndRecord` supports simultaneous recording and playback and can continue with the screen locked when the audio background mode is configured. The main obstacle is not raw audio capability; it is product distribution and car integration. CarPlay requires a requested and approved category entitlement, including the audio entitlement for audio apps.[^42][^43][^44]

An iOS version should therefore follow after the domain protocol and behavior are validated. Kotlin Multiplatform can then share networking, models, authentication, queue logic, and connector protocol while leaving audio sessions, background services, and car UI native. Google describes KMP as stable, production-ready, and officially supported for sharing Android/iOS business logic.[^45]

## Language and stack choices

### Recommended stack

| Layer | Language/technology | Reason |
|---|---|---|
| Android app | Kotlin, Compose, Media3 | Direct access to audio, foreground-service, media-session, Bluetooth, and Android Auto APIs |
| Voice orchestrator | Python + LiveKit Agents or Pipecat | Fastest ecosystem for STT/TTS/LLM experiments and model adapters |
| Linux gateway/connectors | TypeScript/Node initially; Go later if operationally useful | TypeScript aligns with browser schemas and MCP, and is fast for connector work |
| Brave extension | TypeScript, Manifest V3, `webextension-polyfill` if Firefox is added | Native browser ecosystem and broad Chromium compatibility |
| Shared schemas | JSON Schema or Protobuf; generated clients | Cross-language contracts without a forced shared runtime |
| Performance modules | Rust only where profiling justifies it | Good for local DSP, transcript indexing, codecs, or reusable native libraries—not initial product glue |
| Deployment | Docker Compose/systemd plus Tailscale | Simple self-hosted installation and private networking |

Rust or Go “everywhere” would reduce the number of languages while increasing platform-API wrapper work. The hardest code here is not portable computation; it is Android audio lifecycle, Chromium extension APIs, streaming voice framework integration, and fast-changing model adapters. Those areas reward native ecosystems.

A useful compromise is a polyglot monorepo:

```text
/apps/android
/services/hub
/services/voice-agent
/connectors/herdr
/extensions/brave
/packages/protocol
/packages/evals
/deploy
```

Generate types and contract tests from the protocol package. This removes the dangerous duplication—message definitions and behavior—not every duplicated loop or HTTP call.

### When to add Rust

Add Rust after profiling or when one of these becomes a stable reusable core:

- On-device VAD/wake-word/DSP shared through JNI and Swift FFI.
- High-throughput local transcript chunking or vector indexing.
- A secure native-messaging host distributed as a single binary.
- Cross-platform encrypted cache or sync engine.

Herdr itself demonstrates the operational appeal of a single Rust binary, but its plugin model deliberately allows any executable language. That is a good model for this product: stable infrastructure may become Rust; rapidly evolving intelligence and connectors should remain in high-productivity ecosystems.[^46][^32]

## Open-source projects to study

| Project | Learn or reuse | Caveat |
|---|---|---|
| **LiveKit Agents** | WebRTC transport, sessions, interruption, turn detection, mobile clients | Validate self-hosted behavior versus cloud-only advanced models |
| **Pipecat** | Frame pipeline, cancellation, media mixing, provider adapters | More composition work |
| **Home Assistant Assist** | Mobile wake word, local STT/TTS pipeline, WebSocket protocol | Automation-centric rather than long-form media-centric |
| **OpenVoiceOS** | Skills/plugins, privacy-first assistant architecture | Different UX and orchestration assumptions |
| **AntennaPod** | Mature Android podcast RSS/download/player architecture | GPL-3.0: study or comply carefully if incorporating code |
| **Snipd** | Product UX for chapters, snips, transcript navigation, headset actions | Proprietary reference, not a reusable codebase |
| **Herdr** | Persistent agents, semantic status, prompt/read/wait APIs | Local socket must be bridged and access-controlled |
| **MCP SDKs** | Standardized connector/tool surface | Keep unrestricted tools behind a policy gateway |

AntennaPod is a mature open-source Android podcast manager with downloads, streaming, RSS/Atom, chapters, and Android playback behavior, but its repository is GPL-3.0. It is excellent reference code; copying it into a differently licensed product requires deliberate license compatibility.[^47][^48]

## Security model

This app can read browser content and control shell agents, so security cannot be postponed until public launch.

- Pair each phone and extension separately; issue revocable device credentials.
- Keep the Linux API on the tailnet initially and expose no public listener.
- Default browser and Herdr capabilities to read-only.
- Separate `read`, `draft`, `execute`, and `destructive` tool classes.
- Require explicit confirmation for sending a Herdr prompt, approving an agent permission, executing shell commands, submitting forms, or revealing secrets.
- Use idempotency keys for voice-triggered writes; mobile reconnection must not submit the same prompt twice.
- Keep an append-only action log containing transcript, interpreted intent, tool call, result, and confirmation event.
- Cap context reads by characters/lines and redact common secret patterns before LLM submission.
- Never expose the raw Herdr socket or browser native-messaging host to the LAN.
- Make the microphone state unmistakable through the foreground notification, in-app state, earcon, and hardware/privacy indicators.

## Development plan

### Phase 0: prove interaction

Build a one-route prototype before podcast ingestion:

- Android push-to-talk app connects to Linux over LiveKit.
- Linux transcribes, routes a small intent set, and speaks responses.
- Commands: pause, resume, skip, “what did it say?”, and “stop listening.”
- Feed it hand-authored timestamped audio segments.

Success criterion: interruption feels immediate and reconnecting after a tunnel/network change does not lose playback position.

### Phase 1: podcast queue

- RSS subscription and episode download/stream.
- Podcasting 2.0 transcript/chapter parser.
- Fallback transcription on Linux.
- Segment queue, interest profile, thumbs-up/skip/save feedback.
- Headset media-button support and local deterministic commands.

Do not begin with a complex recommender. Start with explicit topic interests, embeddings, recency, show preferences, and an exploration percentage. Store every score component so the system can answer “why did you choose this?”

### Phase 2: desktop context

- Brave extension with “mark active tab” and “send selection.”
- Local WebSocket bridge for development; migrate to Native Messaging when packaging stabilizes.
- Context operations: title, URL, selection, readable text, and bounded DOM element references.
- Phone intents: “summarize the marked page,” “define the selected term,” and “read the next section.”

### Phase 3: Herdr

- Wrap `agent.list`, `agent.read`, `agent.prompt`, and `agent.wait`.
- Subscribe to blocked/done state transitions.
- Add spoken summaries such as “Two agents finished; one needs approval.”
- Keep write operations confirmation-gated and read tails bounded.

### Phase 4: car integration

- Media3 `MediaSession` quality pass.
- Android Auto media browsing and standard transport controls.
- Voice search/commands through supported media callbacks; Android Auto dispatches recognized media actions through callbacks such as `onPlayFromSearch`, `onPause`, and `onSkipToNext`.[^49]
- Only after the core app is reliable, evaluate whether default-assistant integration is worth the scope. Android maintains only the selected default `VoiceInteractionService` as the active assistant.[^50]

### Phase 5: iOS and packaging

- Extract stable domain logic into KMP or maintain generated protocol clients.
- Native Swift audio/CarPlay shell.
- Installer for Linux connector and Native Messaging manifest.
- Capability UI, audit history, and connector health diagnostics.

## Key design decisions

1. **Android first, not cross-platform UI first.** Audio lifecycle and car integration are the product’s riskiest parts, so validate them using native APIs.
2. **Linux-hosted intelligence, phone-hosted interaction.** The phone handles real-time audio and playback; Linux handles models, indexing, persistent connectors, and secrets.
3. **Deterministic media controller plus probabilistic planner.** The LLM proposes content and actions; deterministic code owns playback and permissions.
4. **Domain protocol at the mobile boundary.** MCP may organize backend tools, but the phone gets a small stable API.
5. **Use source audio for podcast clips.** TTS is for transitions, explanations, and summaries, not replacing the podcast.
6. **Explicit modes and capabilities.** Driving is a policy regime, not merely a visual theme.
7. **Polyglot by platform strength.** Kotlin, TypeScript, and Python will reach a convincing prototype faster than a forced Rust/Go monoculture.

## Final recommendation

The fastest credible build is: **native Android/Kotlin + Media3**, **LiveKit Agents/Python on Linux**, **TypeScript Linux gateway and Brave extension**, **Herdr CLI/socket connector**, and **Tailscale private networking**. Start with push-to-talk rather than an always-listening wake word, use publisher transcripts/chapters before transcribing, and implement a queue of timestamped podcast segments with instant local skip/stop controls.

Once the interaction loop is demonstrably useful, add on-device wake words, Native Messaging, Android Auto, richer Herdr events, and KMP-shared logic for iOS. Rust should be an optimization and packaging tool later—not the organizing principle of the first version.

---

## References

1. [Introduction | LiveKit Documentation](https://docs.livekit.io/agents/) - Realtime framework for voice, video, and physical AI agents.

2. [Snipd Feature Overview](https://www.snipd.com/all-features) - The AI-powered podcast app for knowledge seekers: AI notetaking, chat with podcasts, summaries, tran...

3. [The Home Assistant approach to wake words](https://www.home-assistant.io/voice_control/about_wake_word/) - Open source home automation that puts local control and privacy first. Powered by a worldwide commun...

4. [Interruptions - Pipecat](https://docs.pipecat.ai/pipecat/fundamentals/interruptions)

5. [Socket API](https://herdr.dev/docs/socket-api/) - Herdr exposes a local socket API for scripts and agents that need to inspect or control a running se...

6. [podcasting2.org · docs · podcast-namespaceChapters - Podcast Namespace - Podcasting 2.0](https://podcasting2.org/docs/podcast-namespace/tags/chapters)

7. [Transcript - Podcast Namespace](https://podcasting2.org/docs/podcast-namespace/tags/transcript) - This tag is used to link to a transcript or closed captions file. Multiple tags can be present for m...

8. [Snipd | AI Podcast Player on the App Store](https://apps.apple.com/nz/app/snipd-ai-podcast-player/id1557206126) - Snipd helps you capture key podcast insights on the go so that you can revisit and apply them anytim...

9. [Add Transcripts to Your Podcast](https://podcasting2.org/docs/guides/how-to-add-transcripts-to-your-podcast) - Podcasting 2.0 transcripts require two parts: a transcript file (one per language for each episode) ...

10. [Transcript File Format Details - Podcast Namespace](https://podcasting2.org/docs/podcast-namespace/examples/transcripts/transcripts) - SRT transcripts used for podcasts should adhere to the following specifications: Properties: Max num...

11. [Play media in the background](https://developer.android.com/media/platform/mediaplayer/background)

12. [Media apps for cars overview](https://developer.android.com/training/cars/media) - Use the Cars App Library templates to build apps with a customized media browsing and playback exper...

13. [Foreground service types are required](https://developer.android.com/about/versions/14/changes/fgs-types-required) - The RECORD_AUDIO runtime permission is subject to while-in-use restrictions. For this reason, you ca...

14. [Foreground service types | Background work](https://developer.android.com/develop/background-work/services/fgs/service-types) - The RECORD_AUDIO runtime permission is subject to while-in-use restrictions. For this reason, you ca...

15. [turns.md](https://docs.livekit.io/agents/build/turns.md)

16. [VAD and turn detection configuration guide | LiveKit](https://docs.livekit.io/agents/logic/turns/) - Guide to managing conversation turns in voice AI.

17. [Livekit/Agents](https://github.com/livekit/agents) - A framework for building realtime voice AI agents 🤖🎙️📹 - livekit/agents

18. [Pipecat Open Source Framework](https://docs.pipecat.ai/overview/pipecat) - Pipecat is an open source Python framework for voice and multimodal AI agents, orchestrating AI serv...

19. [OpenVoiceOS](https://github.com/openvoiceos) - OpenVoiceOS is a community-driven, open-source, privacy-respecting voice assistant framework and ope...

20. [Assist pipelines](https://developers.home-assistant.io/docs/voice/pipelines/) - The Assist pipeline integration runs the common steps of a voice assistant:

21. [Model Context Protocol · GitHub](https://github.com/modelcontextprotocol) - The Model Context Protocol (MCP) is an open protocol that enables seamless integration between LLM a...

22. [SDKs](https://modelcontextprotocol.io/docs/2025-03-26/sdk)

23. [App Capabilities Header](https://tailscale.com/docs/features/tailscale-serve) - Explore the Tailscale Serve service.

24. [tailscale serve command](https://tailscale.com/docs/reference/tailscale-cli/serve) - Use the `tailscale serve` CLI command to share a local service securely within your tailnet.

25. [one terminal for the whole herd](https://herdr.dev/)%7C) - Run them anywhere. Leave them running. Herdr holds real terminals open so your agents keep working w...

26. [How to work with Herdr](https://herdr.dev/docs/how-to-work/) - Run Herdr locally, inside SSH, or through remote attach.

27. [Socket API | herdr](https://herdr.dev/docs/preview/socket-api/) - Control a running Herdr server from scripts, tools, and coding agents.

28. [Persistence and remote access](https://herdr.dev/docs/persistence-remote/) - It prints newline-delimited JSON terminal.frame records with base64 ANSI bytes, then a terminal.clos...

29. [Concepts | herdr](https://herdr.dev/docs/preview/concepts/) - Understand Herdr workspaces, tabs, panes, agents, sessions, and modes.

30. [Integrations](https://herdr.dev/docs/integrations/) - Herdr detects the agents it supports automatically. Install an agent's integration so Herdr can resu...

31. [Plugins](https://herdr.dev/docs/plugins/) - Use the socket API when you want to send raw JSON requests yourself. Runtime action registration and...

32. [Commands And Environment](https://herdr.dev/docs/preview/plugins/) - Author local Herdr plugins with manifest actions, event hooks, and panes.

33. [Switch to Brave from Firefox – Brave Help Center](https://support.brave.app/hc/en-us/articles/360055359111-Switch-to-Brave-from-Firefox) - Knowledge base for Brave Browser

34. [Browser extensions - MDN Web Docs - Mozilla](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions) - The technology for extensions in Firefox is, to a large extent, compatible with the extension API su...

35. [Chrome incompatibilities - Mozilla - MDN Web Docs](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Chrome_incompatibilities) - The WebExtension APIs aim to provide compatibility across all the main browsers, so extensions shoul...

36. [How can I add extensions to Brave?](https://support.brave.app/hc/en-us/articles/360017909112-How-can-I-add-extensions-to-Brave) - Knowledge base for Brave Browser

37. [Native messaging - Chrome for Developers](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging) - Extensions can exchange messages with native applications using an API that is similar to the other ...

38. [The extension service worker lifecycle - Chrome for Developers](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle) - Sending or receiving messages across a WebSocket in an extension service worker resets the service w...

39. [Use WebSockets in service workers | Chrome Extensions](https://developer.chrome.com/docs/extensions/how-to/web-platform/websockets) - Step-by-step instructions on how to connect to a WebSocket in your Chrome extension.

40. [Media apps | Cars](https://developer.android.com/design/ui/cars/guides/app-types/media-apps)

41. [Google Assistant and media apps](https://developer.android.com/media/implement/assistant)

42. [Requesting CarPlay Entitlements](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements) - To integrate with CarPlay, you must request the appropriate entitlement for your app's category at C...

43. [playAndRecord | Apple Developer Documentation](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playandrecord) - static let record: AVAudioSession.Category. The category for recording audio while also silencing pl...

44. [CarPlay](https://developer.apple.com/carplay/) - CarPlay navigation apps can display enhanced information panels and share route information with sup...

45. [Kotlin Multiplatform](https://developer.android.com/kotlin/multiplatform)

46. [herdr/README.md at master · ogulcancelik/herdr · GitHub](https://github.com/ogulcancelik/herdr/blob/master/README.md) - agent multiplexer that lives in your terminal. Contribute to ogulcancelik/herdr development by creat...

47. [AntennaPod download | SourceForge.net](https://sourceforge.net/projects/antennapod.mirror/) - Download AntennaPod for free. Open source podcast manager for Android. AntennaPod is a flexible and ...

48. [AntennaPod - A podcast manager for Android](https://github.com/AntennaPod/AntennaPod) - This is the official repository of AntennaPod, the easy-to-use, flexible and open-source podcast man...

49. [Support voice actions | Android for Cars](https://developer.android.com/training/cars/media/voice-actions) - This document explains how to integrate voice actions into media apps for Android Auto and Android A...

50. [source.android.com › docs › automotiveAbout voice interaction - Android Open Source Project](https://source.android.com/docs/automotive/voice/voice_interaction_guide)

