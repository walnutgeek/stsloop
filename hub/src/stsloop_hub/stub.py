"""The stub Bucket: what the hub publishes before it has real Providers (#41).

It proves pairing end to end: the phone can focus "the hub" and hear its name.
Its name is "the hub", with "hub" as an alias: "hub" alone is heard 4 times in 6
on the dev phone, "the hub" every time (#82).
It has no commands; the herdr and Claude Code hooks Providers replace it.
"""

from .protocol import Descriptor

STUB_BUCKETS: tuple[Descriptor, ...] = (
    Descriptor(id="hub", name="the hub", aliases=("hub",), type="hub", primitives=(), commands=()),
)
