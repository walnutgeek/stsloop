#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["jsonschema>=4.23"]
# ///
"""Check protocol/ (ADR-0006): every fixture against its schema, and the manifests.

For each version directory `protocol/fixtures/v<N>/` it checks that:

- every schema in `protocol/schemas/v<N>/` is a valid JSON Schema and never
  says `additionalProperties: false` (receivers ignore unknown fields);
- every fixture `<type>/<case>.json` is strict JSON (a duplicate key counts as
  malformed), satisfies `message.schema.json`, has `"type": "<type>"`, and
  satisfies `<type>.schema.json`. Fixtures under `message/` are messages of
  types no schema knows, and must stay that way;
- every command in a `descriptors` fixture uses a Primitive its Descriptor lists;
- every message schema has at least one fixture;
- `exchanges.json` and `equivalents.json` name only fixtures that exist, and
  each equivalent pair has one type.

    uv run scripts/validate_protocol.py [PROTOCOL_DIR]

Prints each problem and exits 1 if there are any. PROTOCOL_DIR defaults to the
repo's `protocol/`.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

ENVELOPE = "message"
EXCHANGES = "exchanges.json"
EQUIVALENTS = "equivalents.json"


def _no_duplicates(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError(f"duplicate key {key!r}")
        obj[key] = value
    return obj


def load_strict(path: Path):
    """Parse JSON, refusing duplicate keys (as the phone's config readers do)."""
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_no_duplicates)


def _closed_objects(node, where="") -> list[str]:
    found = []
    if isinstance(node, dict):
        if node.get("additionalProperties") is False:
            found.append(where or "/")
        for key, value in node.items():
            found += _closed_objects(value, f"{where}/{key}")
    elif isinstance(node, list):
        for i, value in enumerate(node):
            found += _closed_objects(value, f"{where}/{i}")
    return found


def _command_primitives(message: dict, where: str) -> list[str]:
    """What a schema cannot say: a command's primitive is one its Descriptor lists."""
    return [
        f"{where}: Bucket {bucket['id']!r} command {command['id']!r} uses {command['primitive']!r}, "
        f"not in its primitives"
        for bucket in message["buckets"]
        for command in bucket["commands"]
        if command["primitive"] not in bucket["primitives"]
    ]


def _check_version(root: Path, version: str) -> list[str]:
    problems: list[str] = []
    fixtures_dir = root / "fixtures" / version
    schemas_dir = root / "schemas" / version

    validators: dict[str, Draft202012Validator] = {}
    for path in sorted(schemas_dir.glob("*.schema.json")):
        name = path.name.removesuffix(".schema.json")
        rel = f"schemas/{version}/{path.name}"
        try:
            schema = load_strict(path)
            Draft202012Validator.check_schema(schema)
        except (ValueError, SchemaError) as e:
            problems.append(f"{rel}: not a valid JSON Schema: {str(e).splitlines()[0]}")
            continue
        for where in _closed_objects(schema):
            problems.append(f"{rel}: additionalProperties is false at {where}; receivers must ignore unknown fields")
        validators[name] = Draft202012Validator(schema)
    if ENVELOPE not in validators:
        problems.append(f"schemas/{version}/{ENVELOPE}.schema.json: missing or invalid")
        return problems

    covered: set[str] = set()
    fixtures: dict[str, dict] = {}
    for path in sorted(fixtures_dir.glob("*/*.json")):
        kind = path.parent.name
        rel = f"{kind}/{path.name}"
        where = f"fixtures/{version}/{rel}"
        try:
            message = load_strict(path)
        except ValueError as e:
            problems.append(f"{where}: not strict JSON: {e}")
            continue
        errors = list(validators[ENVELOPE].iter_errors(message))
        if errors:
            problems.append(f"{where}: not a message: {errors[0].message}")
            continue
        fixtures[rel] = message
        covered.add(kind)
        if kind == ENVELOPE:
            if message["type"] in validators:
                problems.append(f"{where}: type {message['type']!r} has a schema; move it to {message['type']}/")
            continue
        if message["type"] != kind:
            problems.append(f"{where}: type is {message['type']!r}, but the fixture is under {kind}/")
        if kind not in validators:
            problems.append(f"{where}: no schema schemas/{version}/{kind}.schema.json")
            continue
        errors = list(validators[kind].iter_errors(message))
        for error in errors:
            at = "/" + "/".join(str(p) for p in error.absolute_path)
            problems.append(f"{where}: at {at}: {error.message}")
        if kind == "descriptors" and not errors:
            problems += _command_primitives(message, where)

    for name in sorted(set(validators) - covered - {ENVELOPE}):
        problems.append(f"schemas/{version}/{name}.schema.json: no fixture under fixtures/{version}/{name}/")

    def known(ref, where):
        if ref not in fixtures:
            problems.append(f"fixtures/{version}/{where}: names {ref}, which is not a valid fixture")
            return False
        return True

    exchanges_path = fixtures_dir / EXCHANGES
    if exchanges_path.exists():
        try:
            exchanges = load_strict(exchanges_path)["exchanges"]
        except (ValueError, KeyError, TypeError) as e:
            problems.append(f"fixtures/{version}/{EXCHANGES}: unreadable: {e}")
            exchanges = []
        for exchange in exchanges:
            label = f"{EXCHANGES} {exchange.get('name')!r}"
            for step in exchange.get("steps", []):
                if step.get("from") not in ("phone", "hub"):
                    problems.append(f"fixtures/{version}/{label}: step {step} has no from phone|hub")
                if ("fixture" in step) == ("close" in step):
                    problems.append(f"fixtures/{version}/{label}: step {step} needs exactly one of fixture, close")
                elif "fixture" in step:
                    known(step["fixture"], label)

    equivalents_path = fixtures_dir / EQUIVALENTS
    if equivalents_path.exists():
        try:
            pairs = load_strict(equivalents_path)["equivalents"]
        except (ValueError, KeyError, TypeError) as e:
            problems.append(f"fixtures/{version}/{EQUIVALENTS}: unreadable: {e}")
            pairs = []
        for pair in pairs:
            a, b = pair.get("fixture"), pair.get("reads_as")
            if known(a, EQUIVALENTS) and known(b, EQUIVALENTS) and fixtures[a]["type"] != fixtures[b]["type"]:
                problems.append(f"fixtures/{version}/{EQUIVALENTS}: {a} and {b} differ in type")

    return problems


def check(root: Path) -> list[str]:
    """Every problem found under the protocol directory `root`; empty when all is well."""
    versions = sorted(p.name for p in (root / "fixtures").glob("v*") if p.is_dir())
    if not versions:
        return [f"{root}/fixtures: no version directories"]
    problems = []
    for version in versions:
        problems += _check_version(root, version)
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[0]) if argv else Path(__file__).resolve().parent.parent / "protocol"
    problems = check(root)
    for p in problems:
        print(p)
    if not problems:
        count = len(list((root / "fixtures").glob("v*/*/*.json")))
        print(f"protocol OK: {count} fixtures")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
