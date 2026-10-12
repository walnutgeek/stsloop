"""`stsloop-hub init` and `stsloop-hub serve`, with the tailnet and the running hub faked."""

import asyncio
import io
import json
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from stsloop_hub import cli, config, tailnet
from stsloop_hub.stub import STUB_BUCKETS

from support import FakeBackend, FakeHerdr, agent

TAILNET_IP = "100.101.102.103"


class Run:
    """One CLI invocation against a temporary config directory."""

    def __init__(self, home: Path, tailnet_ip: str | None = TAILNET_IP, herdr=None):
        self.home = home
        self.tailnet_ip = tailnet_ip
        self.herdr = herdr
        self.served = []
        self.pollers = []
        #: The summariser config each serve built its backend from; the backend is a fake.
        self.summarisers = []

    def __call__(self, *argv):
        self.out, self.err = io.StringIO(), io.StringIO()
        self.code = cli.main(
            list(argv),
            env={"STSLOOP_HUB_HOME": str(self.home)},
            out=self.out,
            err=self.err,
            find_tailnet=lambda: self.tailnet_ip,
            run_hub=lambda hub, host, port, pollers: (self.served.append((hub, host, port)), self.pollers.append(pollers)),
            find_herdr=lambda: self.herdr,
            make_backend=lambda cfg: (self.summarisers.append(cfg), FakeBackend())[1],
        )
        return self

    @property
    def config(self):
        return json.loads((self.home / "hub.json").read_text())


class InitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "stsloop-hub"
        self.run = Run(self.home)

    def tearDown(self):
        self.tmp.cleanup()

    def test_init_writes_a_token_only_the_user_can_read(self):
        self.run("init")
        self.assertEqual(self.run.code, 0)
        token = self.run.config["token"]
        self.assertRegex(token, r"^[A-Za-z0-9_-]{43}$")
        self.assertEqual(stat.S_IMODE((self.home / "hub.json").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.home.stat().st_mode), 0o700)

    def test_init_prints_a_qr_code_and_the_hub_url(self):
        out = self.run("init").out.getvalue()
        self.assertIn("█", out)
        self.assertIn(f"ws://{TAILNET_IP}:{config.DEFAULT_PORT}/", out)

    def test_init_again_keeps_the_token(self):
        first = self.run("init").config["token"]
        self.assertEqual(self.run("init").config["token"], first)

    def test_init_new_token_replaces_it(self):
        first = self.run("init").config["token"]
        self.assertNotEqual(self.run("init", "--new-token").config["token"], first)

    def test_init_keeps_an_explicit_host_and_port(self):
        self.run("init", "--host", "192.168.1.5", "--port", "9000")
        self.assertEqual((self.run.config["host"], self.run.config["port"]), ("192.168.1.5", 9000))
        self.assertIn("ws://192.168.1.5:9000/", self.run.out.getvalue())

    def test_a_port_out_of_range_is_rejected_before_anything_is_written(self):
        for port in ("0", "70000", "-1"):
            with self.subTest(port=port), self.assertRaises(SystemExit):
                self.run("init", "--port", port)
        self.assertFalse((self.home / "hub.json").exists())

    def test_a_host_outside_the_tailnet_gets_a_warning(self):
        self.run("init", "--host", "192.168.1.5")
        self.assertIn("unencrypted", self.run.err.getvalue())
        self.run("init", "--host", "100.64.0.9")
        self.assertNotIn("unencrypted", self.run.err.getvalue())

    def test_init_without_a_tailnet_writes_the_token_and_says_how_to_go_on(self):
        run = Run(self.home, tailnet_ip=None)("init")
        self.assertNotEqual(run.code, 0)
        self.assertIn("token", run.config)
        self.assertIn("--host", run.err.getvalue())
        self.assertNotIn("█", run.out.getvalue())


class ServeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "stsloop-hub"
        self.run = Run(self.home)

    def tearDown(self):
        self.tmp.cleanup()

    def test_serve_before_init_says_to_run_init(self):
        self.run("serve")
        self.assertNotEqual(self.run.code, 0)
        self.assertIn("stsloop-hub init", self.run.err.getvalue())
        self.assertEqual(self.run.served, [])

    def test_serve_listens_on_the_tailnet_address_only_by_default(self):
        token = self.run("init").config["token"]
        self.run("serve")
        self.assertEqual(self.run.code, 0)
        [(hub, host, port)] = self.run.served
        self.assertEqual((host, port), (TAILNET_IP, config.DEFAULT_PORT))
        self.assertTrue(hub.authorised(f"Bearer {token}"))

    def test_serve_without_a_tailnet_refuses_to_guess(self):
        self.run("init", "--host", "x")  # write a config, then forget the host
        cfg = self.run.config
        del cfg["host"]
        (self.home / "hub.json").write_text(json.dumps(cfg))
        run = Run(self.home, tailnet_ip=None)("serve")
        self.assertNotEqual(run.code, 0)
        self.assertIn("--host", run.err.getvalue())
        self.assertEqual(run.served, [])

    def test_serve_host_and_port_flags_win(self):
        self.run("init", "--host", "192.168.1.5")
        self.run("serve", "--host", "192.168.1.6", "--port", "9001")
        self.assertEqual(self.run.served[0][1:], ("192.168.1.6", 9001))
        self.assertIn("unencrypted", self.run.err.getvalue())
        self.run("serve", "--host", "127.0.0.1")
        self.assertNotIn("unencrypted", self.run.err.getvalue())

    def test_serve_uses_the_host_init_was_given(self):
        self.run("init", "--host", "192.168.1.5")
        self.run("serve")
        self.assertEqual(self.run.served[0][1], "192.168.1.5")

    def test_without_herdr_serve_publishes_only_the_hub_bucket_and_polls_nothing(self):
        self.run("init")
        self.run("serve")
        [(hub, _, _)] = self.run.served
        self.assertEqual(hub._descriptors(), STUB_BUCKETS)
        self.assertEqual(self.run.pollers, [[]])

    def test_with_herdr_serve_publishes_its_agents_and_polls_it(self):
        fake = FakeHerdr([agent("w8:p1", "stsloop", "blocked")])
        run = Run(self.home, herdr=fake)
        run("init")
        run("serve")
        [(hub, _, _)] = run.served
        [[poll]] = run.pollers
        self.assertEqual([d.id for d in hub._descriptors()], ["hub"], "nothing before the first poll")
        changed = []

        async def one_poll():
            task = asyncio.create_task(poll(lambda: changed.append(True)))
            while not changed:
                await asyncio.sleep(0.01)
            task.cancel()

        asyncio.run(one_poll())
        self.assertEqual([d.id for d in hub._descriptors()], ["hub", "herdr:w8:p1"])
        self.assertEqual(hub._descriptors()[0].commands[0].id, "blocked")
    def test_summaries_use_claude_by_default(self):
        run = Run(self.home, herdr=FakeHerdr())
        run("init")
        run("serve")
        self.assertEqual(run.summarisers, [config.SummariserConfig(backend="claude")])
        self.assertIn("summarising agents with claude", run.err.getvalue())

    def test_the_summariser_named_in_the_config_is_used_and_init_keeps_it(self):
        run = Run(self.home, herdr=FakeHerdr())
        run("init")
        cfg = run.config
        cfg["summariser"] = {"backend": "ollama", "model": "qwen3", "url": "http://10.0.0.2:11434", "timeout_s": 12}
        (self.home / "hub.json").write_text(json.dumps(cfg))
        run("init", "--port", "9000")
        self.assertEqual(run.config["summariser"], cfg["summariser"])
        run("serve")
        self.assertEqual(run.summarisers, [config.SummariserConfig(
            backend="ollama", model="qwen3", url="http://10.0.0.2:11434", timeout_s=12.0)])

    def test_an_unusable_summariser_is_reported(self):
        self.run("init")
        for summariser, said in [
            ({"backend": "gpt"}, "backend 'gpt'"),
            ({"backend": "pi", "timeout_s": 60}, "timeout_s"),
            ({"backend": "pi", "timeout_s": True}, "timeout_s"),
            ({"model": ""}, "model"),
            ("claude", "object"),
        ]:
            with self.subTest(summariser=summariser):
                cfg = self.run.config
                cfg["summariser"] = summariser
                (self.home / "hub.json").write_text(json.dumps(cfg))
                self.run("serve")
                self.assertEqual(self.run.code, 2)
                self.assertIn(said, self.run.err.getvalue())

    def test_serve_stores_the_corpus_in_the_hub_home_by_default(self):
        self.run("init")
        self.run("serve")
        hub = self.run.served[0][0]
        self.assertEqual(hub.corpus.root, self.home / "corpus")
        self.assertTrue((self.home / "corpus").is_dir())
        self.assertIn(str(self.home / "corpus"), self.run.err.getvalue())

    def test_serve_corpus_flag_picks_the_directory(self):
        self.run("init")
        self.run("serve", "--corpus", str(self.home.parent / "elsewhere"))
        self.assertEqual(self.run.served[0][0].corpus.root, self.home.parent / "elsewhere")

    def test_a_config_that_is_not_json_is_reported(self):
        self.home.mkdir(parents=True)
        (self.home / "hub.json").write_text("{nope")
        self.run("serve")
        self.assertNotEqual(self.run.code, 0)
        self.assertIn("hub.json", self.run.err.getvalue())


