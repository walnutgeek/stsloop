<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# I'm thinking about app that you can maintain conversation with it's basically audio hub audio interface to many things in your life because I have to spend some time driving every day I want this app to be able to play clips from a podcast that parts of the podcast that are interested and kind of react to voice and stop if it becomes not interesting so it's kind of managing fire hose another thing that it should be able to do is look over my herder instance and my Browser plugin so basically it needs to be able to pull dictionary from a particular tab in Herdr or from a browser page that I'm looking or marked to interact with at so there should be several modes one when you cannot see screen at all another is when you kind of interact with the things on the screen using voice but through the phone so app should be installed on the phone and be mobile and connect with some connectors on Linux box for herdr or on a browser from based I use Brave but I guess plugin is compatible. Anything like that already exists wood stack would approaches should I take what open source projects I can use or look at to learn to set up such app Is it easier to do iPhone or Android for this particular task what about plugins, Linux and browser? Should we stick to language like Rust or Go that could compile could be compiled everywhere so we have less redundant chunks or better to have technologies that particularly strong in the main. I guess key goal is speed of development or speed of adoption

Build it **Android-first** as a native Kotlin audio client, with the intelligence and connectors running on your Linux box. Use **LiveKit Agents or Pipecat** for interruptible voice orchestration, a TypeScript Manifest V3 extension for Brave, and Herdr’s existing CLI/socket API rather than scraping terminal screens.[^1_1][^1_2][^1_3]

## Recommended stack

| Component | Choice |
| :-- | :-- |
| Phone | Kotlin, Jetpack Compose, Media3, MediaSession |
| Voice runtime | Python + LiveKit Agents; Pipecat if you need lower-level pipeline control |
| Linux hub | TypeScript/Node initially |
| Brave extension | TypeScript, Chromium Manifest V3 |
| Herdr | CLI JSON for normal calls; socket subscriptions for events |
| Private connectivity | Tailscale Serve |
| Shared contract | JSON Schema or Protobuf with generated clients |
| Later iOS sharing | Kotlin Multiplatform for domain logic—not audio/UI |

LiveKit already handles streaming STT/LLM/TTS, turn detection, and interruption; Pipecat offers more explicit control over cancellation, audio queues, and frame processing. Brave accepts Chromium extensions, while Native Messaging provides a production-grade bridge from the extension to a Linux process.[^1_4][^1_5][^1_6][^1_2][^1_1]

## What already exists

Nothing appears to combine the complete concept, but the pieces exist:

- **Snipd:** podcast summaries, AI chapters, highlights, transcript search, headset-driven saving and skipping—the closest UX reference.[^1_7][^1_8]
- **AntennaPod:** mature open-source Android podcast architecture; useful to study, although its GPL-3.0 license matters if reusing code.[^1_9]
- **LiveKit Agents / Pipecat:** conversational audio, barge-in, turn-taking and streaming.
- **Home Assistant Assist:** local wake words and Android background wake-word operation.[^1_10][^1_11]
- **OpenVoiceOS:** skill/plugin architecture for a local, privacy-oriented assistant.[^1_12]
- **Herdr:** already supports listing, reading, prompting, waiting on and subscribing to coding-agent state.[^1_3]


## Product architecture

Model the app as an **interruptible queue of audio units**, not as one unconstrained assistant conversation. Units can be podcast excerpts, summaries, browser-page explanations, Herdr updates or approval requests.

Use three explicit modes:

- **Drive:** eyes-free, concise responses, read-only by default, confirmation before sending prompts or executing anything.
- **Screen companion:** voice operates on the marked browser tab, selection or Herdr pane.
- **Review:** full transcript, saved clips, actions, preferences and audit history.

For podcasts, ingest Podcasting 2.0 transcripts and chapters first, then transcribe only when necessary. Both standards provide timestamps suitable for selecting and playing original audio ranges. Let the LLM rank segments, but keep `stop`, `skip`, `back`, `pause` and barge-in entirely deterministic and local.[^1_13][^1_14]

## Android versus iPhone

**Android is substantially better for the first version.** It gives you direct Media3/MediaSession integration, foreground audio services, easier sideloading and a standard path into Android Auto. Android does impose strict microphone foreground-service lifecycle requirements, so drive mode must be started through a visible user action and maintain an ongoing notification.[^1_15][^1_16][^1_17][^1_18]

iOS can perform background playback and simultaneous recording through `AVAudioSession.playAndRecord`, but CarPlay distribution requires an approved category entitlement. Add iPhone only after the interaction model and protocol stabilize.[^1_19][^1_20]

## Language decision

Do **not** use Rust or Go everywhere merely to share code. Most difficult work is platform integration—not portable algorithms:

- Kotlin is strongest for Android audio, services, Bluetooth and Android Auto.
- TypeScript is strongest for Brave extensions, browser tooling and fast connector work.
- Python has the deepest voice-agent and model integration ecosystem.
- Rust is appropriate later for a single-binary native-messaging host, DSP, indexing or secure local storage.

The detailed report includes the full component architecture, Herdr operation mapping, browser-extension design, security model, open-source project matrix and phased implementation plan.

<span style="display:none">[^1_100][^1_101][^1_102][^1_103][^1_104][^1_105][^1_106][^1_107][^1_108][^1_109][^1_110][^1_111][^1_112][^1_113][^1_114][^1_115][^1_116][^1_117][^1_118][^1_119][^1_120][^1_121][^1_122][^1_123][^1_124][^1_125][^1_126][^1_127][^1_128][^1_129][^1_130][^1_131][^1_132][^1_133][^1_134][^1_135][^1_136][^1_137][^1_138][^1_139][^1_140][^1_141][^1_142][^1_143][^1_144][^1_145][^1_146][^1_147][^1_148][^1_149][^1_150][^1_151][^1_152][^1_153][^1_154][^1_155][^1_156][^1_157][^1_158][^1_159][^1_160][^1_161][^1_162][^1_163][^1_164][^1_21][^1_22][^1_23][^1_24][^1_25][^1_26][^1_27][^1_28][^1_29][^1_30][^1_31][^1_32][^1_33][^1_34][^1_35][^1_36][^1_37][^1_38][^1_39][^1_40][^1_41][^1_42][^1_43][^1_44][^1_45][^1_46][^1_47][^1_48][^1_49][^1_50][^1_51][^1_52][^1_53][^1_54][^1_55][^1_56][^1_57][^1_58][^1_59][^1_60][^1_61][^1_62][^1_63][^1_64][^1_65][^1_66][^1_67][^1_68][^1_69][^1_70][^1_71][^1_72][^1_73][^1_74][^1_75][^1_76][^1_77][^1_78][^1_79][^1_80][^1_81][^1_82][^1_83][^1_84][^1_85][^1_86][^1_87][^1_88][^1_89][^1_90][^1_91][^1_92][^1_93][^1_94][^1_95][^1_96][^1_97][^1_98][^1_99]</span>

