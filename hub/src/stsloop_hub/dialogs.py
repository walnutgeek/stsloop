"""Claude Code's question dialog (the AskUserQuestion tool), and answering it with keys (#80).

When Claude Code asks the person a question, its hooks carry the questions
(`questions(tool_input)`), and the agent's Bucket shows them in its Descriptor
(`p.Dialog`). The phone picks an option, or takes a Draft as a free-text
answer, per question, and sends the whole answer once ("send it"). The hub
then types it into the dialog through herdr (`keys`), one key at a time.

How Claude Code 2.1's dialog takes keys (recorded against a real one; see
`hub/tests/recordings/screen-*.txt`):

- Each question lists its options as `1.`, `2.`, …, then `Type something.`
  (the free-text answer) and `Chat about this`. Pressing an option's digit on
  a one-choice question picks it and moves on: to the next question, or, on
  the last of one question, it answers the dialog.
- A free-text answer is its digit (one past the last option), the text, then
  Enter.
- On a multi-select question a digit toggles that option; Tab moves on.
- A dialog of several questions, or with a multi-select one, ends on "Review
  your answers", where `1` is "Submit answers".
- Keys sent in one burst are read as one input and ignored, so each goes on
  its own, `KEY_GAP_S` apart.

A permission prompt takes digits too ("1. Yes"), so a key that lands on the
wrong dialog could approve one. Before each digit, the hub reads the agent's
screen and presses it only if the screen shows the question that digit
answers (or, for the last `1`, the review screen) and no permission prompt
(`shows`).
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from dataclasses import dataclass

from . import protocol as p

#: Claude Code's free-text row, on a question dialog only.
FREE_TEXT_ROW = "Type something"
#: Claude Code's permission prompt asks this.
PERMISSION_ROW = "Do you want to proceed?"
#: The review screen's first row, which `1` picks.
SUBMIT_ROW = "Submit answers"
#: How long to wait between keys: a burst is read as one input.
KEY_GAP_S = 0.3
#: How many screen lines the check reads: a dialog with four described options fits.
SCREEN_LINES = 40
#: How much of a question must be on screen, without its spaces (a wrapped line breaks anywhere).
MATCH_CHARS = 60
NUMBERS = ("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")


@dataclass(frozen=True)
class Key:
    """One key press, in herdr's key syntax: a digit, `enter` or `tab`. A digit that answers a question or submits
    the answers `expects` what the screen must show before it is pressed (`shows`): that question, or `SUBMIT_ROW`."""

    key: str
    expects: str | None = None


@dataclass(frozen=True)
class Text:
    """Literal text typed into the dialog's free-text row."""

    text: str


Step = Key | Text


class Unfit(Exception):
    """An answer that does not fit the dialog: `str(e)` says why, to be spoken."""


def number(n: int) -> str:
    """1 → "one": how an option is said."""
    return NUMBERS[n - 1] if 1 <= n <= len(NUMBERS) else str(n)


def questions(tool_input: object) -> tuple[p.DialogQuestion, ...] | None:
    """AskUserQuestion's questions, as the Descriptor shows them; None when its input is not readable as one."""
    if not isinstance(tool_input, Mapping) or not isinstance(tool_input.get("questions"), list):
        return None
    out = []
    for q in tool_input["questions"]:
        if not isinstance(q, Mapping) or not isinstance(q.get("question"), str) or not q["question"].strip():
            return None
        labels = [o.get("label") for o in q.get("options") or [] if isinstance(o, Mapping)]
        if not labels or not all(isinstance(label, str) and label.strip() for label in labels):
            return None
        multi = q.get("multiSelect") is True
        # Claude Code offers "Type something." on every question; only a one-choice one is answered that way here.
        out.append(p.DialogQuestion(question=" ".join(q["question"].split()), options=tuple(" ".join(label.split()) for label in labels),
                                    multi_select=multi, other=not multi))
    return tuple(out) or None


def dialog_id(tool_use_id: str | None, asked: tuple[p.DialogQuestion, ...]) -> str:
    """The dialog's id: its tool call's, when the hooks gave one, else one made from its questions."""
    if tool_use_id:
        return tool_use_id
    digest = hashlib.sha256(json.dumps([(q.question, q.options) for q in asked]).encode()).hexdigest()
    return f"ask-{digest[:16]}"


def reading(name: str, asked: tuple[p.DialogQuestion, ...], own_answer: str = "my answer") -> str:
    """What "what does it say" says of a question dialog: each question and its numbered options, and, when one
    takes a free-text answer, the phrase that answers with the Draft (`own_answer`)."""
    def options(q: p.DialogQuestion) -> str:
        return "; ".join(f"option {number(n)}: {label}" for n, label in enumerate(q.options, 1)) + "."

    if len(asked) == 1:
        said = f"{name} asks: {asked[0].question} {options(asked[0])}"
    else:
        parts = [f"question {number(n)}: {q.question} {options(q)}" for n, q in enumerate(asked, 1)]
        said = f"{name} asks {number(len(asked))} questions. " + " ".join(parts)
    if any(q.other for q in asked):
        said += f" Or dictate your own answer and say {own_answer}."
    return said


def keys(asked: tuple[p.DialogQuestion, ...], choices: tuple[p.Choice, ...]) -> list[Step]:
    """The keys that answer the dialog `asked` with `choices`, in order. Raises Unfit when they do not fit."""
    if len(choices) != len(asked):
        raise Unfit(f"it asks {len(asked)} question{'s' if len(asked) != 1 else ''}, and the answer has {len(choices)}")
    steps: list[Step] = []
    for n, (q, c) in enumerate(zip(asked, choices), 1):
        if c.option is not None:
            if not 1 <= c.option <= len(q.options):
                raise Unfit(f"question {number(n)} has no option {number(c.option)}")
            if c.option > 9:
                raise Unfit(f"option {number(c.option)} has no key of its own")
            steps.append(Key(str(c.option), expects=q.question))
            if q.multi_select:
                steps.append(Key("tab"))
        else:
            text = " ".join((c.text or "").split())
            if not text or not q.other:
                raise Unfit(f"question {number(n)} takes no answer of your own")
            if len(q.options) + 1 > 9:
                raise Unfit(f"question {number(n)}'s own answer has no key of its own")
            # herdr reads a text starting with "-" as an option.
            steps += [Key(str(len(q.options) + 1), expects=q.question), Text(f" {text}" if text.startswith("-") else text),
                      Key("enter")]
    if len(asked) > 1 or any(q.multi_select for q in asked):
        steps.append(Key("1", expects=SUBMIT_ROW))
    return steps


def _squeezed(text: str) -> str:
    return re.sub(r"\s+", "", text)


def shows(screen: str, expects: str) -> bool:
    """Whether `screen` (the agent's last lines) shows what a key `expects`, and no permission prompt: the question
    dialog at the question `expects`, or the review screen (`SUBMIT_ROW`)."""
    tail = _squeezed("\n".join(screen.splitlines()[-SCREEN_LINES:]))
    if _squeezed(PERMISSION_ROW) in tail:
        return False
    if expects == SUBMIT_ROW:
        return _squeezed(SUBMIT_ROW) in tail
    return _squeezed(FREE_TEXT_ROW) in tail and _squeezed(expects)[:MATCH_CHARS] in tail


def on_screen(screen: str, asked: tuple[p.DialogQuestion, ...]) -> bool:
    """Whether `screen` shows the dialog `asked`, at its first question, and no permission prompt."""
    return shows(screen, asked[0].question)
