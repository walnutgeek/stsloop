"""Protocol v1 messages and the handshake rules (ADR-0006).

The contract is `protocol/` at the repo root: one JSON Schema per message and
golden fixtures. This module is the hub's reading of it:

- `decode` / `from_json` turn a frame into a typed message. Unknown fields are
  ignored, and a message of unknown type decodes to `None`, so the caller
  ignores it. A message of a known type that breaks its schema raises
  `BadMessage`.
- `encode` / `to_json` turn a message back into compact JSON.
- `negotiate` answers a `Hello` with a `Welcome` or a `Refusal`.

Corpus upload (the `corpus` capability) is `TurnOffer` and `TurnAudio` from the
phone, each answered by a `TurnAck`; `corpus.py` is what the hub does with them.
Summaries (the `summaries` capability) are `Summarize` from the phone, each
answered by a `Reply`; `summary.NoteSummaries` words them.
"""

from __future__ import annotations

import base64
import binascii
import json
import re
from dataclasses import dataclass, field
from typing import Union


@dataclass(frozen=True)
class Version:
    major: int
    minor: int


#: Every version the hub speaks, newest first, one per major: the current major
#: and, once there is one, the previous (ADR-0006, "Versioning").
SUPPORTED: tuple[Version, ...] = (Version(1, 0),)

#: The capabilities the hub can use, in the order it lists them in `welcome`.
CORPUS = "corpus"
SUMMARIES = "summaries"
CAPABILITIES: tuple[str, ...] = ("descriptors", CORPUS, SUMMARIES)

#: WebSocket close code sent right after a `refusal`, or when no hello arrives.
POLICY_VIOLATION = 1008


@dataclass(frozen=True)
class Hello:
    protocol: Version
    capabilities: tuple[str, ...]


@dataclass(frozen=True)
class Welcome:
    protocol: Version
    capabilities: tuple[str, ...]


@dataclass(frozen=True)
class Refusal:
    reason: str
    message: str
    supported: tuple[Version, ...] = ()


@dataclass(frozen=True)
class Command:
    id: str
    phrases: tuple[str, ...]
    primitive: str
    #: A command whatever has Focus, not only while its Bucket has it.
    anywhere: bool = False
    #: How long the phone waits for this command's reply, when longer than its default (a summary).
    reply_within_ms: int | None = None


#: Dialog kinds (`descriptors.schema.json`, `dialog`): only a question can be answered by voice.
QUESTION_DIALOG, PERMISSION_DIALOG, UNKNOWN_DIALOG = "question", "permission", "unknown"


@dataclass(frozen=True)
class DialogQuestion:
    """One question of a question dialog: its text, its options' labels in order, and whether it lets the person
    pick several (`multi_select`) or type their own answer (`other`)."""

    question: str
    options: tuple[str, ...]
    multi_select: bool = False
    other: bool = False


@dataclass(frozen=True)
class Dialog:
    """The dialog an agent Bucket is blocked on: a `question` (with its `id` and `questions`), a `permission`
    prompt, or `unknown`."""

    kind: str
    id: str | None = None
    questions: tuple[DialogQuestion, ...] = ()


@dataclass(frozen=True)
class Descriptor:
    id: str
    name: str
    aliases: tuple[str, ...]
    type: str
    primitives: tuple[str, ...]
    commands: tuple[Command, ...] = field(default=())
    #: The dialog the agent is blocked on, while it is; None otherwise.
    dialog: Dialog | None = None


@dataclass(frozen=True)
class Descriptors:
    buckets: tuple[Descriptor, ...]


@dataclass(frozen=True)
class Pairing:
    protocol: Version
    url: str
    token: str


@dataclass(frozen=True)
class Request:
    """Phone to hub: run the `request` command `command` of the Bucket whose Descriptor id is `bucket`."""

    id: str
    bucket: str
    command: str


@dataclass(frozen=True)
class Reply:
    """Hub to phone: the answer to the request or summarize `id`, as one or two sentences to `say`.
    `reason` is None when the command ran (see reply.schema.json)."""

    id: str
    say: str
    reason: str | None = None


#: A `Reply` reason: the summariser did not answer a `Summarize`, so `say` reads the Notes raw.
FALLBACK = "fallback"


@dataclass(frozen=True)
class SummaryNote:
    """One Note sent for a summary: its Turn directory name, and its words."""

    turn: str
    text: str