<div align="center">⁂</div>

[^1_1]: https://docs.livekit.io/agents/

[^1_2]: https://www.snipd.com/all-features

[^1_3]: https://www.home-assistant.io/voice_control/about_wake_word/

[^1_4]: https://docs.pipecat.ai/pipecat/fundamentals/interruptions

[^1_5]: https://herdr.dev/docs/socket-api/

[^1_6]: https://podcasting2.org/docs/podcast-namespace/tags/chapters

[^1_7]: https://podcasting2.org/docs/podcast-namespace/tags/transcript

[^1_8]: https://apps.apple.com/nz/app/snipd-ai-podcast-player/id1557206126

[^1_9]: https://podcasting2.org/docs/guides/how-to-add-transcripts-to-your-podcast

[^1_10]: https://podcasting2.org/docs/podcast-namespace/examples/transcripts/transcripts

[^1_11]: https://developer.android.com/media/platform/mediaplayer/background

[^1_12]: https://developer.android.com/training/cars/media

[^1_13]: https://developer.android.com/about/versions/14/changes/fgs-types-required

[^1_14]: https://developer.android.com/develop/background-work/services/fgs/service-types

[^1_15]: https://docs.livekit.io/agents/build/turns.md

[^1_16]: https://docs.livekit.io/agents/logic/turns/

[^1_17]: https://github.com/livekit/agents

[^1_18]: https://docs.pipecat.ai/overview/pipecat

[^1_19]: https://github.com/openvoiceos

[^1_20]: https://developers.home-assistant.io/docs/voice/pipelines/

[^1_21]: https://github.com/modelcontextprotocol

[^1_22]: https://modelcontextprotocol.io/docs/2025-03-26/sdk

[^1_23]: https://tailscale.com/docs/features/tailscale-serve

[^1_24]: https://tailscale.com/docs/reference/tailscale-cli/serve

[^1_25]: https://herdr.dev/)%7C

[^1_26]: https://herdr.dev/docs/how-to-work/

[^1_27]: https://herdr.dev/docs/preview/socket-api/

[^1_28]: https://herdr.dev/docs/persistence-remote/

[^1_29]: https://herdr.dev/docs/preview/concepts/

[^1_30]: https://herdr.dev/docs/integrations/

[^1_31]: https://herdr.dev/docs/plugins/

[^1_32]: https://herdr.dev/docs/preview/plugins/

[^1_33]: https://support.brave.app/hc/en-us/articles/360055359111-Switch-to-Brave-from-Firefox

[^1_34]: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions

[^1_35]: https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Chrome_incompatibilities

[^1_36]: https://support.brave.app/hc/en-us/articles/360017909112-How-can-I-add-extensions-to-Brave

[^1_37]: https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging

[^1_38]: https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle

[^1_39]: https://developer.chrome.com/docs/extensions/how-to/web-platform/websockets

[^1_40]: https://developer.android.com/design/ui/cars/guides/app-types/media-apps

[^1_41]: https://developer.android.com/media/implement/assistant

[^1_42]: https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements

[^1_43]: https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playandrecord

[^1_44]: https://developer.apple.com/carplay/

[^1_45]: https://developer.android.com/kotlin/multiplatform

[^1_46]: https://github.com/ogulcancelik/herdr/blob/master/README.md

[^1_47]: https://sourceforge.net/projects/antennapod.mirror/

[^1_48]: https://github.com/AntennaPod/AntennaPod

[^1_49]: https://developer.android.com/training/cars/media/voice-actions

[^1_50]: https://source.android.com/docs/automotive/voice/voice_interaction_guide

[^1_51]: https://herdr.dev/docs/quick-start/

[^1_52]: https://github.com/nathnael-desta/herdr-project-sessions

[^1_53]: https://news.ycombinator.com/item?id=48756578

[^1_54]: https://herdr.dev/docs/cli-reference/

[^1_55]: https://herdr.dev/docs/concepts/

[^1_56]: https://docs.railway.com/cloud-agents/herdr

[^1_57]: https://herdr.dev/docs/preview/quick-start/

[^1_58]: https://www.mindstudio.ai/blog/herder-terminal-agent-multiplexer

[^1_59]: https://github.com/SuperCodeAgents/herdr-terminal

[^1_60]: https://pyshine.com/herdr-Agent-Multiplexer-for-AI-Agents/

[^1_61]: https://herdr.dev/docs/install/

[^1_62]: https://flaviocopes.com/herdr-phone/

[^1_63]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

[^1_64]: https://developer.android.com/about/versions/11/privacy/foreground-services

[^1_65]: https://developer.android.com/develop/background-work/services/fgs/changes

[^1_66]: https://developer.chrome.com/docs/extensions/develop/concepts/real-time

[^1_67]: https://developer.android.com/about/versions/17/changes/bg-audio

[^1_68]: https://developer.android.com/about/versions/15/changes/foreground-service-types

[^1_69]: https://developer.android.com/develop/background-work/services/fgs/declare

[^1_70]: https://developer.android.com/about/versions/14/changes/fgs-types-required?;authuser=1\&authuser=1\&hl=zh-tw

[^1_71]: https://developer.android.com/about/versions/14/changes/fgs-types-required?;authuser=2\&authuser=2\&hl=ko

[^1_72]: https://developer.android.com/develop/background-work/services/fgs

[^1_73]: https://developer.apple.com/documentation/avfaudio/avaudiosession

[^1_74]: https://developer.apple.com/documentation/avfaudio/avaudiosession.md

[^1_75]: https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record

[^1_76]: https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback

[^1_77]: https://developer.apple.com/documentation/avfaudio/avaudiosession/setaggregatediopreference(\_:)

[^1_78]: https://developer.apple.com/documentation/bundleresources/entitlements

[^1_79]: https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.carplay-audio

[^1_80]: https://developer.apple.com/videos/play/wwdc2026/212/

[^1_81]: https://developer.apple.com/documentation/AVFAudio/AVAudioSession/Category-swift.struct/playback?language=\_8,\_8

[^1_82]: https://developer.apple.com/documentation/carplay/supporting-previous-versions-of-ios

[^1_83]: https://developer.apple.com/documentation/coreaudiotypes/avaudiosession/errorcode/cannotstartrecording?changes=la\&language=objc

