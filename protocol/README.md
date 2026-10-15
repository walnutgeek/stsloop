# The stsloop protocol

The contract between the phone and the hub
([ADR-0006](../docs/adr/0006-protocol-contract-and-versioning.md)): one JSON
Schema per message, and golden fixtures that every implementation's tests
parse. Neither side shares code with the other; they agree here.

```
schemas/v1/<type>.schema.json      one per message type, JSON Schema 2020-12
fixtures/v1/<type>/<case>.json     canonical messages; "type" equals the directory
fixtures/v1/message/<case>.json    messages of types no schema knows (to be ignored)
fixtures/v1/exchanges.json         conversations, in order: frames, idle time, closes, HTTP answers
fixtures/v1/equivalents.json       fixtures with extra fields, and the fixture each reads as
```

`uv run scripts/validate_protocol.py` checks every fixture against its schema
and the manifests against the fixtures; CI runs it.

## The wire, v1

One WebSocket, phone to hub, one JSON object per text frame, each with a
string `type`.

1. **Pairing.** `stsloop-hub init` shows a QR code holding a `pairing` message
   as compact JSON: the hub's `url` and a `token`.
2. **Authorisation.** The phone opens `url` with the header
   `Authorization: Bearer <token>`. A wrong or missing token gets HTTP 401, and
   no WebSocket.
3. **Handshake.** The phone sends `hello` (its `protocol` major and minor, and
   its `capabilities`). The hub answers `welcome` (the version and the
   capabilities it will use) or `refusal` (a `reason`, a sentence fit to be
   spoken, and the versions it supports), and after a refusal closes with code
   1008. A hub that gets no `hello` within 10 s closes with 1008 too.
4. **Descriptors.** With the `descriptors` capability, the hub then sends a
   `descriptors` snapshot, and a new one whenever a Bucket comes, goes or
   changes. Each snapshot replaces the last.
5. **Requests.** For a command whose Primitive is `request`, the phone sends
   a `request` naming the Descriptor's `id` and the command's `id`, and the
   hub answers it with one `reply` carrying the same `id`: a sentence to
   speak, and a `reason` when the command could not run. A command marked
   `anywhere` is heard whatever has Focus ("what's blocked"); the hub puts
   such commands on its own `hub` Bucket. No capability is needed: a hub
   that declares a `request` command answers it. The phone waits 5 s for a
   reply, or as long as the command's `reply_within_ms` asks (up to 60 s),
   then says the hub did not answer. A hub answers each request as soon as
   it can, so replies may come in another order than their requests; a
   slow one ("what does it say", which asks a model) holds up no other.
6. **Corpus upload.** With the `corpus` capability (a hub offers it when it
   has a Corpus directory to store into), the phone offers each Turn the hub
   has not yet acknowledged, oldest first, one at a time:
   - `turn_offer`: the Turn's directory name, its `turn.json` text, and the
     length and SHA-256 of its Recording. The hub answers `turn_ack`:
     `stored` if it already holds that same Turn (a re-upload is a no-op),
     `send` with `offset` 0, or `rejected` with a `reason`.
   - `turn_audio`: one base64 piece from the `offset` asked for, with `last`
     on the final one. The hub answers each with `send` and the next offset,
     and the last with `stored` (the whole file matched the offer's length and
     SHA-256) or `rejected`.

   Only one piece is ever in flight, so a live message on the same socket
   waits behind at most one. A Turn is idempotent by its directory name;
   another Turn under a held name is a `conflict`, and the held one is kept.
   A dropped connection drops the Turn on offer; it is offered again, from the
   start. The hub stores the Turn as the phone wrote it,
   `<corpus>/<started_at>-<id>/{turn.json,audio.wav}`, so
   `scripts/corpus_load.py` reads the hub's Corpus unchanged.
7. **Sends.** The `draft` Primitive's "send" delivers a Draft to an agent
   Bucket: the phone sends `send` with the Descriptor's `id`, the `text`, and
   an idempotency `key` (128 random bits, new for every "send"). The hub
   answers one `sent` carrying the same `key`: a sentence to speak, and no
   `reason` when the Draft was delivered (`unknown_bucket` or `refused`: not
   sent; `uncertain`: it may have arrived). The hub tries each key at most
   once, ever, recording it before it delivers (`sends.jsonl`, which survives
   a restart), and answers a key it has seen with the answer it gave the
   first time. So the phone sends the same `send` again on every welcome
   until a `sent` answers it, and a reconnect never delivers a Draft twice.
   The `draft` commands themselves ("read it back", "send", "cancel": ids
   `read_back`, `send`, `cancel`) run on the phone. No capability is needed:
   a hub that declares a `draft` command answers `send`.