@dataclass(frozen=True)
class Summarize:
    """Phone to hub, with the `summaries` capability: summarise `notes` (oldest first), said in the capture
    Bucket `bucket` since its last summary, which covered Notes up to the Turn `since` (None: its first, over
    the last day). `more` says older Notes in that range were left out (see summarize.schema.json)."""

    id: str
    bucket: str
    notes: tuple[SummaryNote, ...]
    since: str | None = None
    more: bool = False


@dataclass(frozen=True)
class TurnOffer:
    """One Turn of the phone's Corpus, offered for upload: its directory name, its
    `turn.json` text, and the length and SHA-256 of the Recording to come."""

    turn: str
    turn_json: str
    sha256: str
    bytes: int


@dataclass(frozen=True)
class TurnAudio:
    """One piece of the Recording of the Turn on offer, starting at `offset`."""

    turn: str
    offset: int
    data: bytes
    last: bool


#: TurnAck statuses known in v1.0; a phone treats any other as "rejected".
SEND, STORED, REJECTED = "send", "stored", "rejected"


@dataclass(frozen=True)
class TurnAck:
    """The hub's answer to each offer and each piece: `send` from `offset`, `stored`, or `rejected`."""

    turn: str
    status: str
    offset: int | None = None
    reason: str | None = None
    message: str | None = None


@dataclass(frozen=True)
class Choice:
    """One question's answer: the `option` picked (1 is the first), or a free-text `text`."""

    option: int | None = None
    text: str | None = None


@dataclass(frozen=True)
class Answer:
    """A send's answer to the question dialog `dialog`: one choice per question, in order."""

    dialog: str
    choices: tuple[Choice, ...]


@dataclass(frozen=True)
class Send:
    """Phone to hub: deliver the Draft `text` to the agent Bucket whose Descriptor id is `bucket`, at most once
    for its idempotency `key` (see send.schema.json). With `answer`, it answers the agent's question dialog
    instead, and `text` is the answer as the person heard it read back."""

    key: str
    bucket: str
    text: str
    answer: Answer | None = None


@dataclass(frozen=True)
class Sent:
    """Hub to phone: the answer to the send `key`. `reason` is None when the Draft was delivered
    (see sent.schema.json)."""

    key: str
    say: str
    reason: str | None = None


#: The shortest idempotency key the hub takes: 128 random bits as hex is 32 characters.
MIN_KEY = 16


@dataclass(frozen=True)
class Item:
    """Hub to phone: an Item for the Attention Queue, said in the next Silence after an earcon.
    `buckets` are the Descriptor ids it is about; `id` is unique across hub restarts."""

    id: str
    buckets: tuple[str, ...]
    say: str


@dataclass(frozen=True)
class ItemSpoken:
    """Phone to hub: the Item `id` has been said; the hub stops sending it."""

    id: str


@dataclass(frozen=True)
class StatusRequest:
    """Phone to hub: how is the hub? ("hub status", "hub details"). Answered by one `StatusReport`."""

    id: str


#: The Descriptor type of an agent Bucket, which a `StatusReport` counts.
AGENT = "agent"

#: herdr states a `StatusReport` names (status_report.schema.json); the field is open, so a phone degrades.
HERDR_OK = "ok"
HERDR_NOT_ANSWERING = "not_answering"
HERDR_PROTOCOL_MISMATCH = "protocol_mismatch"
HERDR_NOT_INSTALLED = "not_installed"
HERDR_CHECKING = "checking"


@dataclass(frozen=True)
class HerdrReport:
    """How the hub's herdr is doing: `state` (`HERDR_*`, or a newer one) and the binary's `version`."""

    state: str
    version: str | None = None


@dataclass(frozen=True)
class StatusReport:
    """Hub to phone: the answer to the status request `id`, as facts the phone words itself. `herdr` and
    `hooks_installed` are None when the hub has nothing to say about them."""

    id: str
    agents: int
    blocked: int
    herdr: HerdrReport | None = None
    hooks_installed: bool | None = None


Message = Union[
    Hello, Welcome, Refusal, Descriptors, Pairing, Request, Reply, Summarize, TurnOffer, TurnAudio, TurnAck, Send, Sent,
    Item, ItemSpoken, StatusRequest, StatusReport,
]


class BadMessage(ValueError):
    """A frame that is not a message, or a known message that breaks its schema.
    `message_type` is the frame's type when it had one."""

    def __init__(self, problem: str, message_type: str | None = None):
        super().__init__(problem)
        self.message_type = message_type


# --- the handshake ---------------------------------------------------------