[^1_84]: https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.carplay-maps

[^1_85]: https://docs.livekit.io/agents/logic/turns/turn-detector/

[^1_86]: https://livekit.com/blog/turn-detection-and-interruption-handling

[^1_87]: https://docs.livekit.io/agents/logic/turns/tuning/

[^1_88]: https://docs.livekit.io/agents/multimodality/audio/

[^1_89]: https://livekit.com/blog/improved-end-of-turn-model-cuts-voice-ai-interruptions-39

[^1_90]: https://livekit.com/voice-agents

[^1_91]: https://livekit.com/

[^1_92]: https://livekit.com/blog/adaptive-interruption-handling

[^1_93]: https://docs.livekit.io/agents/logic/sessions/

[^1_94]: https://livekit.com/blog/build-your-first-ai-voice-agent-python

[^1_95]: https://livekit.com/blog/solving-end-of-turn-detection

[^1_96]: https://dispatch.fm/features/podcasting-2-0

[^1_97]: https://blubrry.com/support/podcasting-2-0-introduction/

[^1_98]: https://vexascribe.com/podcast-transcription

[^1_99]: https://fast.io/resources/podcast-metadata-extraction-rss-chapters/

[^1_100]: https://support.iono.fm/docs/podcast-2-support

[^1_101]: https://www.podhome.fm/docs/podcasting-2-0

[^1_102]: https://blog.castopod.org/apple-podcasts-embraces-chapters-another-victory-for-podcasting-2-0/

[^1_103]: https://blubrry.com/support/powerpress-documentation/powerpress-podcasting-2-0-features/

[^1_104]: https://convertaudiototext.com/blog/transcription-for-podcasters-complete-guide

[^1_105]: https://brave.com/learn/what-are-web-browser-extensions/

[^1_106]: https://github.com/LegaSage/awesome-podcast-apps/blob/main/README.md

[^1_107]: https://github.com/AntennaPod

[^1_108]: https://github.com/ByteHamster/AntennaPod

[^1_109]: https://github.com/julykova/AntennaPod

[^1_110]: https://github.com/promethyttrium/AntennaPod

[^1_111]: https://android.googlesource.com/platform/external/AntennaPod/AntennaPod/+/bd217c8cf0a7d3db5f2126aa35ba8bfa41c6f7e3/CHANGELOG.md

[^1_112]: https://brave.com/learn/category/browser-extensions/

[^1_113]: https://store.openvoiceos.org/

[^1_114]: https://docs.pipecat.ai/llms.txt

[^1_115]: https://www.assemblyai.com/docs/voice-agents/best-practices

[^1_116]: https://store.openvoiceos.org/search/

[^1_117]: https://www.assemblyai.com/docs/voice-agents/pipecat-universal-3-5-pro

[^1_118]: https://github.com/csells/awesome-jarvis

[^1_119]: https://docs.smallest.ai/voice-agents/integrations/agent-framework/pipecat

[^1_120]: https://openvoiceos.github.io/ovos-technical-manual/54-skill-examples/

[^1_121]: https://docs.pipecat.ai/pipecat/examples/recipes

[^1_122]: https://www.openvoiceos.org/press

[^1_123]: https://source.android.com/docs/automotive/voice/ttr

[^1_124]: https://developers.google.com/cars/design/create-apps/app-types/media

[^1_125]: https://developers.google.com/cars/design/android-auto/apps/media-apps

[^1_126]: https://developer.android.com/training/cars/apps/media

[^1_127]: https://support.google.com/androidauto/answer/6348327?hl=en-GB

[^1_128]: https://developer.android.com/training/cars

[^1_129]: https://developer.android.com/training/cars/platforms/android-auto

[^1_130]: https://developers.google.com/cars/design/android-auto

[^1_131]: https://developer.android.com/cars

[^1_132]: https://source.android.google.cn/docs/automotive/voice/voice_interaction_guide?hl=zh-cn

[^1_133]: https://herdr.dev/docs/agents/

[^1_134]: https://herdr.dev/docs/

[^1_135]: https://herdr.dev/docs/configuration/

[^1_136]: https://herdr.dev/docs/preview/agents/

[^1_137]: https://herdr.dev/docs/preview/integrations/

[^1_138]: https://herdr.dev/docs/connecting-machines/

[^1_139]: https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html

[^1_140]: https://docs.flutter.dev/platform-integration/platform-channels

[^1_141]: https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html

[^1_142]: https://docs.flutter.dev/resources/faq

[^1_143]: https://kotlinlang.org/docs/multiplatform/build-ios-android-app.html

[^1_144]: https://kotlinlang.org/multiplatform/

[^1_145]: https://docs.flutter.dev/platform-integration/platform-channels?tab=type-mappings-swift-tab

[^1_146]: https://kotlinlang.org/docs/multiplatform-mobile-upgrade-app.html

[^1_147]: https://kotlinlang.org/docs/multiplatform/ios-android-app-development.html

[^1_148]: https://docs.flutterflow.io/concepts/advanced/method-channels/

[^1_149]: https://blog.jetbrains.com/kotlin/2020/08/kotlin-multiplatform-mobile-goes-alpha/

[^1_150]: https://flutter.unisbadri.com/en/advanced/platform-channels/

[^1_151]: https://kotlinlang.org/docs/multiplatform/multiplatform-upgrade-app.html

[^1_152]: https://flutteruniversity.gitbook.io/docs/learn-flutter/professional/native-integrations

[^1_153]: https://github.com/modelcontextprotocol/typescript-sdk

[^1_154]: https://modelcontextprotocol.io/docs/2025-11-25/sdk

[^1_155]: https://modelcontextprotocol.io/docs/2024-11-05/sdk

[^1_156]: https://modelcontextprotocol.io/docs/draft/sdk

[^1_157]: https://github.com/modelcontextprotocol/typescript-sdk/tree/main?tab=readme-ov-file

[^1_158]: https://github.com/ArcBlock/mcp-typescript-sdk

[^1_159]: https://github.com/tilfin/modelcontextprotocol-typescript-sdk

[^1_160]: https://modelcontextprotocol.io/docs/2026-07-28/sdk

[^1_161]: https://github.com/orgs/modelcontextprotocol/repositories

[^1_162]: https://tailscale.com/docs/solutions/migrate-legacy-vpn-tailscale

[^1_163]: https://tailscale.com/docs/reference/tailscale-cli

[^1_164]: https://www.perplexity.ai/search/de75959a-727c-43c3-a000-1027b3060c45


---

