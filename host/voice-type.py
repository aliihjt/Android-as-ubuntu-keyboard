#!/usr/bin/env python3
"""Voice typing for Fedora, fully offline (Whisper). English, Persian and ~100 more.

Press the shortcut (Ctrl+Super+D) to start listening, press it again to stop
(or hold the mic in Fedora Pad › Push to Talk with "Laptop mic"):
the words are typed where your cursor is, in any app. Stops by itself after
two minutes. Settings: ~/.config/tabletdesk/voice.json
    {"model": "small", "language": "auto"}     language: auto, en, fa, ...
Install with ./host/voice-setup.sh
"""
import json
import os
import signal
import subprocess
import sys
import time

CACHE = os.path.expanduser("~/.cache/fedora-pad")
PIDFILE = os.path.join(CACHE, "voice.pid")
WAV = os.path.join(CACHE, "voice.wav")
CONFIG = os.path.expanduser("~/.config/tabletdesk/voice.json")
MODELS = os.path.expanduser("~/.local/share/fedora-pad/voice/models")
MAX_SECONDS = 120
TERMINALS = ("terminal", "ptyxis", "console", "konsole", "kitty", "alacritty", "wezterm", "tilix", "foot")


def notify(text, replace_id=None):
    """Desktop notification; returns its id so the next one replaces it."""
    cmd = ["notify-send", "-a", "Voice typing", "-p", "Voice typing", text]
    if replace_id:
        cmd[1:1] = ["-r", str(replace_id)]
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=3).stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def config():
    try:
        with open(CONFIG) as f:
            c = json.load(f)
    except (OSError, ValueError):
        c = {}
    return {"model": str(c.get("model", "small")), "language": str(c.get("language", "auto"))}


def running_pid():
    try:
        with open(PIDFILE) as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def focused_app():
    try:
        out = subprocess.run(["gdbus", "call", "--session", "--dest", "org.gnome.Shell",
                              "--object-path", "/io/github/aliihjt/TabletDesk",
                              "--method", "io.github.aliihjt.TabletDesk.FocusedApp"],
                             capture_output=True, text=True, timeout=2).stdout
        return out.lower()
    except (OSError, subprocess.SubprocessError):
        return ""


def paste(text):
    """Types the text where the cursor is: clipboard + Ctrl+V (works for every language)."""
    try:
        old = subprocess.run(["wl-paste", "-n", "-t", "text"], capture_output=True, timeout=2).stdout
    except (OSError, subprocess.SubprocessError):
        old = None
    subprocess.run(["wl-copy"], input=text.encode(), timeout=3)
    terminal = any(t in focused_app() for t in TERMINALS)
    from evdev import UInput, ecodes as e
    keys = [e.KEY_LEFTCTRL] + ([e.KEY_LEFTSHIFT] if terminal else []) + [e.KEY_V]
    with UInput({e.EV_KEY: keys}, name="Fedora Pad voice typing") as ui:
        time.sleep(0.4)  # let GNOME notice the new keyboard
        for k in keys:
            ui.write(e.EV_KEY, k, 1)
            ui.syn()
        for k in reversed(keys):
            ui.write(e.EV_KEY, k, 0)
            ui.syn()
        time.sleep(0.2)
    if old:  # put back what was in the clipboard before
        time.sleep(0.6)
        subprocess.run(["wl-copy"], input=old, timeout=3)


def transcribe(cfg):
    from faster_whisper import WhisperModel
    model = WhisperModel(cfg["model"], device="cpu", compute_type="int8", download_root=MODELS)
    lang = None if cfg["language"] in ("", "auto") else cfg["language"]
    segments, _ = model.transcribe(WAV, language=lang, beam_size=5, vad_filter=True)
    return " ".join(s.text.strip() for s in segments).strip()


def record_and_type():
    """Runs in the background: records until stopped, then types the words."""
    cfg = config()
    nid = notify("🎙 Listening… press Ctrl+Super+D again to stop.")
    rec = subprocess.Popen(["pw-record", "--rate", "16000", "--channels", "1", "--format", "s16", WAV])
    with open(PIDFILE, "w") as f:
        f.write(str(rec.pid))
    try:
        rec.wait(timeout=MAX_SECONDS)
    except subprocess.TimeoutExpired:
        rec.send_signal(signal.SIGINT)
        rec.wait()
    finally:
        try:
            os.remove(PIDFILE)
        except OSError:
            pass
    if not os.path.exists(WAV) or os.path.getsize(WAV) < 8000:  # under ~0.25 s of sound
        notify("Too short: keep holding while you speak.", nid)
        try:
            os.remove(WAV)
        except OSError:
            pass
        return
    nid = notify("✍️ Writing…", nid) or nid
    try:
        text = transcribe(cfg)
    except Exception as err:  # missing model, broken audio...
        notify("Couldn't understand the recording: %s" % err, nid)
        return
    finally:
        try:
            os.remove(WAV)
        except OSError:
            pass
    if not text:
        notify("Didn't hear any words.", nid)
        return
    paste(text)
    notify("✅ " + (text if len(text) < 80 else text[:77] + "…"), nid)


def main():  # no argument: start / stop; "start" or "stop" for Push to Talk
    os.makedirs(CACHE, exist_ok=True)
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg == "--run":
        record_and_type()
        return
    pid = running_pid()
    if pid:  # second press (or Push to Talk let go): stop; the background run types the text
        if arg != "start":
            os.kill(pid, signal.SIGINT)
        return
    if arg == "stop":
        # let go before listening began: wait a moment for it, then stop
        for _ in range(60):
            time.sleep(0.05)
            pid = running_pid()
            if pid:
                os.kill(pid, signal.SIGINT)
                break
        return
    # first press: start in the background so the shortcut returns at once
    subprocess.Popen([sys.executable, os.path.abspath(__file__), "--run"],
                     start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(20):  # wait until it's listening, so a quick second press works
        if running_pid():
            break
        time.sleep(0.05)


if __name__ == "__main__":
    main()
