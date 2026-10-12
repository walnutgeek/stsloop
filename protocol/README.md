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
   that declares a `request` command answers it.

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
Bucket answers every exchange exactly. `descriptors/herdr.json` and the
`reply/` fixtures are what the hub's herdr Provider produces for a fake herdr
(`hub/tests/test_agents.py`). The phone's are in
`core/src/test/kotlin/com/walnutgeek/stsloop/core/hub/`
(`ProtocolFixturesTest`, and `HubLinkTest`, which plays each exchange's hub
steps to the phone and checks where it ends up: online, refused, offline and
retrying, or unauthorised). The phone sends one message, `hello/basic.json`,
whatever phone a given exchange stands for, then sends the exchange's
`request`s itself, each equal to its fixture.