# I don't really use Android after or CarPlay usually it's just the Bluetooth integration so Tesla and transit pretty much its only option. Does it change calculus

Yes—the lack of Android Auto/CarPlay **significantly reduces Android’s platform advantage**, but it does not change the overall phone-plus-Linux architecture. Since Tesla and ordinary transit audio systems behave mainly as Bluetooth peripherals, the first client can target whichever phone you actually carry; for your current workflow, Android still wins mostly because of deployment freedom and your existing device, not because of automotive integration.

## What Bluetooth provides

Tesla supports Bluetooth media playback and hands-free phone audio, while steering-wheel/media controls manage playback and volume. Your app can therefore appear as an ordinary podcast/media player:[^2_1][^2_2]

- Stream high-quality audio over Bluetooth A2DP.
- Publish title, segment and episode metadata through the phone’s media session.
- Receive play, pause, next and previous events where the vehicle exposes them.
- Treat “next” as “not interesting—skip this segment.”
- Keep all intelligence, indexing and Herdr/browser interaction on the phone and Linux server.

On Android, Bluetooth media-button events are routed to the active `MediaSession`, including while the activity is hidden. Consequently, Media3 and MediaSession remain important, but Android Auto code can be removed entirely.[^2_3]

## The microphone caveat

Bluetooth has two relevant operating modes:


| Mode | Output quality | Microphone | Recommended use |
| :-- | --: | --: | :-- |
| A2DP media | High-quality stereo | No | Podcast and TTS playback |
| HFP communication | Lower-quality voice audio | Yes, including vehicle microphone | Active conversation when cabin mic is required |
| A2DP + phone mic | High-quality output | Phone microphone | Preferable if routing works reliably on the target phone |

A2DP is explicitly output-only and intended for high-bandwidth media; using Bluetooth microphone input switches the session toward the hands-free communication route. Classic Bluetooth generally lowers output quality while the microphone is active, whereas newer LE Audio improves simultaneous input/output—but you should not assume vehicles support LE Audio.[^2_4][^2_5]

That means **continuous full-duplex listening through Tesla’s cabin microphone is probably the wrong default**. Better behavior:

1. Play podcasts and TTS through A2DP.
2. Detect a deliberate trigger: phone button, headset button, notification action or possibly a local wake word using the phone microphone.
3. Pause playback.
4. Open a short voice-capture window.
5. Execute the command.
6. Return to A2DP playback.

This half-duplex interaction avoids leaving the vehicle connection in low-quality call mode.

## Platform calculus

| Question | Android | iPhone |
| :-- | :-- | :-- |
| Bluetooth playback | Excellent | Excellent |
| Media controls | MediaSession | Now Playing/remote commands |
| A2DP plus voice capture | Requires device-specific routing tests | Explicit `AVAudioSession` routing options |
| Background microphone | Foreground-service restrictions | Background audio/session restrictions |
| Sideloading and experimentation | Easier | More signing/distribution friction |
| Tesla integration | Essentially equal | Essentially equal |
| Linux/browser/Herdr connectivity | Equal | Equal |

iOS explicitly supports both Bluetooth HFP input and A2DP output options through `AVAudioSession`; selecting HFP input normally routes output to HFP as well, while `allowBluetoothA2DP` permits media output for suitable `playAndRecord` sessions. Android offers comparable communication-device routing, but behavior should be tested against your exact phone/Tesla combination.[^2_6][^2_7][^2_5][^2_4]

## Revised recommendation

For a personal prototype:

- **Android/Kotlin remains the shortest path** because you already use Android and can deploy/debug freely.
- Drop all Android Auto work.
- Build only Media3/MediaSession, foreground audio capture, Bluetooth routing and media-button handling.
- Use **push-to-talk or tap-to-talk first**, not always-on full-duplex capture.
- Test both routes: Tesla speakers plus phone microphone, and Tesla HFP microphone.
- Assign Bluetooth “next” to skip-and-down-rank; “previous” to replay or move to the previous segment.
- Keep a large notification action for “Talk,” plus optional volume-button/headset-button gestures.

For broader adoption, **iPhone should move earlier in the roadmap** because Bluetooth makes the core experience nearly platform-neutral. Share the session protocol, queue, scoring model and connector schemas; keep the audio-routing shell native on each platform.

The quickest validation may even be a **minimal native audio shell on both platforms**, with nearly all product behavior server-side. The difficult part to prove is no longer vehicle UI integration—it is whether Bluetooth mode switching, interruption latency and microphone capture feel natural enough during real driving.

<span style="display:none">[^2_10][^2_11][^2_12][^2_13][^2_14][^2_15][^2_16][^2_17][^2_18][^2_19][^2_20][^2_21][^2_22][^2_23][^2_24][^2_25][^2_26][^2_27][^2_8][^2_9]</span>

<div align="center">⁂</div>

[^2_1]: https://www.tesla.com/ownersmanual/models/en_us/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_2]: https://www.tesla.com/ownersmanual/model3/en_us/GUID-3D90EA76-8DE3-4808-B7E4-1979EF299F3A.html

[^2_3]: https://developer.android.com/media/legacy/media-buttons

[^2_4]: https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/allowbluetootha2dp

[^2_5]: https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview

[^2_6]: https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/allowbluetoothhfp?changes=\_\_3

[^2_7]: https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/allowbluetooth

[^2_8]: https://www.tesla.com/ownersmanual/modely/en_us/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_9]: https://www.tesla.com/ownersmanual/model3/en_uy/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_10]: https://www.tesla.com/ownersmanual/model3/en_kr/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_11]: https://www.tesla.com/ownersmanual/model3/en_sg/GUID-3D90EA76-8DE3-4808-B7E4-1979EF299F3A.html

[^2_12]: https://www.tesla.com/ownersmanual/modely/en_us/GUID-3D90EA76-8DE3-4808-B7E4-1979EF299F3A.html

[^2_13]: https://www.tesla.com/ownersmanual/modely/en_us/GUID-68582EA9-CBE5-4474-880E-3EF4992002DF.html

[^2_14]: https://www.tesla.com/ownersmanual/models/en_ie/GUID-68582EA9-CBE5-4474-880E-3EF4992002DF.html

[^2_15]: https://www.tesla.com/ownersmanual/model3/en_gb/GUID-68582EA9-CBE5-4474-880E-3EF4992002DF.html

[^2_16]: https://www.tesla.com/ownersmanual/model3/en_us/GUID-68582EA9-CBE5-4474-880E-3EF4992002DF.html

[^2_17]: https://www.tesla.com/ownersmanual/modely/en_eu/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_18]: https://developer.apple.com/documentation/avfaudio/avaudiosession

