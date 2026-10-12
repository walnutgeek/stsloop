"""The stub Bucket: what the hub publishes before it has real Providers (#41).

It proves pairing end to end: the phone can focus "hub" and hear its name.
It has no commands; the herdr and Claude Code hooks Providers replace it.
"""

from .protocol import Descriptor

STUB_BUCKETS: tuple[Descriptor, ...] = (
    Descriptor(id="hub", name="hub", aliases=("the hub",), type="hub", primitives=(), commands=()),
)
