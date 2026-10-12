"""The hooks' HTTP listener on a loopback port, fed raw requests and recorded hook payloads."""

import asyncio
import json
import unittest

from stsloop_hub import hook_server

from support import recording

PAYLOAD = recording("recorded-permission")[3][2]  # a PermissionRequest


async def raw(port: int, request: bytes) -> bytes:
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(request)
    await writer.drain()
    answer = await reader.read()
    writer.close()
    return answer


def post(body: bytes, path: str = hook_server.PATH, headers: str = "") -> bytes:
    return (f"POST {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n{headers}"
            f"Content-Length: {len(body)}\r\n\r\n").encode() + body


class HookServerTest(unittest.TestCase):
    def exchange(self, *requests: bytes, on_event=None):
        """Each request's answer, and the events the hub took in."""
        events = []

        async def run():
            server = await hook_server.listen(on_event or (lambda *e: events.append(e)), 0)
            port = server.sockets[0].getsockname()[1]
            async with server:
                answers = [await raw(port, r) for r in requests]
                await asyncio.sleep(0)
                return answers

        return asyncio.run(run()), events

    def test_an_event_is_answered_with_an_empty_204_and_taken_in(self):
        [answer], events = self.exchange(post(json.dumps(PAYLOAD).encode(), headers="X-Herdr-Pane: w8:p1\r\nX-Claude-Pid: 4242\r\n"))
        self.assertEqual(answer, b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        self.assertEqual(events, [(PAYLOAD, "w8:p1", 4242)])

    def test_outside_herdr_the_pane_is_empty(self):
        _, events = self.exchange(post(json.dumps(PAYLOAD).encode(), headers="X-Herdr-Pane: \r\nX-Claude-Pid: \r\n"))
        self.assertEqual(events, [(PAYLOAD, None, None)])

    def test_a_pane_or_pid_that_is_not_one_is_dropped(self):
        with self.assertLogs("stsloop.hooks", "INFO"):
            _, events = self.exchange(post(json.dumps(PAYLOAD).encode(), headers="X-Herdr-Pane: ; rm -rf\r\nX-Claude-Pid: 1\r\n"))
        self.assertEqual(events, [(PAYLOAD, None, None)])

    def test_other_paths_and_methods_are_refused(self):
        with self.assertLogs("stsloop.hooks", "INFO"):
            answers, events = self.exchange(
                post(b"{}", path="/other"),
                f"GET {hook_server.PATH} HTTP/1.1\r\n\r\n".encode(),
                f"POST {hook_server.PATH} HTTP/1.1\r\n\r\n".encode(),
                f"POST {hook_server.PATH} HTTP/1.1\r\nContent-Length: {hook_server.MAX_BODY + 1}\r\n\r\n".encode(),
            )
        self.assertEqual([a.split(b"\r\n")[0] for a in answers], [
            b"HTTP/1.1 404 Not Found", b"HTTP/1.1 405 Method Not Allowed", b"HTTP/1.1 411 Length Required",
            b"HTTP/1.1 413 Content Too Large",
        ])
        self.assertEqual(events, [])

    def test_only_json_to_a_loopback_host_is_read(self):
        # What a web page could send without a preflight, or through DNS rebinding.
        body = json.dumps(PAYLOAD).encode()
        page = (f"POST {hook_server.PATH} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n"
                f"Content-Length: {len(body)}\r\n\r\n").encode() + body
        rebound = (f"POST {hook_server.PATH} HTTP/1.1\r\nHost: evil.example:7718\r\nContent-Type: application/json\r\n"
                   f"Content-Length: {len(body)}\r\n\r\n").encode() + body
        with self.assertLogs("stsloop.hooks", "INFO"):
            answers, events = self.exchange(page, rebound)
        self.assertEqual([a.split(b"\r\n")[0] for a in answers],
                         [b"HTTP/1.1 415 Unsupported Media Type", b"HTTP/1.1 403 Forbidden"])
        self.assertEqual(events, [])

    def test_a_large_body_is_asked_for_at_once(self):
        # curl sends `Expect: 100-continue` before a large body, and waits a second for the go-ahead.
        body = json.dumps(PAYLOAD).encode()
        events = []

        async def run():
            server = await hook_server.listen(lambda *e: events.append(e), 0)
            port = server.sockets[0].getsockname()[1]
            async with server:
                reader, writer = await asyncio.open_connection("127.0.0.1", port)
                writer.write((f"POST {hook_server.PATH} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
                              f"Content-Type: application/json\r\nExpect: 100-continue\r\n"
                              f"Content-Length: {len(body)}\r\n\r\n").encode())
                await writer.drain()
                go = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 0.5)
                writer.write(body)
                answer = await reader.read()
                writer.close()
                await asyncio.sleep(0)
                return go, answer

        go, answer = asyncio.run(run())
        self.assertEqual(go, b"HTTP/1.1 100 Continue\r\n\r\n")
        self.assertTrue(answer.startswith(b"HTTP/1.1 204"))
        self.assertEqual(len(events), 1)

    def test_headers_too_large_are_refused(self):
        with self.assertLogs("stsloop.hooks", "INFO"):
            [answer], _ = self.exchange(post(b"{}", headers="X-Big: " + "a" * hook_server.MAX_HEAD + "\r\n"))
        self.assertTrue(answer.startswith(b"HTTP/1.1 431"))

    def test_a_body_that_is_not_json_is_answered_and_ignored(self):
        with self.assertLogs("stsloop.hooks", "INFO"):
            [answer], events = self.exchange(post(b"not json"))
        self.assertTrue(answer.startswith(b"HTTP/1.1 204"))
        self.assertEqual(events, [])

    def test_a_failing_handler_still_answers_and_keeps_listening(self):
        def broken(*event):
            raise RuntimeError("bug")

        with self.assertLogs("stsloop.hooks", "ERROR"):
            answers, _ = self.exchange(post(b"{}"), post(b"{}"), on_event=broken)
        self.assertTrue(all(a.startswith(b"HTTP/1.1 204") for a in answers))

    def test_a_port_already_taken_is_logged_and_the_hub_goes_on(self):
        async def run():
            server = await hook_server.listen(lambda *e: None, 0)
            port = server.sockets[0].getsockname()[1]
            async with server:
                with self.assertLogs("stsloop.hooks", "WARNING"):
                    await hook_server.run(lambda *e: None, port)

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