[^2_19]: https://www.tesla.com/ownersmanual/modely/en_mo/GUID-3D90EA76-8DE3-4808-B7E4-1979EF299F3A.html

[^2_20]: https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct

[^2_21]: https://developer.apple.com/documentation/avfaudio/avaudiosession/categoryoptions-swift.struct/defaulttospeaker

[^2_22]: https://www.tesla.com/ownersmanual/model3/en_th/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_23]: https://github.com/elwinchia/tesla-pi

[^2_24]: https://www.tesla.com/ownersmanual/model3/en_qa/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_25]: https://www.tesla.com/ownersmanual/modely/en_cl/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html

[^2_26]: https://developer.android.com/develop/connectivity/bluetooth

[^2_27]: https://www.tesla.com/ownersmanual/modely/en_kr/GUID-7A85FB6B-9DF6-4C55-A2F9-793207E48E9D.html


---

# I use both Android and iPhone. And nothing built yet. does use something like TAURI complicate things. I understand Bluetooth stack probably requires tighter device integration so there will be native codes for each platform but from architecture point of view we can still target one platform but use TAURI to be able to add another one later

Yes—**Tauri is a reasonable choice if you treat it as the cross-platform UI and application shell, not as the audio runtime**. It adds some initial plumbing, but it can make the second mobile platform—and a future desktop control panel—substantially easier.

## Correct boundary

Tauri 2 supports Android and iOS from one web frontend and Rust core, while its plugin system exposes native Kotlin and Swift implementations behind a common API. For this app, the boundary should be:[^3_1][^3_2]

```text
Tauri UI: TypeScript/Svelte/React
    │ commands + low-frequency events
Rust core: protocol, queue, state machine, persistence
    │ Tauri mobile plugin interface
Native audio engine
    ├── Android: Kotlin + Media3 + MediaSession + AudioManager
    └── iOS: Swift + AVAudioSession + AVAudioEngine/AVPlayer
              │
       WebRTC/WebSocket
              │
         Linux hub
```

The native audio engine—not the WebView—must own:

- Background playback.
- Microphone capture.
- Bluetooth route changes.
- Audio focus and interruption handling.
- Lock-screen and Bluetooth media controls.
- Foreground-service lifecycle on Android.
- `AVAudioSession` lifecycle on iOS.
- Playback position and reconnection state.

Mobile WebViews may throttle timers or suspend hidden content, and Tauri cannot disable that behavior consistently across platforms. Therefore, JavaScript should never be responsible for maintaining audio playback, detecting silence, uploading continuous microphone frames or responding to Bluetooth buttons while backgrounded.[^3_3]

## What Tauri shares

| Component | Shared? | Location |
| :-- | --: | :-- |
| UI and settings | Yes | TypeScript/WebView |
| Conversation and playback state model | Yes | Rust |
| Linux protocol client | Yes | Rust |
| Queue, bookmarks and feedback | Yes | Rust |
| Local database/schema | Mostly | Rust |
| Authentication and pairing | Yes | Rust |
| Media playback API | Interface only | Native plugin |
| Recording and Bluetooth routing | No | Kotlin/Swift |
| Background lifecycle | No | Kotlin/Swift |
| OS media controls | No | Kotlin/Swift |

Tauri’s mobile plugin template explicitly creates a Kotlin Android library and Swift package, with native commands exposed to Rust or JavaScript. That matches this architecture well: define one logical `audio-session` plugin, then implement its Android and iOS backends separately.[^3_4][^3_1]

## Suggested plugin API

Keep the API semantic rather than exposing platform details:

```ts
interface AudioSession {
  configure(options: SessionOptions): Promise<void>;
  play(item: PlaybackItem): Promise<void>;
  pause(): Promise<void>;
  seek(positionMs: number): Promise<void>;

  beginVoiceTurn(mode: "phone-mic" | "bluetooth-hfp"): Promise<void>;
  endVoiceTurn(): Promise<void>;

  setNowPlaying(metadata: MediaMetadata): Promise<void>;
  getState(): Promise<AudioState>;
}
```

Native-to-core events:

```ts
type AudioEvent =
  | { type: "media-command"; command: "play" | "pause" | "next" | "previous" }
  | { type: "route-changed"; route: AudioRoute }
  | { type: "interruption"; state: "began" | "ended" }
  | { type: "playback-position"; positionMs: number }
  | { type: "voice-turn-started" }
  | { type: "voice-turn-ended" }
  | { type: "error"; code: string };
```

Do **not** continuously move raw PCM through `JSON.stringify`, Tauri commands and WebView events. Tauri supports raw byte requests, but the better design is for Kotlin/Swift—or a shared native Rust library—to stream encoded audio directly to the Linux voice service. The WebView should only see state and transcript events.[^3_2]

## Does it complicate development?

Moderately:

- You now debug JavaScript, Rust and one native language for the first platform.
- Tauri’s mobile ecosystem is younger than its desktop ecosystem.
- Audio plugins that exist today primarily cover native background **playback**, not your full duplex recording, Bluetooth routing and conversational interruption requirements.[^3_5][^3_6]
- Native build configuration still lives in Gradle/Android Studio and Xcode.
- Plugin bridges introduce another lifecycle and error boundary.

But native-only development does not eliminate the Kotlin/Swift split—it merely moves all UI and application logic into two implementations. Tauri confines the duplication to the parts that genuinely cannot be portable.

## Existing plugins

A community Tauri native-audio plugin already wraps Media3 plus `MediaSessionService` on Android and `AVPlayer`, Now Playing and remote commands on iOS. It is useful as reference code or an initial playback implementation, but it uses the iOS `.playback` category and does not solve conversational recording and Bluetooth HFP/A2DP switching.[^3_6][^3_5]

Another media-session plugin reports complete Android support but labels iOS support as work in progress, illustrating why these pieces should be treated as references rather than critical dependencies. For the core audio plugin, owning the relatively small native implementations is safer.[^3_7]

## Best rollout

I would use Tauri and start with **iPhone first or Android first based purely on which device you drive with more often**—Bluetooth no longer gives either platform a decisive product advantage.

1. Create the Tauri app with mocked `AudioSession`.
2. Put session state, queue behavior and Linux protocol in Rust.
3. Implement one native backend—Kotlin or Swift.
4. Test Tesla Bluetooth routing before building podcast intelligence.
5. Keep the native API platform-neutral.
6. Implement the second backend without changing UI or orchestration.
7. Reuse the same Tauri frontend for a Linux/macOS/Windows control panel later.

## Recommendation