class ConfigDirTest(unittest.TestCase):
    def test_stsloop_hub_home_wins(self):
        self.assertEqual(config.config_dir({"STSLOOP_HUB_HOME": "/a", "XDG_CONFIG_HOME": "/b"}), Path("/a"))

    def test_xdg_config_home_is_next(self):
        self.assertEqual(config.config_dir({"XDG_CONFIG_HOME": "/b"}), Path("/b/stsloop-hub"))

    def test_otherwise_dot_config(self):
        self.assertEqual(config.config_dir({"HOME": "/h"}), Path("/h/.config/stsloop-hub"))


class CorpusDirTest(unittest.TestCase):
    def test_stsloop_hub_home_wins(self):
        self.assertEqual(config.corpus_dir({"STSLOOP_HUB_HOME": "/a", "XDG_DATA_HOME": "/b"}), Path("/a/corpus"))

    def test_xdg_data_home_is_next(self):
        self.assertEqual(config.corpus_dir({"XDG_DATA_HOME": "/b"}), Path("/b/stsloop-hub/corpus"))

    def test_otherwise_dot_local_share(self):
        self.assertEqual(config.corpus_dir({"HOME": "/h"}), Path("/h/.local/share/stsloop-hub/corpus"))


class TailnetTest(unittest.TestCase):
    def runner(self, stdout="", returncode=0, missing=()):
        def run(argv, **kwargs):
            if argv[0] in missing:
                raise FileNotFoundError(argv[0])
            return subprocess.CompletedProcess(argv, returncode, stdout=stdout, stderr="")
        return run

    def test_it_asks_tailscale_for_its_ipv4_address(self):
        self.assertEqual(tailnet.address(run=self.runner("100.101.102.103\n")), "100.101.102.103")

    def test_no_tailscale_means_no_address(self):
        self.assertIsNone(tailnet.address(run=self.runner(missing=tuple(tailnet.CANDIDATES))))

    def test_tailscale_down_means_no_address(self):
        self.assertIsNone(tailnet.address(run=self.runner("", returncode=1)))

    def test_an_address_outside_the_tailnet_range_is_not_trusted(self):
        self.assertIsNone(tailnet.address(run=self.runner("192.168.1.5\n")))

    def test_it_falls_back_to_the_macos_app_binary(self):
        mac = tailnet.CANDIDATES[-1]
        def run(argv, **kwargs):
            if argv[0] != mac:
                raise FileNotFoundError(argv[0])
            return subprocess.CompletedProcess(argv, 0, stdout="100.64.0.9\n", stderr="")
        self.assertEqual(tailnet.address(run=run), "100.64.0.9")


if __name__ == "__main__":
    unittest.main()
