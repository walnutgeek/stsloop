---
status: accepted
---

# The phone and the hub meet at JSON fixtures, versioned by handshake

The phone is Kotlin and the hub is Python ([ADR-0008](./0008-python-hub.md)),
and Providers may be anything that can make an HTTP call (a Claude Code hook
is a shell command). So the contract cannot be shared code. There are three
contracts:

1. **The wire**, phone ↔ hub over one WebSocket: the pairing handshake,
   Descriptors, Items, commands and sends, Corpus upload.
2. **The Corpus format**, files on disk, already at `"schema": 1` and already
   written in Kotlin and read in Python (`scripts/corpus_load.py`).
3. **The Provider interface**, hub ↔ Provider.

Each is defined in `protocol/` as **JSON, one JSON Schema per message, and
golden fixtures**: canonical example messages per version. Every
implementation's tests must parse every fixture and produce equivalent output
for the messages it emits. A phone and a hub can drift only if one of them
fails a shared test.

## Versioning

- The phone's `hello` carries `protocol: {major, minor}` and a list of
  capabilities; the hub answers with what it will use.
- Within a major, changes are **additive only**: new optional fields and new
  message types. Receivers ignore fields and message types they do not know.
- A breaking change bumps the major. **The hub supports the current major and
  the previous one; the phone supports only its own.** Phones update slowly
  (sideloaded APKs, later F-Droid); hubs update with a package upgrade, so the
  compatibility code lives where updating is cheap. The previous major's
  fixtures are frozen in `protocol/fixtures/v<N-1>/`, and the hub's CI runs
  against them.
- The Corpus keeps its own `schema` number, with the same additive rule.

## Primitives keep Descriptors data

The phone knows a fixed set of Primitives (`note`, `draft`, `speak_item`,
`request`). A Descriptor composes them, so a Provider can add Bucket types
without a phone release. Only a new Primitive needs a minor protocol bump. A
Descriptor naming a Primitive the phone does not know degrades to its plain
`request` commands, and the loop says so once.

## Considered options

- **Shared code** (`:core` types as the contract). Only works with a JVM hub,
  and not for hooks or third-party Providers.
- **An IDL with codegen** (protobuf, gRPC). Typed bindings everywhere, at the
  cost of a toolchain and payloads that cannot be read in a log or written
  with `curl`.
- **Versioned endpoints** (`/v1/…`). Does not fit a single WebSocket.
- **Lockstep versions.** A phone that missed an update would stop working,
  against "degrade, don't fail".

## Deferred

- **Primitives as behaviour mixins**, and later **a Provider declaring its own
  behaviour**. Primitives are the smallest step that keeps the loop local and
  the grammar deterministic; richer composition waits for a Provider that
  needs it.
- Splitting `protocol/` into its own repo, if third-party Providers want it on
  its own. Until then the monorepo (`app/`, `core/`, `hub/`, `protocol/`, one
  version tag) lets one PR change a fixture, the phone and the hub together.
