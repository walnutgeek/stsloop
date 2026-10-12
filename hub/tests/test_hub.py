"""The hub's WebSocket: the token check, refusals, and one phone at a time."""

import asyncio
import json
import unittest

from websockets.exceptions import ConnectionClosed, InvalidStatus

from support import fixture, pairing_token, phone, running_hub

HELLO = json.dumps(fixture("v1", "hello/basic.json"))


class TokenTest(unittest.IsolatedAsyncioTestCase):
    async def assert_refused(self, authorization):
        async with running_hub() as (_, url):
            with self.assertRaises(InvalidStatus) as caught:
                async with phone(url, authorization=authorization):
                    pass
            self.assertEqual(caught.exception.response.status_code, 401)

    async def test_a_wrong_token_is_refused_before_the_upgrade(self):
        await self.assert_refused(f"Bearer {'x' * 43}")

    async def test_a_prefix_of_the_token_is_refused(self):
        await self.assert_refused(f"Bearer {pairing_token()[:-1]}")

    async def test_no_token_is_refused(self):
        await self.assert_refused(None)

    async def test_a_token_in_another_scheme_is_refused(self):
        await self.assert_refused(f"Basic {pairing_token()}")

    async def test_the_right_token_is_accepted(self):
        async with running_hub() as (_, url):
            async with phone(url, pairing_token()) as ws:
                await ws.send(HELLO)
                self.assertEqual(json.loads(await asyncio.wait_for(ws.recv(), 5))["type"], "welcome")

    async def test_the_scheme_is_case_insensitive(self):
        async with running_hub() as (_, url):
            async with phone(url, authorization=f"bearer {pairing_token()}") as ws:
                await ws.send(HELLO)
                self.assertEqual(json.loads(await asyncio.wait_for(ws.recv(), 5))["type"], "welcome")

    async def test_a_token_with_trailing_space_is_refused(self):
        await self.assert_refused(f"Bearer {pairing_token()} x")


class HelloTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_malformed_hello_is_refused_as_bad_hello(self):
        async with running_hub() as (_, url):
            async with phone(url, pairing_token()) as ws:
                await ws.send('{"type": "hello", "capabilities": ["descriptors"]}')
                refusal = json.loads(await asyncio.wait_for(ws.recv(), 5))
                self.assertEqual(refusal, fixture("v1", "refusal/bad-hello.json"))
                with self.assertRaises(ConnectionClosed):
                    await asyncio.wait_for(ws.recv(), 5)
                self.assertEqual(ws.close_code, 1008)

    async def test_frames_that_are_not_messages_are_ignored_before_hello(self):
        async with running_hub() as (_, url):
            async with phone(url, pairing_token()) as ws:
                await ws.send("not json")
                await ws.send(b"\x00binary")
                await ws.send('{"type": "welcome", "protocol": {"major": 1, "minor": 0}, "capabilities": []}')
                await ws.send(HELLO)
                self.assertEqual(json.loads(await asyncio.wait_for(ws.recv(), 5))["type"], "welcome")

    async def test_a_phone_that_never_says_hello_is_closed(self):
        async with running_hub(hello_timeout=0.2) as (_, url):
            async with phone(url, pairing_token()) as ws:
                with self.assertRaises(ConnectionClosed):
                    await asyncio.wait_for(ws.recv(), 5)
                self.assertEqual(ws.close_code, 1008)

    async def test_a_phone_without_the_descriptors_capability_gets_no_descriptors(self):
        async with running_hub() as (_, url):
            async with phone(url, pairing_token()) as ws:
                await ws.send('{"type": "hello", "protocol": {"major": 1, "minor": 0}, "capabilities": []}')
                welcome = json.loads(await asyncio.wait_for(ws.recv(), 5))
                self.assertEqual(welcome["capabilities"], [])
                with self.assertRaises(asyncio.TimeoutError):
                    await asyncio.wait_for(ws.recv(), 0.2)

    async def test_messages_after_the_handshake_are_ignored_and_the_connection_stays_open(self):
        async with running_hub() as (_, url):
            async with phone(url, pairing_token()) as ws:
                await ws.send(HELLO)
                await ws.recv(), await ws.recv()
                await ws.send(json.dumps(fixture("v1", "message/future-type.json")))
                await ws.send("garbage")
                await ws.send(HELLO)
                with self.assertRaises(asyncio.TimeoutError):
                    await asyncio.wait_for(ws.recv(), 0.2)
                await ws.ping()


class OnePhoneTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_new_connection_replaces_the_old_one(self):
        async with running_hub() as (hub, url):
            async with phone(url, pairing_token()) as first:
                await first.send(HELLO)
                await first.recv(), await first.recv()
                async with phone(url, pairing_token()) as second:
                    await second.send(HELLO)
                    self.assertEqual(json.loads(await asyncio.wait_for(second.recv(), 5))["type"], "welcome")
                    with self.assertRaises(ConnectionClosed):
                        await asyncio.wait_for(first.recv(), 5)
                    self.assertEqual(first.close_code, 1000)
                    self.assertEqual(hub.connections, 1)

    async def test_a_refused_connection_leaves_the_welcomed_one_alone(self):
        async with running_hub() as (hub, url):
            async with phone(url, pairing_token()) as good:
                await good.send(HELLO)
                await good.recv(), await good.recv()
                async with phone(url, pairing_token()) as bad:
                    await bad.send(json.dumps(fixture("v1", "hello/major-2.json")))
                    self.assertEqual(json.loads(await asyncio.wait_for(bad.recv(), 5))["type"], "refusal")
                    with self.assertRaises(ConnectionClosed):
                        await asyncio.wait_for(bad.recv(), 5)
                await good.ping()
                with self.assertRaises(asyncio.TimeoutError):
                    await asyncio.wait_for(good.recv(), 0.2)


if __name__ == "__main__":
    unittest.main()
