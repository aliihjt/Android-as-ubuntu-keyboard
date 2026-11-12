"""The laptop's screen in the iPhone web app (Fedora): Laptop Screen page.

Records the screen through GNOME's screen-sharing portal (the laptop asks once,
"Share your screen?", then remembers it with a restore token) and GStreamer
turns it into small JPEG pictures, about 12 a second, sent to each viewer as
binary WebSocket messages. Each viewer always gets the newest picture: a slow
moment on Wi-Fi skips pictures instead of falling behind.

Some laptops give a black picture one way and the real screen another way, so
there are three ways to get the pictures, tried in turn until one shows the
screen (the one that worked is remembered for next time):
  gl      the portal's stream copied out on the graphics card (GNOME's fast path)
  memory  the portal's stream copied in memory
  shell   pictures taken by Fedora Pad's GNOME helper (gnome-extension), about 4
          a second. Only after the laptop said yes to "Share your screen?", and
          the portal stays open, so GNOME's "sharing" sign stays in the top bar.
A picture that is one flat colour all over (the black picture) doesn't count.

Needs: python3-gobject, pipewire-gstreamer (pipewiresrc), gstreamer1-plugins-good (jpegenc, pngdec).
"""
import os
import sys
import threading
import time

try:
    import gi
    gi.require_version("Gst", "1.0")
    from gi.repository import Gio, GLib, Gst
except (ImportError, ValueError):
    Gio = GLib = Gst = None

FPS = 12
SHELL_FPS = 4
PORTAL = ("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")
CAST = "org.freedesktop.portal.ScreenCast"
EXTENSION = ("org.gnome.Shell", "/io/github/aliihjt/TabletDesk", "io.github.aliihjt.TabletDesk")
METHODS = ("gl", "memory", "shell")
BLACK_HELP = ("The laptop only gives a black picture. Log out of Fedora and back in once (that turns on "
              "Fedora Pad's newer GNOME helper), then open Laptop Screen again.")


def _tail(width, fps):
    """Scale, pace and encode: raw video -> JPEG pictures for the phone."""
    rate = f"videorate drop-only=true ! video/x-raw,framerate={int(fps)}/1 ! " if fps else ""
    return ("videoconvert ! videoscale ! "
            f"video/x-raw,width={int(width)},pixel-aspect-ratio=1/1 ! {rate}"
            "videoconvert ! video/x-raw,format=I420 ! "
            "jpegenc name=enc quality=55 ! appsink name=sink emit-signals=true max-buffers=1 drop=true sync=false")


def pipeline_text(fd, node, width, fps=FPS, method="memory", props=("keepalive-time", "always-copy")):
    """The GStreamer pipeline: the portal's PipeWire stream (or the helper's pictures) -> scaled JPEGs.
    props: the pipewiresrc settings this laptop's version knows."""
    if method == "shell":
        return ("appsrc name=src is-live=true format=time do-timestamp=true caps=image/png,framerate=0/1 ! "
                "pngdec ! " + _tail(width, 0))
    src = f"pipewiresrc fd={fd} path={node} do-timestamp=true"
    if "keepalive-time" in props:
        src += " keepalive-time=1000"  # the same picture again each second while nothing moves
    if method == "gl":  # GNOME hands over graphics-card buffers: copy them out on the card
        return f"{src} ! glupload ! glcolorconvert ! gldownload ! " + _tail(width, fps)
    if "always-copy" in props:
        src += " always-copy=true"
    return f"{src} ! " + _tail(width, fps)