Use Tauri if you expect **both Android and iPhone plus potentially a desktop control application**. Tauri supports exactly that arrangement: shared web UI, shared Rust logic and native Kotlin/Swift integrations.[^3_8]

If this will remain exclusively a mobile audio app, Tauri offers less leverage; Kotlin Multiplatform with native Compose/SwiftUI shells would be the more conventional low-risk architecture. But given your Linux connector, browser integration and likely desktop administration UI, **Tauri is defensible and potentially advantageous—provided audio remains a native subsystem and Tauri remains the control plane.**

<span style="display:none">[^3_10][^3_11][^3_12][^3_13][^3_14][^3_15][^3_9]</span>

<div align="center">⁂</div>

[^3_1]: https://v2.tauri.app/develop/plugins/develop-mobile/

[^3_2]: https://v2.tauri.app/blog/tauri-20/

[^3_3]: https://v2.tauri.app/reference/javascript/api/namespacewebview/

[^3_4]: https://v2.tauri.app/develop/plugins/

[^3_5]: https://github.com/uvarov-frontend/tauri-plugin-native-audio

[^3_6]: https://docs.rs/crate/tauri-plugin-native-audio/latest/source/README.md

[^3_7]: https://crates.io/crates/tauri-plugin-media-session

[^3_8]: https://v2.tauri.app/

[^3_9]: https://tauri.app/release/tauri/

[^3_10]: https://tauri.app/blog/tauri-2-0-0-alpha-4/

[^3_11]: https://factory.ai/open-source-wikis/tauri?page=systems/mobile.md

[^3_12]: https://tauri.app/blog/roadmap-to-tauri-2-0/

[^3_13]: https://v2.tauri.app/plugin/

[^3_14]: https://github.com/brenogonzaga/tauri-plugin-tts

[^3_15]: https://blog.codercops.com/blog/tauri-2-desktop-mobile-apps-rust-web-2026


---

# I have pixel 10 Pro and I have iPhone 14 Pro I want to be able to use voice recognition running on device locally and I want to be able to generate text-to-speech on a device locally basically first MVP is running a loop transcription if there is a few seconds pause detected it just play back transcription in computer voice later I want to run small simple system one model on device model like jev but locally to recognize if transcription is actually command or something to remember for later. Are any of those two devices are good choice for this? Is it even feasible.

Yes, this is completely feasible on both devices. **Start with the Pixel 10 Pro**: its 16 GB RAM, Tensor G5, easier background execution/debugging, and less restrictive model deployment give you considerably more room for later on-device intent classification or a small language model.[^4_1][^4_2]

## Device choice

| Capability | Pixel 10 Pro | iPhone 14 Pro |
| :-- | :-- | :-- |
| Local STT | Android on-device recognizer, Whisper.cpp, Sherpa-ONNX | SpeechAnalyzer/DictationTranscriber, Whisper.cpp |
| Local TTS | Android embedded TTS voices, Sherpa/Kokoro/Piper | AVSpeechSynthesizer |
| Available RAM | 16 GB[^4_1] | Approximately 6 GB[^4_3] |
| Small local LLM | Comfortable for quantized 1B–4B experimentation | Realistically target sub-2B or classifiers |
| Background microphone | Supported with visible foreground service | Supported with background-audio configuration |
| Development/model experimentation | Easier | More controlled |
| Best initial role | **Primary development target** | Second client |

The iPhone 14 Pro’s A16 contains a 16-core Neural Engine and is entirely capable of STT, TTS and small classifiers. Its limitation for your roadmap is primarily memory and deployment flexibility, not speech performance.[^4_4]

## MVP loop

Use a **half-duplex state machine**:

```text
LISTENING
    ↓ VAD detects speech
CAPTURING
    ↓ 1.5–3 seconds silence
TRANSCRIBING
    ↓ final text
SPEAKING
    ↓ TTS finished + 300 ms guard time
LISTENING
```

Do not listen while TTS is playing in the first version. Otherwise the microphone will transcribe the synthetic voice and create a feedback loop.

The initial behavior can be:

```text
User: "Remember to order roofing screws"
         ↓
VAD closes utterance after silence
         ↓
STT: "Remember to order roofing screws"
         ↓
TTS: "Remember to order roofing screws"
```

You do not need streaming token-level recognition initially. Capture one utterance, close it after silence, transcribe it locally, play it back, and reopen capture.

## Fastest Pixel MVP

### Option A: system APIs

Use:

- `AudioRecord` or system recognition input.
- `SpeechRecognizer.createOnDeviceSpeechRecognizer()`.
- Android `TextToSpeech`.
- A microphone foreground service.
- Kotlin native audio code behind your Tauri plugin.

Android exposes a specific factory for an on-device recognizer. For TTS, enumerate voices and choose one where `isNetworkConnectionRequired()` is false.[^4_5][^4_6][^4_7]

This is the shortest route to a proof of concept, but Android warns that `SpeechRecognizer` is not designed as one permanently continuous recognition request. Recreate or restart recognition for each utterance, which naturally fits your pause-delimited loop.[^4_8]

### Option B: portable local stack

Use **Sherpa-ONNX** for:

- VAD.
- Streaming or utterance-based STT.
- TTS.
- Later keyword spotting and speaker identification.

Sherpa-ONNX supports fully offline VAD, ASR and TTS on both Android and iOS, with Kotlin, Swift and C/C++ APIs. That makes it an unusually good match for a Tauri native plugin because you can share model choices and potentially a C++/Rust wrapper while retaining native audio capture.[^4_9][^4_10]

A sensible first configuration is:

```text
Capture: native Kotlin AudioRecord
VAD: Silero VAD through Sherpa-ONNX
STT: small streaming Zipformer/Moonshine model
TTS: Android system TTS initially
Storage: SQLite
UI: Tauri
```

Use system TTS first because it eliminates model packaging and synthesis latency. Move to Sherpa-ONNX with Piper or Kokoro only when voice quality becomes important; Sherpa already supports offline Android TTS using those model families.[^4_11][^4_9]

## Whisper alternative

Whisper.cpp runs fully offline on Android and iOS and includes reference applications for both platforms. It is useful when you want the same multilingual recognition model everywhere, but I would not choose it for the first Pixel MVP:[^4_12][^4_13]

- Whisper is naturally better for completed audio chunks than ultra-low-latency streaming.
- You still need a separate VAD.
- Model startup, thermals and battery use require more attention.
- Native system or streaming Sherpa recognition will make the first loop easier.

Whisper.cpp becomes attractive for a second implementation or as an accuracy benchmark.

## iPhone implementation

On iOS 26, use:

