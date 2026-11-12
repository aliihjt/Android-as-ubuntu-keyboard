"""The laptop's screen in the iPhone web app (Fedora): Laptop Screen page.

Records the screen through GNOME's screen-sharing portal (the laptop asks once,
"Share your screen?", then remembers it with a restore token) and GStreamer
turns it into small JPEG pictures, about 12 a second, sent to each viewer as
binary WebSocket messages. Each viewer always gets the newest picture: a slow
moment on Wi-Fi skips pictures instead of falling behind.

Needs: python3-gobject, pipewire-gstreamer (pipewiresrc), gstreamer1-plugins-good (jpegenc).
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
PORTAL = ("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")
CAST = "org.freedesktop.portal.ScreenCast"


def pipeline_text(fd, node, width, fps=FPS):
    """The GStreamer pipeline: the portal's PipeWire stream -> scaled JPEG pictures."""
    return (f"pipewiresrc fd={fd} path={node} do-timestamp=true keepalive-time=1000 ! "
            "videoconvert ! videoscale ! "
            f"video/x-raw,width={int(width)},pixel-aspect-ratio=1/1 ! "
            f"videorate drop-only=true ! video/x-raw,framerate={int(fps)}/1 ! "
            "jpegenc quality=55 ! appsink name=sink emit-signals=true max-buffers=1 drop=true sync=false")


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
        self.viewers = {}           # key -> Viewer
        self.lock = threading.Lock()
        self.pipeline = None
        self.ctx = None
        self.bus = None
        self.session = None
        self.width = 1280
        self.size = None            # (w, h) of the pictures, once known

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
                                  ("appsink", "gstreamer1-plugins-base")):
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

    # ---- the portal (asks once on the laptop, then remembers)

    def _request(self, method, args, fds=None):
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
        """The portal session -> (PipeWire fd, node id)."""
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
        node = res["streams"][0][0]
        result, fdlist = self.bus.call_with_unix_fd_list_sync(
            PORTAL[0], PORTAL[1], CAST, "OpenPipeWireRemote", GLib.Variant("(oa{sv})", (self.session, {})),
            GLib.VariantType("(h)"), Gio.DBusCallFlags.NONE, 10000, None, None)
        return fdlist.get(result.unpack()[0]), node

    # ---- recording

    def _run(self):
        self.ctx = GLib.MainContext()
        self.ctx.push_thread_default()
        try:
            self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
            fd, node = self._open()
            pipe = Gst.parse_launch(pipeline_text(fd, node, self.width))
            pipe.get_by_name("sink").connect("new-sample", self._sample)
            with self.lock:
                if not self.viewers:  # everybody left while the laptop was asking
                    raise RuntimeError("no viewer")
                self.pipeline = pipe
            pipe.set_state(Gst.State.PLAYING)
            print("Laptop Screen: streaming", flush=True)
            while self.pipeline is pipe:
                self.ctx.iteration(False)
                time.sleep(0.2)
        except (GLib.Error, RuntimeError, KeyError, IndexError) as err:
            print("Laptop Screen:", err, file=sys.stderr)
            self._tell_error(str(getattr(err, "message", err)))
        finally:
            self._close_session()
            self.ctx = None

    def _tell_error(self, text):
        with self.lock:
            viewers = list(self.viewers.values())
        for v in viewers:
            try:
                v.send_json({"t": "screen", "ok": False, "error": text})
            except Exception:  # noqa: BLE001
                pass

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
        if pipe is not None:
            pipe.set_state(Gst.State.NULL)
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