def flat(data, width, height):
    """True when an I420 picture is one colour all over (the black picture some laptops give)."""
    stride = (int(width) + 3) & ~3
    if not data or width <= 0 or height <= 0 or len(data) < stride * height:
        return True
    lo, hi = 255, 0
    step_x = max(1, width // 64)
    for row in range(0, height, max(1, height // 48)):
        line = data[row * stride: row * stride + width: step_x]
        lo, hi = min(lo, min(line)), max(hi, max(line))
        if hi - lo > 3:
            return False
    return True


class Judge:
    """Does a way of capturing show the screen? 'good', 'black', 'nothing', or None (still looking)."""

    def __init__(self):
        self.frames = self.black = 0
        self.good = False

    def frame(self, is_flat):
        self.frames += 1
        if is_flat:
            self.black += 1
        else:
            self.good = True

    def verdict(self, elapsed):
        if self.good:
            return "good"
        if self.black >= 6 and elapsed > 4:
            return "black"
        if elapsed > 8:
            return "black" if self.frames else "nothing"
        return None


class Viewer:
    """One iPhone watching: sends only the newest picture, from its own thread."""

    def __init__(self, send_binary, send_json):
        self.send_binary, self.send_json = send_binary, send_json
        self.told_size = False
        self.frame = None
        self.cond = threading.Condition()
        self.open = True
        threading.Thread(target=self._run, daemon=True).start()

    def offer(self, jpeg):
        with self.cond:
            self.frame = jpeg  # an older one not sent yet is simply replaced
            self.cond.notify()

    def close(self):
        with self.cond:
            self.open = False
            self.cond.notify()

    def _run(self):
        while True:
            with self.cond:
                while self.open and self.frame is None:
                    self.cond.wait()
                if not self.open:
                    return
                frame, self.frame = self.frame, None
            try:
                self.send_binary(frame)
            except Exception:  # noqa: BLE001 - the iPhone went away
                self.open = False
                return


class ScreenCast:
    def __init__(self, conf_dir):
        self.token_file = os.path.join(conf_dir, "screencast-token")
        self.method_file = os.path.join(conf_dir, "screencast-method")
        self.viewers = {}           # key -> Viewer
        self.lock = threading.Lock()
        self.pipeline = None
        self.ctx = None
        self.bus = None
        self.session = None
        self.node = None            # the portal stream's PipeWire node
        self.width = 1280
        self.size = None            # (w, h) of the pictures, once known
        self.judge = Judge()

    # ---- viewers

    def watch(self, key, send_binary, send_json, width=1280):
        """Start sending pictures to a viewer. Returns None, or what is missing."""
        missing = self.missing()
        if missing:
            return missing
        with self.lock:
            old = self.viewers.pop(key, None)
            if old:
                old.close()
            self.viewers[key] = Viewer(send_binary, send_json)
            self.width = max(480, min(1600, int(width or 1280)))
            running = self.pipeline is not None or self.ctx is not None
        if not running:
            threading.Thread(target=self._run, daemon=True).start()
        return None

    def leave(self, key):
        with self.lock:
            v = self.viewers.pop(key, None)
            empty = not self.viewers
        if v:
            v.close()
        if empty:
            self.stop()

    @staticmethod
    def missing():
        if Gst is None:
            return "Install once on the laptop: sudo dnf install python3-gobject gstreamer1"
        Gst.init(None)
        for element, package in (("pipewiresrc", "pipewire-gstreamer"), ("jpegenc", "gstreamer1-plugins-good"),
                                  ("pngdec", "gstreamer1-plugins-good"), ("appsink", "gstreamer1-plugins-base")):
            if Gst.ElementFactory.find(element) is None:
                return "Install once on the laptop: sudo dnf install " + package
        return None

    def _deliver(self, jpeg):
        with self.lock:
            viewers = list(self.viewers.values())
        for v in viewers:
            if not v.told_size and self.size:  # the picture's shape, for tapping at the right place
                v.told_size = True
                try:
                    v.send_json({"t": "screen", "ok": True, "w": self.size[0], "h": self.size[1]})
                except Exception:  # noqa: BLE001
                    pass
            v.offer(jpeg)

    def _tell(self, msg):
        with self.lock:
            viewers = list(self.viewers.values())
        for v in viewers:
            try:
                v.send_json(msg)
            except Exception:  # noqa: BLE001
                pass

    # ---- the portal (asks once on the laptop, then remembers)

    def _request(self, method, args):
        """Calls a portal method and waits for its Response signal: (code, results)."""
        token = "fedorapad%d" % int(time.monotonic() * 1000)
        sender = self.bus.get_unique_name()[1:].replace(".", "_")
        path = f"/org/freedesktop/portal/desktop/request/{sender}/{token}"
        done = {}

        def response(conn, s, p, i, sig, params):
            done["r"] = params.unpack()
        sub = self.bus.signal_subscribe(PORTAL[0], "org.freedesktop.portal.Request", "Response", path, None,
                                        Gio.DBusSignalFlags.NONE, response)
        args[-1]["handle_token"] = GLib.Variant("s", token)
        self.bus.call_sync(PORTAL[0], PORTAL[1], CAST, method, GLib.Variant(self._sig(method), tuple(args)),
                           None, Gio.DBusCallFlags.NONE, 60000, None)
        end = time.monotonic() + 120  # the "Share your screen?" box can take a while to answer
        while "r" not in done and time.monotonic() < end:
            self.ctx.iteration(True)
        self.bus.signal_unsubscribe(sub)
        return done.get("r", (2, {}))

    @staticmethod
    def _sig(method):
        return {"CreateSession": "(a{sv})", "SelectSources": "(oa{sv})", "Start": "(osa{sv})"}[method]

    def _open(self):
        """The portal session (the first time the laptop asks "Share your screen?") -> self.node."""
        code, res = self._request("CreateSession", [{"session_handle_token": GLib.Variant("s", "fedorapad")}])
        if code != 0:
            raise RuntimeError("the laptop didn't allow screen sharing")
        self.session = res["session_handle"]
        opts = {"types": GLib.Variant("u", 1), "multiple": GLib.Variant("b", False),
                "cursor_mode": GLib.Variant("u", 2), "persist_mode": GLib.Variant("u", 2)}
        try:
            with open(self.token_file) as f:
                opts["restore_token"] = GLib.Variant("s", f.read().strip())
        except OSError:
            pass
        code, _ = self._request("SelectSources", [self.session, opts])
        if code != 0:
            raise RuntimeError("the laptop didn't allow screen sharing")
        code, res = self._request("Start", [self.session, "", {}])
        if code != 0 or not res.get("streams"):
            raise RuntimeError("screen sharing was cancelled on the laptop")
        if res.get("restore_token"):  # next time: no question
            try:
                with open(os.open(self.token_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
                    f.write(res["restore_token"])
            except OSError:
                pass
        self.node = res["streams"][0][0]

    def _remote(self):
        """A new connection to the stream: each try's pipewiresrc takes its own."""
        result, fdlist = self.bus.call_with_unix_fd_list_sync(
            PORTAL[0], PORTAL[1], CAST, "OpenPipeWireRemote", GLib.Variant("(oa{sv})", (self.session, {})),
            GLib.VariantType("(h)"), Gio.DBusCallFlags.NONE, 10000, None, None)
        return fdlist.get(result.unpack()[0])

    # ---- which way of capturing

    def _methods(self):
        """The ways to try, the one that worked last time first."""
        order = list(METHODS)
        if not all(Gst.ElementFactory.find(e) for e in ("glupload", "glcolorconvert", "gldownload")):
            order.remove("gl")
        try:
            with open(self.method_file) as f:
                last = f.read().strip()
            if last in order:
                order.remove(last)
                order.insert(0, last)
        except OSError:
            pass
        return order

    def _remember(self, method):
        try:
            with open(self.method_file, "w") as f:
                f.write(method)
        except OSError:
            pass

    # ---- recording

    def _run(self):
        self.ctx = GLib.MainContext()
        self.ctx.push_thread_default()
        try:
            self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
            self._open()
            methods = self._methods()
            for n, method in enumerate(methods):
                outcome = self._try(method)
                print("Laptop Screen:", method, "->", outcome, flush=True)
                if outcome == "stopped":
                    return
                if n + 1 < len(methods):
                    self._tell({"t": "screen", "ok": True,
                                "note": "No picture yet: trying another way (%d of %d)…" % (n + 2, len(methods))})
            self._tell_error(BLACK_HELP)
        except (GLib.Error, RuntimeError, KeyError, IndexError) as err:
            print("Laptop Screen:", err, file=sys.stderr)
            self._tell_error(str(getattr(err, "message", err)))
        finally:
            self._close_session()
            self.ctx = None

    def _pipewire_props(self):
        el = Gst.ElementFactory.make("pipewiresrc", None)
        return tuple(p for p in ("keepalive-time", "always-copy") if el is not None and el.find_property(p))

    def _try(self, method):
        """Runs one way of capturing. 'stopped': everybody left (after it worked, or not);
        otherwise why it didn't work: 'black', 'nothing' or 'error: ...'."""
        try:
            fd = None if method == "shell" else self._remote()
            pipe = Gst.parse_launch(pipeline_text(fd, self.node, self.width, method=method,
                                                  props=self._pipewire_props()))
        except GLib.Error as err:
            return "error: " + err.message
        pipe.get_by_name("sink").connect("new-sample", self._sample)
        pipe.get_by_name("enc").get_static_pad("sink").add_probe(Gst.PadProbeType.BUFFER, self._look)
        self.judge, self.size = Judge(), None
        with self.lock:
            if not self.viewers:  # everybody left while the laptop was asking
                pipe.set_state(Gst.State.NULL)
                return "stopped"
            self.pipeline = pipe
            for v in self.viewers.values():
                v.told_size = False
        src = pipe.get_by_name("src")  # only in "shell"
        shot_file = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/tmp", "fedorapad-screen.png")
        bus = pipe.get_bus()
        pipe.set_state(Gst.State.PLAYING)
        start, next_shot, remembered = time.monotonic(), 0.0, False
        try:
            while self.pipeline is pipe:
                msg = bus.pop_filtered(Gst.MessageType.ERROR)
                if msg is not None:
                    return "error: " + msg.parse_error()[0].message
                verdict = self.judge.verdict(time.monotonic() - start)
                if verdict == "good" and not remembered:
                    remembered = True
                    self._remember(method)
                    self._tell({"t": "screen", "ok": True, "note": ""})
                    print("Laptop Screen: streaming (%s)" % method, flush=True)
                elif verdict in ("black", "nothing"):
                    return verdict
                if src is not None and time.monotonic() >= next_shot:
                    next_shot = time.monotonic() + 1 / SHELL_FPS
                    if not self._shoot(src, shot_file):
                        return "error: the GNOME helper can't take pictures yet (log out and in once)"
                self.ctx.iteration(False)
                time.sleep(0.03 if src is not None else 0.2)
            return "stopped"
        finally:
            with self.lock:
                if self.pipeline is pipe:
                    self.pipeline = None
            pipe.set_state(Gst.State.NULL)

    def _shoot(self, src, path):
        """One picture of the laptop screen from the GNOME helper, into the 'shell' pipeline."""
        try:
            res = self.bus.call_sync(EXTENSION[0], EXTENSION[1], EXTENSION[2], "Snapshot", None,
                                     GLib.VariantType("(b)"), Gio.DBusCallFlags.NONE, 3000, None)
            if not res.unpack()[0]:
                return False
            with open(path, "rb") as f:
                data = f.read()
        except (GLib.Error, OSError):
            return False
        src.emit("push-buffer", Gst.Buffer.new_wrapped(data))
        return True

    def _look(self, pad, info):
        """Just before encoding: is the picture all one colour?"""
        if not self.judge.good:
            caps, buf = pad.get_current_caps(), info.get_buffer()
            if caps is not None and buf is not None:
                st = caps.get_structure(0)
                ok, m = buf.map(Gst.MapFlags.READ)
                if ok:
                    try:
                        self.judge.frame(flat(bytes(m.data), st.get_value("width"), st.get_value("height")))
                    finally:
                        buf.unmap(m)
        return Gst.PadProbeReturn.OK

    def _tell_error(self, text):
        self._tell({"t": "screen", "ok": False, "error": text})

    def _sample(self, sink):
        sample = sink.emit("pull-sample")
        if sample is None:
            return Gst.FlowReturn.OK
        if self.size is None:
            st = sample.get_caps().get_structure(0)
            self.size = (st.get_value("width"), st.get_value("height"))
        buf = sample.get_buffer()
        ok, info = buf.map(Gst.MapFlags.READ)
        if ok:
            try:
                self._deliver(bytes(info.data))
            finally:
                buf.unmap(info)
        return Gst.FlowReturn.OK

    def stop(self):
        with self.lock:
            pipe, self.pipeline = self.pipeline, None
        if pipe is not None:  # the recording thread sees it and closes the pipeline itself
            print("Laptop Screen: stopped", flush=True)

    def _close_session(self):
        if self.session and self.bus:
            try:
                self.bus.call_sync(PORTAL[0], self.session, "org.freedesktop.portal.Session", "Close",
                                   None, None, Gio.DBusCallFlags.NONE, 3000, None)
            except GLib.Error:
                pass
        self.session = None
        self.size = None