8. **Items.** The hub sends an `item` when a Bucket that uses `speak_item`
   raises one (an agent has become blocked): an `id`, the `buckets` it is
   about, and one sentence to `say`. Changes the hub sees together are one
   Item. The phone says it after an earcon in the next Silence, and then
   sends `item_spoken` with its `id`. After each welcome (and its
   `descriptors`), the hub sends again every Item not yet spoken that still
   holds, and the phone ignores an `id` it already has, answering
   `item_spoken` again for one it has said: a reconnect never repeats an
   alert. A muted Bucket raises nothing; the hub adds `quiet` and `unmute`
   `request` commands to every Bucket that uses `speak_item`, answers them
   itself, and keeps the mutes across restarts. No capability is needed.

9. **Summaries.** With the `summaries` capability (a hub always offers
   it), "summarize bucket" on a capture Bucket sends `summarize`: an `id`,
   the Bucket's name, the Turn directory name its last summary covered up
   to (`since`, absent the first time), and the Notes' words, oldest first
   (`notes`, at most about 50; `more` when older ones were left out). The
   phone sends the words itself, because the newest Notes may not be
   uploaded yet. The hub answers one `reply` with the same `id`: a model's
   summary, or, when there is no model or it fails, the Notes read raw with
   the reason `fallback`. The phone waits 30 s, then reads them raw itself,
   and counts the Notes as summarised only once a reply without a reason
   has been spoken. Under an agent Bucket's Focus the same phrases send its
   `read` `request` instead.
10. **Status.** "Hub status" and "hub details", the loop's own commands, send
    `status_request` (an `id`) while the hub is online. The hub answers one
    `status_report` with the same `id`, at once, as facts: the agent Buckets
    it publishes, how many are blocked, and, when it knows, herdr's `state`
    and `version` and whether its Claude Code `hooks` are installed. The
    phone words them itself, adding what only it knows (how long it has
    been connected, how many Turns wait to upload), so the answer sounds the
    same as the one it gives alone while the hub is offline. No capability
    is needed: a hub that does not know `status_request` ignores it, and the
    phone says, after 5 s, that the hub did not answer.

The hub serves one phone at a time: once a new connection is welcomed, the
hub closes the older one (code 1000). A refused connection replaces nothing.

## Rules for every receiver

- **Ignore fields you do not know**, at any depth. `equivalents.json` pairs a
  fixture carrying extras with the fixture it must read as.
- **Ignore messages whose type you do not know**, before and after the
  handshake. Fixtures under `message/` are such messages.
- An unknown Primitive, Bucket type or refusal reason is not an error: see the
  schema descriptions for how each degrades.
- Within a major, changes are additive only. When a major is bumped, the
  previous major's fixtures are frozen in `fixtures/v<N-1>/` and the hub keeps
  passing them.

## Conformance tests

Every implementation's tests load every fixture and:

- parse each message of a known type; re-encoding a fixture that is not on the
  left of `equivalents.json` gives the fixture back, compared as parsed JSON
  (key order and whitespace do not matter);
- parse each `equivalents.json` pair to equal values;
- for each message they send, produce output equal, as parsed JSON, to the
  fixture `exchanges.json` names for that step.

The hub's are in `hub/tests/test_fixtures.py`; a hub publishing only the stub
Bucket, with an empty Corpus, answers every exchange exactly (an exchange with
`after` runs on the hub the named earlier exchange left behind).
`descriptors/herdr.json` and the `reply/` fixtures are what the hub's herdr
Provider produces for a fake herdr and a fake summariser
(`hub/tests/test_agents.py`), `descriptors/claude-code.json` and
`reply/needs-herdr.json` what it publishes and answers for a Claude Code
session its hooks report without herdr (`hub/tests/test_claude_code.py`,
which replays recorded hook payloads), the `sent/` fixtures what it answers the
`send/` ones with (`hub/tests/test_sends.py`), the `item/` fixtures
what it raises (`hub/tests/test_alerts.py`), and `reply/summary.json` and
`reply/summary-fallback.json` what it answers `summarize/basic.json` with,
given a fake summariser and none (`hub/tests/test_summary.py`), and
`status_report/herdr.json` what `serve` reports for a fake herdr with its hooks
installed (`hub/tests/test_cli.py`). The phone's
are in `core/src/test/kotlin/com/walnutgeek/stsloop/core/hub/`
(`ProtocolFixturesTest`; `HubLinkTest`, which plays each exchange's hub steps
to the phone and checks where it ends up: online, refused, offline and
retrying, or unauthorised; and `CorpusUploaderTest`, which plays the hub steps
of each upload exchange to the uploader, with the offered Turns in its Corpus,
and checks it sends exactly the phone steps). The phone sends one message,
`hello/basic.json`, whatever phone a given exchange stands for, then sends the
exchange's `request`s, `send`s, `summarize`s and `status_request`s itself, each equal to its
fixture (`DraftPrimitiveTest` checks the phone's own send against
`send/basic.json`, and `BucketSummariesTest` its own summarize against
`summarize/basic.json`, and its raw reading against the hub's in
`reply/summary-fallback.json`; `HubStatusCommandTest` its own
`status_request` against `status_request/basic.json`).