- `AVAudioEngine` for microphone capture.
- `SpeechAnalyzer` with `SpeechTranscriber`, or `DictationTranscriber` if the preferred transcriber is unavailable.
- `AVSpeechSynthesizer` for local speech.
- The audio background mode.
- `AVAudioSession.playAndRecord`.

Apple’s current SpeechAnalyzer API supports microphone transcription and manages on-device speech models; `DictationTranscriber` supports older devices using the system dictation model family. Apple’s speech synthesis runs on-device, with default voices included and optional enhanced voices downloadable separately.[^4_14][^4_15][^4_16][^4_17][^4_18]

For an entirely custom and platform-symmetric stack, use Sherpa-ONNX or Whisper.cpp instead of Apple SpeechAnalyzer. But system SpeechAnalyzer plus AVSpeechSynthesizer is likely the lowest-effort native iPhone implementation.

## Command classification

You probably do **not need an LLM** to determine whether an utterance is a command, note or ordinary speech.

Start with a layered classifier:

```text
1. Explicit phrase grammar
   "remember..."
   "save this..."
   "skip..."
   "stop..."
   "ask agent..."

2. Tiny text classifier
   command | remember | question | dictation | ignore

3. Small LLM only when confidence is low
```

For five to twenty intent classes, a small encoder/classifier will be faster, more deterministic and far less battery-intensive than a generative model. You could use a quantized MiniLM/MobileBERT-style classifier or even embeddings plus nearest examples.

If you want generative parsing later, the Pixel can experiment with quantized 1B–4B models. Google AI Edge supports small models, including Gemma variants and on-device function calling, on Android and other platforms. ExecuTorch also supports LLM deployment on Android and iOS through Java and Swift bindings, although separate backend-specific model exports may be needed.[^4_19][^4_20][^4_21]

A command result should be structured:

```json
{
  "intent": "remember",
  "confidence": 0.96,
  "arguments": {
    "text": "order roofing screws"
  }
}
```

Never let the model directly execute arbitrary actions. It should select from a fixed intent schema.

## Tauri layout

```text
Tauri UI
  └─ transcript, controls, history, settings

Rust core
  ├─ state machine
  ├─ SQLite
  ├─ intent schemas
  └─ classifier/LLM interface

audio-session plugin
  ├─ Android: Kotlin AudioRecord + Media3/TTS
  └─ iOS: Swift AVAudioEngine + AVSpeechSynthesizer

shared inference library
  └─ Sherpa-ONNX or Whisper.cpp through native FFI
```

Keep PCM audio, VAD and inference outside the WebView. Send only events such as:

```text
speech_started
speech_ended
transcript_final
tts_started
tts_finished
intent_detected
```


## Practical recommendation

Start on the **Pixel 10 Pro** with:

1. Tauri UI and Rust state machine.
2. Kotlin audio plugin.
3. Android on-device `SpeechRecognizer`.
4. Android offline `TextToSpeech`.
5. Pause recognition while TTS speaks.
6. Store every utterance locally.
7. Add phrase-based `repeat`, `remember`, `discard` and `stop` intents.
8. Replace system recognition with Sherpa-ONNX only after the interaction loop works.
9. Add the iPhone backend using SpeechAnalyzer and AVSpeechSynthesizer.
10. Add a small classifier before experimenting with a generative SLM.

The hardware is more than adequate. The difficult parts will be audio routing, endpoint timing, background lifecycle and preventing feedback—not model inference.

<span style="display:none">[^4_22][^4_23][^4_24][^4_25][^4_26][^4_27][^4_28][^4_29][^4_30][^4_31][^4_32][^4_33][^4_34][^4_35][^4_36][^4_37][^4_38][^4_39][^4_40][^4_41][^4_42][^4_43][^4_44][^4_45][^4_46][^4_47][^4_48][^4_49][^4_50][^4_51][^4_52][^4_53][^4_54][^4_55][^4_56][^4_57][^4_58][^4_59][^4_60][^4_61][^4_62][^4_63][^4_64][^4_65][^4_66][^4_67][^4_68][^4_69][^4_70][^4_71][^4_72][^4_73][^4_74][^4_75][^4_76][^4_77][^4_78][^4_79][^4_80][^4_81][^4_82][^4_83][^4_84][^4_85][^4_86][^4_87][^4_88][^4_89][^4_90][^4_91][^4_92][^4_93][^4_94][^4_95][^4_96][^4_97]</span>

<div align="center">⁂</div>

[^4_1]: https://store.google.com/us/product/pixel_10_pro_specs?hl=en-US

[^4_2]: https://blog.google/products-and-platforms/devices/pixel/google-pixel-10-pro-xl/

[^4_3]: https://everymac.com/systems/apple/iphone/specs/apple-iphone-14-pro-global-a2890-specs.html

[^4_4]: https://support.apple.com/en-us/111849

[^4_5]: https://developer.android.com/reference/android/speech/SpeechRecognizer

[^4_6]: https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine

[^4_7]: https://developer.android.com/reference/android/speech/tts/Voice

[^4_8]: https://android.googlesource.com/platform/frameworks/base/+/cd92588/core/java/android/speech/SpeechRecognizer.java

[^4_9]: https://github.com/k2-fsa/sherpa-onnx

[^4_10]: https://k2-fsa.github.io/sherpa/onnx/

[^4_11]: https://github.com/k2-fsa/sherpa-onnx/discussions/3383

[^4_12]: https://github.com/ggml-org/whisper.cpp

[^4_13]: https://github.com/ggml-org/whisper.cpp/blob/master/README.md

[^4_14]: https://developer.apple.com/documentation/speech/speechanalyzer

[^4_15]: https://developer.apple.com/videos/play/wwdc2025/277/

[^4_16]: https://developer.apple.com/documentation/speech/dictationtranscriber

[^4_17]: https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoicequality/enhanced

[^4_18]: https://developer.apple.com/documentation/avfoundation/speech-synthesis

[^4_19]: https://docs.pytorch.org/executorch/stable/llm/getting-started.html

[^4_20]: https://docs.pytorch.org/executorch/0.5/llm/getting-started.html

[^4_21]: https://developers.googleblog.com/en/google-ai-edge-small-language-models-multimodality-rag-function-calling/

[^4_22]: https://support.apple.com/en-mide/111849

[^4_23]: https://store.google.com/product/pixel_10_pro?hl=en-US

[^4_24]: https://support.google.com/pixelphone/answer/7158570?hl=en

[^4_25]: https://www.gsmarena.com/google_pixel_10_pro_5g-13987.php