def negotiate(
    hello: Hello,
    supported: tuple[Version, ...] = SUPPORTED,
    capabilities: tuple[str, ...] = CAPABILITIES,
) -> Welcome | Refusal:
    """The hub's answer to `hello`: the phone's major at the lower minor, and the
    `capabilities` of the hub's that the phone also has, or a refusal naming what
    the hub speaks."""
    for version in supported:
        if version.major == hello.protocol.major:
            return Welcome(
                protocol=Version(version.major, min(version.minor, hello.protocol.minor)),
                capabilities=tuple(c for c in capabilities if c in hello.capabilities),
            )
    majors = sorted(v.major for v in supported)
    speaks = " or ".join(str(m) for m in majors)
    fix = "Upgrade the hub." if hello.protocol.major > majors[-1] else "Update the phone app."
    return Refusal(
        reason="unsupported_protocol",
        message=f"The hub speaks protocol {speaks} and the phone speaks {hello.protocol.major}. {fix}",
        supported=supported,
    )


#: The answer to a hello the hub cannot read; the details go to the hub's log.
BAD_HELLO = Refusal(reason="bad_hello", message="The hub could not read the phone's hello. Update the phone app.")


# --- decoding ----------------------------------------------------------------


def _no_duplicates(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise BadMessage(f"duplicate key {key!r}")
        obj[key] = value
    return obj


def decode(text: str | bytes) -> Message | None:
    """Parse one text frame. `None` means a message of a type the hub does not know."""
    try:
        obj = json.loads(text, object_pairs_hook=_no_duplicates)
    except (ValueError, UnicodeDecodeError) as e:
        raise BadMessage(f"not JSON: {e}") from None
    return from_json(obj)


def from_json(obj) -> Message | None:
    if not isinstance(obj, dict) or not isinstance(obj.get("type"), str) or not obj["type"]:
        raise BadMessage("not a message: an object with a string type")
    reader = _READERS.get(obj["type"])
    if reader is None:
        return None
    try:
        return reader(obj)
    except BadMessage as e:
        raise BadMessage(str(e), obj["type"]) from None


def _get(obj: dict, key: str, kind: type, what: str):
    value = obj.get(key)
    if not isinstance(value, kind) or (kind is int and isinstance(value, bool)):
        raise BadMessage(f"{what} needs {key} as {kind.__name__}")
    return value


def _string(obj: dict, key: str, what: str) -> str:
    value = _get(obj, key, str, what)
    if not value:
        raise BadMessage(f"{what} has an empty {key}")
    return value


def _strings(obj: dict, key: str, what: str, min_items: int = 0) -> tuple[str, ...]:
    values = _get(obj, key, list, what)
    if len(values) < min_items or not all(isinstance(v, str) and v for v in values):
        raise BadMessage(f"{what} needs {key} as a list of non-empty strings")
    return tuple(values)


def _version(obj: dict, key: str, what: str) -> Version:
    value = _get(obj, key, dict, what)
    major, minor = (_get(value, k, int, f"{what}'s {key}") for k in ("major", "minor"))
    if major < 0 or minor < 0:
        raise BadMessage(f"{what}'s {key} is negative")
    return Version(major, minor)


def _command(obj, what: str) -> Command:
    if not isinstance(obj, dict):
        raise BadMessage(f"{what} is not an object")
    return Command(
        id=_string(obj, "id", what),
        phrases=_strings(obj, "phrases", what, min_items=1),
        primitive=_string(obj, "primitive", what),
        anywhere=_get(obj, "anywhere", bool, what) if "anywhere" in obj else False,
        reply_within_ms=_reply_within(obj, what),
    )


def _reply_within(obj: dict, what: str) -> int | None:
    if "reply_within_ms" not in obj:
        return None
    value = _get(obj, "reply_within_ms", int, what)
    if value < 1:
        raise BadMessage(f"{what}'s reply_within_ms is not positive")
    return value


def _flag(obj: dict, key: str, what: str) -> bool:
    return _get(obj, key, bool, what) if key in obj else False


def _dialog(obj: dict, what: str) -> Dialog:
    kind = _string(obj, "kind", what)
    questions = []
    if kind == QUESTION_DIALOG or "questions" in obj:
        for q in _get(obj, "questions", list, what):
            if not isinstance(q, dict):
                raise BadMessage(f"{what}'s question is not an object")
            qw = f"{what}'s question"
            questions.append(DialogQuestion(
                question=_string(q, "question", qw),
                options=_strings(q, "options", qw, min_items=1),
                multi_select=_flag(q, "multi_select", qw),
                other=_flag(q, "other", qw),
            ))
        if not questions:
            raise BadMessage(f"{what} needs at least one question")
    return Dialog(
        kind=kind,
        id=_string(obj, "id", what) if kind == QUESTION_DIALOG or "id" in obj else None,
        questions=tuple(questions),
    )


def _answer(obj: dict) -> Answer:
    what = "send's answer"
    choices = []
    for c in _get(obj, "choices", list, what):
        if not isinstance(c, dict) or ("option" in c) == ("text" in c):
            raise BadMessage(f"{what}'s choice needs an option or a text")
        if "option" in c:
            option = _get(c, "option", int, f"{what}'s choice")
            if option < 1:
                raise BadMessage(f"{what}'s choice has an option below 1")
            choices.append(Choice(option=option))
        else:
            choices.append(Choice(text=_string(c, "text", f"{what}'s choice")))
    if not choices:
        raise BadMessage(f"{what} needs at least one choice")
    return Answer(dialog=_string(obj, "dialog", what), choices=tuple(choices))


def _descriptor(obj) -> Descriptor:
    if not isinstance(obj, dict):
        raise BadMessage("a Descriptor is not an object")
    what = f"Descriptor {obj.get('id')!r}"
    descriptor = Descriptor(
        id=_string(obj, "id", what),
        name=_string(obj, "name", what),
        aliases=_strings(obj, "aliases", what),
        type=_string(obj, "type", what),
        primitives=_strings(obj, "primitives", what),
        commands=tuple(_command(c, f"{what}'s command") for c in _get(obj, "commands", list, what)),
        dialog=_dialog(_get(obj, "dialog", dict, what), f"{what}'s dialog") if "dialog" in obj else None,
    )
    for command in descriptor.commands:
        if command.primitive not in descriptor.primitives:
            raise BadMessage(f"{what}'s command {command.id!r} uses {command.primitive!r}, not in its primitives")
    return descriptor


#: A Turn directory name, `<started_at>-<id>` (docs/phase0.md, "Corpus format").
TURN_NAME = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z-[0-9a-f]{1,32}")
_SHA256 = re.compile(r"[0-9a-f]{64}")


def _count(obj: dict, key: str, what: str) -> int:
    value = _get(obj, key, int, what)
    if value < 0:
        raise BadMessage(f"{what} has a negative {key}")
    return value


def _turn_name(obj: dict, key: str, what: str) -> str:
    name = _string(obj, key, what)
    if not TURN_NAME.fullmatch(name):
        raise BadMessage(f"{what}'s {key} {name!r} is not a Turn directory name")
    return name


def _summarize(o: dict) -> Summarize:
    notes = []
    for n in _get(o, "notes", list, "summarize"):
        if not isinstance(n, dict):
            raise BadMessage("a summarize's note is not an object")
        notes.append(SummaryNote(turn=_turn_name(n, "turn", "summarize's note"), text=_string(n, "text", "summarize's note")))
    if not notes:
        raise BadMessage("summarize needs at least one note")
    return Summarize(
        id=_string(o, "id", "summarize"),
        bucket=_string(o, "bucket", "summarize"),
        notes=tuple(notes),
        since=_turn_name(o, "since", "summarize") if "since" in o else None,
        more=_get(o, "more", bool, "summarize") if "more" in o else False,
    )


def _turn_offer(o: dict) -> TurnOffer:
    turn = _string(o, "turn", "turn_offer")
    if not TURN_NAME.fullmatch(turn):
        raise BadMessage(f"turn_offer's turn {turn!r} is not a Turn directory name")
    audio = _get(o, "audio", dict, "turn_offer")
    sha256 = _string(audio, "sha256", "turn_offer's audio")
    if not _SHA256.fullmatch(sha256):
        raise BadMessage("turn_offer's audio needs sha256 as 64 lowercase hex digits")
    turn_json = _get(o, "turn_json", str, "turn_offer")
    if len(turn_json) < 2:
        raise BadMessage("turn_offer has an empty turn_json")
    return TurnOffer(turn=turn, turn_json=turn_json, sha256=sha256, bytes=_count(audio, "bytes", "turn_offer's audio"))


def _turn_audio(o: dict) -> TurnAudio:
    try:
        data = base64.b64decode(_get(o, "data", str, "turn_audio"), validate=True)
    except binascii.Error as e:
        raise BadMessage(f"turn_audio's data is not base64: {e}") from None
    return TurnAudio(
        turn=_string(o, "turn", "turn_audio"),
        offset=_count(o, "offset", "turn_audio"),
        data=data,
        last=_get(o, "last", bool, "turn_audio"),
    )


def _turn_ack(o: dict) -> TurnAck:
    status = _string(o, "status", "turn_ack")
    return TurnAck(
        turn=_string(o, "turn", "turn_ack"),
        status=status,
        offset=_count(o, "offset", "turn_ack") if "offset" in o or status == SEND else None,
        reason=_string(o, "reason", "turn_ack") if "reason" in o or status == REJECTED else None,
        message=_string(o, "message", "turn_ack") if "message" in o else None,
    )


_READERS = {
    "hello": lambda o: Hello(
        protocol=_version(o, "protocol", "hello"),
        capabilities=_strings(o, "capabilities", "hello"),
    ),
    "welcome": lambda o: Welcome(
        protocol=_version(o, "protocol", "welcome"),
        capabilities=_strings(o, "capabilities", "welcome"),
    ),
    "refusal": lambda o: Refusal(
        reason=_string(o, "reason", "refusal"),
        message=_string(o, "message", "refusal"),
        supported=tuple(
            _version({"v": v}, "v", "refusal's supported") for v in _get(o, "supported", list, "refusal")
        ) if "supported" in o else (),
    ),
    "descriptors": lambda o: Descriptors(
        buckets=tuple(_descriptor(d) for d in _get(o, "buckets", list, "descriptors")),
    ),
    "pairing": lambda o: Pairing(
        protocol=_version(o, "protocol", "pairing"),
        url=_string(o, "url", "pairing"),
        token=_string(o, "token", "pairing"),
    ),
    "request": lambda o: Request(
        id=_string(o, "id", "request"),
        bucket=_string(o, "bucket", "request"),
        command=_string(o, "command", "request"),
    ),
    "reply": lambda o: Reply(
        id=_string(o, "id", "reply"),
        say=_string(o, "say", "reply"),
        reason=_string(o, "reason", "reply") if "reason" in o else None,
    ),
    "summarize": _summarize,
    "turn_offer": _turn_offer,
    "turn_audio": _turn_audio,
    "turn_ack": _turn_ack,
    "send": lambda o: Send(
        key=_key(o, "send"),
        bucket=_string(o, "bucket", "send"),
        text=_string(o, "text", "send"),
        answer=_answer(_get(o, "answer", dict, "send")) if "answer" in o else None,
    ),
    "sent": lambda o: Sent(
        key=_string(o, "key", "sent"),
        say=_string(o, "say", "sent"),
        reason=_string(o, "reason", "sent") if "reason" in o else None,
    ),
    "item": lambda o: Item(
        id=_string(o, "id", "item"),
        buckets=_strings(o, "buckets", "item", min_items=1),
        say=_string(o, "say", "item"),
    ),
    "item_spoken": lambda o: ItemSpoken(id=_string(o, "id", "item_spoken")),
    "status_request": lambda o: StatusRequest(id=_string(o, "id", "status_request")),
    "status_report": lambda o: _status_report(o),
}


def _status_report(o: dict) -> StatusReport:
    herdr = None
    if "herdr" in o:
        h = _get(o, "herdr", dict, "status_report")
        herdr = HerdrReport(
            state=_string(h, "state", "status_report's herdr"),
            version=_string(h, "version", "status_report's herdr") if "version" in h else None,
        )
    hooks = _get(_get(o, "hooks", dict, "status_report"), "installed", bool, "status_report's hooks") if "hooks" in o else None
    return StatusReport(
        id=_string(o, "id", "status_report"),
        agents=_count(o, "agents", "status_report"),
        blocked=_count(o, "blocked", "status_report"),
        herdr=herdr,
        hooks_installed=hooks,
    )


def _key(obj: dict, what: str) -> str:
    key = _string(obj, "key", what)
    if len(key) < MIN_KEY:
        raise BadMessage(f"{what} needs a key of at least {MIN_KEY} characters")
    return key


# --- encoding ----------------------------------------------------------------


def _v(version: Version) -> dict:
    return {"major": version.major, "minor": version.minor}


def _descriptor_json(d: Descriptor) -> dict:
    obj = {
        "id": d.id,
        "name": d.name,
        "aliases": list(d.aliases),
        "type": d.type,
        "primitives": list(d.primitives),
        "commands": [_command_json(c) for c in d.commands],
    }
    if d.dialog is not None:
        obj["dialog"] = _dialog_json(d.dialog)
    return obj


def _dialog_json(d: Dialog) -> dict:
    obj: dict = {"kind": d.kind}
    if d.id is not None:
        obj["id"] = d.id
    if d.questions:
        obj["questions"] = []
        for q in d.questions:
            qo: dict = {"question": q.question, "options": list(q.options)}
            if q.multi_select:
                qo["multi_select"] = True
            if q.other:
                qo["other"] = True
            obj["questions"].append(qo)
    return obj


def _answer_json(a: Answer) -> dict:
    return {
        "dialog": a.dialog,
        "choices": [{"option": c.option} if c.option is not None else {"text": c.text} for c in a.choices],
    }


def _command_json(c: Command) -> dict:
    obj = {"id": c.id, "phrases": list(c.phrases), "primitive": c.primitive}
    if c.anywhere:
        obj["anywhere"] = True
    if c.reply_within_ms is not None:
        obj["reply_within_ms"] = c.reply_within_ms
    return obj


def to_json(message: Message) -> dict:
    match message:
        case Hello(protocol=v, capabilities=caps):
            return {"type": "hello", "protocol": _v(v), "capabilities": list(caps)}
        case Welcome(protocol=v, capabilities=caps):
            return {"type": "welcome", "protocol": _v(v), "capabilities": list(caps)}
        case Refusal(reason=reason, message=text, supported=supported):
            obj = {"type": "refusal", "reason": reason, "message": text}
            if supported:
                obj["supported"] = [_v(v) for v in supported]
            return obj
        case Descriptors(buckets=buckets):
            return {"type": "descriptors", "buckets": [_descriptor_json(d) for d in buckets]}
        case Pairing(protocol=v, url=url, token=token):
            return {"type": "pairing", "protocol": _v(v), "url": url, "token": token}
        case Request(id=rid, bucket=bucket, command=command):
            return {"type": "request", "id": rid, "bucket": bucket, "command": command}
        case Reply(id=rid, say=say, reason=reason):
            obj = {"type": "reply", "id": rid}
            if reason is not None:
                obj["reason"] = reason
            obj["say"] = say
            return obj
        case Summarize(id=sid, bucket=bucket, notes=notes, since=since, more=more):
            obj = {"type": "summarize", "id": sid, "bucket": bucket}
            if since is not None:
                obj["since"] = since
            obj["notes"] = [{"turn": n.turn, "text": n.text} for n in notes]
            if more:
                obj["more"] = True
            return obj
        case TurnOffer(turn=turn, turn_json=turn_json, sha256=sha256, bytes=size):
            return {"type": "turn_offer", "turn": turn, "turn_json": turn_json, "audio": {"sha256": sha256, "bytes": size}}
        case TurnAudio(turn=turn, offset=offset, data=data, last=last):
            return {"type": "turn_audio", "turn": turn, "offset": offset,
                    "data": base64.b64encode(data).decode("ascii"), "last": last}
        case TurnAck(turn=turn, status=status, offset=offset, reason=reason, message=text):
            obj = {"type": "turn_ack", "turn": turn, "status": status}
            for key, value in (("offset", offset), ("reason", reason), ("message", text)):
                if value is not None:
                    obj[key] = value
            return obj
        case Send(key=key, bucket=bucket, text=text, answer=answer):
            obj = {"type": "send", "key": key, "bucket": bucket, "text": text}
            if answer is not None:
                obj["answer"] = _answer_json(answer)
            return obj
        case Sent(key=key, say=say, reason=reason):
            obj = {"type": "sent", "key": key}
            if reason is not None:
                obj["reason"] = reason
            obj["say"] = say
            return obj
        case Item(id=iid, buckets=buckets, say=say):
            return {"type": "item", "id": iid, "buckets": list(buckets), "say": say}
        case ItemSpoken(id=iid):
            return {"type": "item_spoken", "id": iid}
        case StatusRequest(id=sid):
            return {"type": "status_request", "id": sid}
        case StatusReport(id=sid, agents=agents, blocked=blocked, herdr=herdr, hooks_installed=hooks):
            obj = {"type": "status_report", "id": sid, "agents": agents, "blocked": blocked}
            if herdr is not None:
                obj["herdr"] = {"state": herdr.state}
                if herdr.version is not None:
                    obj["herdr"]["version"] = herdr.version
            if hooks is not None:
                obj["hooks"] = {"installed": hooks}
            return obj
    raise TypeError(f"not a message: {message!r}")


def encode(message: Message) -> str:
    """Compact JSON, one message per text frame."""
    return json.dumps(to_json(message), separators=(",", ":"), ensure_ascii=False)
