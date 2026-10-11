# Getting the Corpus onto the Linux box

The Egress design (`docs/mvp.md`, "Egress") is "no protocol": the Corpus is
immutable and append-only, so any folder copy that brings new files over is
enough, and repeating a copy is harmless. This page is the step-by-step
version, and it is honest about one obstacle.

## The obstacle: the Corpus is in app-private storage

The app writes the Corpus to `files/corpus/` in its **private** storage
(`/data/data/com.walnutgeek.stsloop/files/corpus/`, `Context.filesDir`).
Android lets no other app read that directory: not Syncthing, not Termux,
not a file manager. Since Android 11, even an app with "All files access"
cannot read another app's `Android/data/` folder. So today:

| Path | Works today? |
| --- | --- |
| `adb` + `run-as`, over USB | **Yes**, debug builds only |
| `adb` + `run-as`, over Wi-Fi (wireless debugging, also over Tailscale) | Should work, debug builds only. Not tried yet |
| Syncthing on the phone | **No**. Needs the app to copy the Corpus to shared storage first (a future ticket) |
| `rsync` over Tailscale from the phone (Termux) | **No**, for the same reason |
| A release (non-debuggable) build | **No path at all**. `run-as` refuses, and there is no export |

The missing piece is an app-side export: copy each published Turn
directory, unchanged, to a folder the user picks (e.g. `Documents/stsloop/`
through the Storage Access Framework). Syncthing or rsync can then mirror
that folder. Until that exists, use `adb`.

## Today: pull with `adb` (debug build)

`run-as` runs a command as the app's own user, so it can read `files/`.
`tar` streams the whole directory out at once:

```sh
mkdir -p ~/stsloop/files && cd ~/stsloop/files
mise exec -- adb exec-out run-as com.walnutgeek.stsloop sh -c 'cd files && tar cf - corpus' | tar xf -
uv run <repo>/scripts/corpus_load.py corpus
```

Run the same pull again whenever you want new Turns. It re-sends the whole
Corpus each time (tens of MB per hundred Turns), but every Turn file is
immutable, so overwriting it locally changes nothing. The test-mode route
logs in `corpus/sessions/` do grow, and the second pull picks up the new
lines. Only `corpus/` is copied, never `corpus-staging/`, so no half-written
Turn arrives: a Turn directory is renamed into `corpus/` only once its WAV
and `turn.json` are complete.

### Over Wi-Fi or Tailscale instead of USB

Android 11+ "Wireless debugging" serves `adb` over the network, so the same
command works without a cable:

1. On the phone: Settings → System → Developer options → Wireless debugging
   → on. Phone and Linux box on the same Wi-Fi for the first pairing.
2. "Pair device with pairing code", then on the Linux box
   `mise exec -- adb pair <phone-ip>:<pairing-port>` and type the code.
3. `mise exec -- adb connect <phone-ip>:<port>` (the port on the Wireless
   debugging screen, not the pairing port; it changes whenever wireless
   debugging is switched off and on).
4. Run the pull above.

With Tailscale on both machines, `<phone-ip>` can be the phone's Tailscale
address (`100.x.y.z`), so this also works away from home. Two limits:
Android turns wireless debugging off when the phone leaves Wi-Fi, and none of
this has been tried with this app yet.

## Once the app exports the Corpus: Syncthing (recommended)

Syncthing is the recommended setup once the export exists. It runs in the
background on both ends, needs no cloud, copies only new files, and resumes
after connectivity gaps. The **Linux** side can be set up today.

On the Linux box:

1. Install Syncthing (`sudo pacman -S syncthing`, `sudo apt install syncthing`,
   ...), then `systemctl --user enable --now syncthing`.
2. Open the web UI at <http://127.0.0.1:8384> and note this device's ID
   (Actions → Show ID).

On the phone:

3. Install the maintained Syncthing for Android fork (*Syncthing-Fork*, from
   F-Droid or its GitHub releases). The original Syncthing Android app was
   discontinued in 2024.
4. Add the Linux box as a remote device (its ID from step 2).
5. Add a folder pointing at the export folder (e.g. `Documents/stsloop/corpus`),
   **Folder type: Send Only**, shared with the Linux box.

On the Linux box again:

6. Accept the folder when the web UI offers it. Put it at e.g.
   `~/stsloop/corpus` and set **Folder type: Receive Only**. The phone stays
   the only writer, so the mirror can never push a change back.
7. Leave file versioning off: nothing is ever edited or deleted.
8. `uv run <repo>/scripts/corpus_load.py ~/stsloop/corpus`.

Syncthing copies a file to a hidden temporary name and renames it into place
once complete, and it puts `.stfolder` (and, if versioning is on,
`.stversions`) in the folder root. The loader skips dot entries. A Turn caught
mid-sync shows up under "Problems" as `turn_json_missing` or `audio_missing`
and is fine on the next run.

## Alternative, also after the export: `rsync` over Tailscale

If you would rather not run a sync daemon, push from the phone with Termux
over Tailscale:

1. Tailscale on both machines; `sshd` running on the Linux box. Note its
   Tailscale name (`tailscale status`).
2. On the phone: install Termux (from F-Droid), then
   `pkg install rsync openssh` and `termux-setup-storage` (grants Termux the
   shared storage, where the export lives).
3. Generate a key (`ssh-keygen`) and add it to the Linux box's
   `~/.ssh/authorized_keys`.
4. Each time you want the new Turns:

   ```sh
   rsync -a --ignore-existing ~/storage/shared/Documents/stsloop/corpus/ <you>@<linux-box>:stsloop/corpus/
   ```

   `--ignore-existing` is safe because a Turn file never changes; drop it if
   you also want the test-mode route logs (`sessions/*.jsonl`), which grow.

## Loading it

`scripts/corpus_load.py` reads the mirror as plain files. It takes the
`corpus/` directory, or the `files/` directory that holds it:

```sh
uv run scripts/corpus_load.py ~/stsloop/files                    # stats, tombstoned Turns left out
uv run scripts/corpus_load.py ~/stsloop/files --tombstoned mark  # counted, with a tombstoned column
uv run scripts/corpus_load.py ~/stsloop/files --no-verify        # skip hashing the WAVs
uv run scripts/corpus_load_test.py                               # the loader's own tests
```

It verifies each `audio.sha256` against its WAV, applies tombstones as
`docs/mvp.md` "Corpus format" defines them, and prints Turn counts by kind
and by Bucket, the Declaration rate, the tombstone count, and every problem
it found. A classifier experiment imports it:

```python
import sys; sys.path.insert(0, "<repo>/scripts")
import corpus_load

for t in corpus_load.load_turns("~/stsloop/files/corpus"):  # live Turns, audio verified
    t.audio_path, t.text, t.bucket, t.declared, t.kind, t.raw
```