[^4_26]: https://www.gsmarena.com/google_pixel_10_pro-review-2877p4.php

[^4_27]: https://www.gsmarena.com/google_pixel_10_pro-review-2877.php

[^4_28]: https://everymac.com/systems/apple/iphone/specs/apple-iphone-14-pro-china-a2892-specs.html

[^4_29]: https://everymac.com/systems/apple/iphone/specs/apple-iphone-14-pro-russia-a2891-specs.html

[^4_30]: https://everymac.com/systems/apple/iphone/specs/apple-iphone-14-pro-united-states-a2650-specs.html

[^4_31]: https://www.gsmarena.com/apple_iphone_14_pro-review-2480p5.php

[^4_32]: https://fi.google.com/about/phones/pixel-10-pro-fold-specs

[^4_33]: https://developer.apple.com/documentation/speech/sfspeechrecognizer/supportsondevicerecognition

[^4_34]: https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/requiresondevicerecognition

[^4_35]: https://developer.android.com/reference/kotlin/android/speech/SpeechRecognizer

[^4_36]: https://developer.apple.com/documentation/speech/sfspeechrecognizer

[^4_37]: https://developer.apple.com/documentation/Speech/bringing-advanced-speech-to-text-capabilities-to-your-app

[^4_38]: https://devstreaming-cdn.apple.com/videos/wwdc/2019/256p7m9z4yst71ai/256/256_advances_in_speech_recognition.pdf

[^4_39]: https://developer.android.com/develop/xr/jetpack-xr-sdk/asr

[^4_40]: https://developers.google.com/android/reference/com/google/mlkit/genai/speechrecognition/SpeechRecognizer

[^4_41]: https://swiftcrafted.dev/article/speechanalyzer-speechtranscriber-ios-26-swift

[^4_42]: https://learn.microsoft.com/en-us/dotnet/api/android.speech.speechrecognizer?view=net-android-35.0

[^4_43]: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/speech/SpeechRecognizer.java

[^4_44]: https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoicequality/default?language=objc,objc

[^4_45]: https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer

[^4_46]: https://bendodson.com/weblog/2024/04/03/using-your-personal-voice-in-an-ios-app/

[^4_47]: https://developer.apple.com/documentation/avfaudio/speech-synthesis

[^4_48]: https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoice/quality

[^4_49]: https://note.com/npaka/n/n6bc285983dc7?hl=en

[^4_50]: https://gist.github.com/Koze/d1de49c24fc28375a9e314c72f7fdae4

[^4_51]: https://www.acciyo.com/text-to-speech-ios-swift-github/

[^4_52]: https://learn.microsoft.com/en-gb/dotnet/api/android.speech.tts.voice.isnetworkconnectionrequired?view=net-android-35.0

[^4_53]: https://github.com/Ryankaya/ios-avspeechsynthesizer-voiceforge-20260516-100000-c4d5e6

[^4_54]: https://spokio.pro/apple-voice-tts-stack-mac

[^4_55]: https://executorch.ai/

[^4_56]: https://docs.pytorch.org/executorch/stable/index.html

[^4_57]: https://github.com/Fereshte874/whisper.cpp.android

[^4_58]: https://pytorch.org/blog/introducing-executorch-1-0/

[^4_59]: https://docs.pytorch.org/executorch/stable/user-pathways.html

[^4_60]: https://docs.pytorch.org/executorch/main/\_sources/llm/getting-started.md.txt

[^4_61]: https://android.googlesource.com/platform/external/executorch/+show/3826eeeae7c3af97a5bfbc23776ca76bd19b9c91/README.md

[^4_62]: https://docs.pytorch.org/executorch/0.7/llm/getting-started.html

[^4_63]: https://github.com/liam-mceneaney/androidwhisper.cpp

[^4_64]: https://docs.pytorch.org/executorch/stable/edge-platforms-section.html

[^4_65]: https://github.com/kardianos/whisper.cpp

[^4_66]: https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/README.md

[^4_67]: https://github.com/k2-fsa/sherpa-onnx/releases

[^4_68]: https://github.com/leecheedoo/sherpa-onnx-offline-asr

[^4_69]: https://github.com/therealtimex/sherpa-onnx-tts-stt

[^4_70]: https://github.com/XDcobra/react-native-sherpa-onnx

[^4_71]: https://pub.dev/packages/vad

[^4_72]: https://github.com/Made-in-Jurgistan/mobile-stt-engineering-guide

[^4_73]: https://raw.githubusercontent.com/csukuangfj/sherpa-onnx/ff7fb7d3de74ef385a5aa45749b33e3449a9072b/CHANGELOG.md

[^4_74]: https://pub.dev/packages/vad/versions/0.0.7

[^4_75]: https://www.nuget.org/packages/SileroVad

[^4_76]: https://deepwiki.com/gkonovalov/android-vad/3.2-silero-vad

[^4_77]: https://developer.android.com/about/versions/14/changes/fgs-types-required

[^4_78]: https://developer.android.com/develop/background-work/services/fgs/service-types

[^4_79]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

[^4_80]: https://developer.android.com/about/versions/17/changes/bg-audio

[^4_81]: https://developer.android.com/about/versions/15/behavior-changes-15

[^4_82]: https://developer.android.com/develop/background-work/services/fgs/launch

[^4_83]: https://developer.apple.com/documentation/technologyoverviews/audio-and-music

[^4_84]: https://developer.android.com/about/versions/11/privacy/foreground-services

[^4_85]: https://developer.android.com/about/versions/oreo/background

[^4_86]: https://developer.android.com/develop/background-work/services/fgs

[^4_87]: https://developer.apple.com/documentation/avfaudio/avaudiosession

[^4_88]: https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback

[^4_89]: https://developer.apple.com/videos/play/wwdc2025/251/

[^4_90]: https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/record

[^4_91]: https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-29/+/refs/heads/androidx-drawerlayout-release/android/speech/SpeechRecognizer.java

[^4_92]: https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-transition-release/android/speech/SpeechRecognizer.java

[^4_93]: https://tessl.io/registry/dpearson2699/swift-ios-skills/3.9.1/files/skills/speech-recognition/SKILL.md

[^4_94]: https://tessl.io/registry/dpearson2699/swift-ios-skills/3.6.0/files/skills/speech-recognition/SKILL.md

[^4_95]: https://developer.apple.com/jp/videos/play/wwdc2016/509/

[^4_96]: https://www.linkedin.com/posts/addpipe_a-quick-look-at-apples-speechanalyzer-api-activity-7495069236910944257-BFsF

[^4_97]: https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/android/speech/SpeechRecognizer.java

