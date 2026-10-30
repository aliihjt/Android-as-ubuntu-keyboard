"""Phone or tablet as the PC's webcam and microphone (Fedora).

Apps (Zoom, Meet, OBS...) see "Fedora Pad Camera" (a v4l2loopback device) and
"Fedora Pad Microphone" (a PipeWire virtual source). Two ways in:

  android  the tablet's camera and mic over the USB cable, with scrcpy
           (Android 12 or newer); host/webcam-setup.sh installs everything
  web      the iPhone / iPad web app sends JPEG frames and 16-bit audio over
           its encrypted WebSocket; ffmpeg and pw-cat feed them in
"""
import base64
import glob
import os
import shutil
import subprocess
import threading

SCRCPY = os.path.expanduser("~/.local/share/fedora-pad/scrcpy/scrcpy")
SINK = "fedorapad_mic_sink"
WIDTH, HEIGHT = 1280, 720


def camera_device():
    """The loopback device labelled "Fedora Pad Camera", or None."""
    for d in sorted(glob.glob("/sys/devices/virtual/video4linux/video*")):
        try:
            with open(os.path.join(d, "name")) as f:
                if f.read().strip() == "Fedora Pad Camera":
                    return "/dev/" + os.path.basename(d)
        except OSError:
            pass
    return None


class Webcam:
    def __init__(self):
        self.lock = threading.Lock()
        self.source = None      # "android" | "web" | None
        self.procs = []
        self.modules = []       # PipeWire modules for the virtual mic
        self.ffmpeg = self.audio = None
        self.rate = None

    # ---- the virtual microphone ----

    def _mic_on(self):
        if self.modules:
            return
        for args in (["module-null-sink", f"sink_name={SINK}",
                      'sink_properties=device.description="Fedora Pad Mic Input"'],
                     ["module-remap-source", f"master={SINK}.monitor", "source_name=fedorapad_mic",
                      'source_properties=device.description="Fedora Pad Microphone"']):
            out = subprocess.run(["pactl", "load-module"] + args, capture_output=True, text=True)
            if out.returncode == 0 and out.stdout.strip().isdigit():
                self.modules.append(out.stdout.strip())

    def _mic_off(self):
        for m in reversed(self.modules):
            subprocess.run(["pactl", "unload-module", m], capture_output=True)
        self.modules = []

    # ---- start / stop ----

    def status(self, error=None):
        msg = {"t": "webcam", "on": self.source is not None, "src": self.source or ""}
        if error:
            msg["error"] = error
        return msg

    def start(self, src, facing="front", mic=True):
        with self.lock:
            self._stop()
            dev = camera_device()
            if not dev:
                return self.status("Run ./host/webcam-setup.sh on the PC once (and restart if it asks).")
            if mic and shutil.which("pactl"):
                self._mic_on()
            if src == "android":
                if not os.path.exists(SCRCPY):
                    return self.status("Run ./host/webcam-setup.sh on the PC once.")
                env = dict(os.environ, PULSE_SINK=SINK, ADB=shutil.which("adb") or "adb")
                argv = [SCRCPY, "--video-source=camera", f"--camera-facing={'back' if facing == 'back' else 'front'}",
                        f"--camera-size={WIDTH}x{HEIGHT}", f"--v4l2-sink={dev}", "--no-video-playback",
                        "--no-control"]
                argv.append("--audio-source=mic" if mic else "--no-audio")
                try:
                    proc = subprocess.Popen(argv, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                            text=True, errors="replace")
                except OSError as err:
                    return self.status(str(err))
                self.procs.append(proc)
                self.source = "android"
                threading.Thread(target=self._watch, args=(proc,), daemon=True).start()
            else:
                vf = (f"scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
                      f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2,format=yuv420p")
                try:
                    self.ffmpeg = subprocess.Popen(
                        ["ffmpeg", "-loglevel", "error", "-use_wallclock_as_timestamps", "1", "-f", "mjpeg",
                         "-i", "-", "-vf", vf, "-r", "30", "-f", "v4l2", dev],
                        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                except OSError as err:
                    return self.status("ffmpeg is missing: " + str(err))
                self.procs.append(self.ffmpeg)
                self.source = "web"
            return self.status()

    def _watch(self, proc):
        _, err = proc.communicate()
        with self.lock:
            if proc in self.procs and self.source == "android":
                print("webcam (scrcpy) stopped:", (err or "").strip()[-300:], flush=True)
                self._stop()

    def stop(self):
        with self.lock:
            self._stop()
        return self.status()

    def _stop(self):
        for p in self.procs:
            try:
                if p.stdin:
                    p.stdin.close()
            except OSError:
                pass
            if p.poll() is None:
                p.terminate()
        self.procs, self.ffmpeg, self.audio, self.rate = [], None, None, None
        self.source = None
        self._mic_off()

    # ---- frames and sound from the web app ----

    def frame(self, jpg_b64):
        f = self.ffmpeg
        if self.source != "web" or not f:
            return
        try:
            f.stdin.write(base64.b64decode(jpg_b64))
            f.stdin.flush()
        except (OSError, ValueError):
            pass

    def pcm(self, pcm_b64, rate):
        if self.source != "web" or not self.modules:
            return
        rate = int(rate) if str(rate).isdigit() and 8000 <= int(rate) <= 96000 else 48000
        if self.audio is None or self.rate != rate or self.audio.poll() is not None:
            try:
                self.audio = subprocess.Popen(["pw-cat", "--playback", "--target", SINK, "--format", "s16",
                                               "--rate", str(rate), "--channels", "1", "-"],
                                              stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                              stderr=subprocess.DEVNULL)
            except OSError:
                return
            self.rate = rate
            self.procs.append(self.audio)
        try:
            self.audio.stdin.write(base64.b64decode(pcm_b64))
            self.audio.stdin.flush()
        except (OSError, ValueError):
            pass
