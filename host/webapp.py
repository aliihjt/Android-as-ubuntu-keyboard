"""Fedora Pad as a web app for iPad, iPhone or any browser on the home Wi-Fi.

Shared by the Fedora host (tabletdesk_host.py) and the Windows host
(windows/fedorapad_host.py). Two small servers:

  http://PC:7781   setup page: the "Fedora Pad local CA" certificate to install
                   on the iPad once, and how to add the app to the Home Screen
  https://PC:7780  the app itself (host/web/), pairing, and a WebSocket that
                   carries the same JSON messages as the Android app

Everything on 7780 is encrypted with a certificate from the local CA, so the
iPad trusts it once the CA is installed. A device must pair first: it asks,
the PC shows a 6-digit code, the user types it on the device, and the device
gets a long random token (only its hash is stored on the PC).
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import socket
import socketserver
import ssl
import struct
import subprocess
import sys
import threading
import time
from urllib.parse import parse_qs, urlsplit

WEB_PORT = 7780
SETUP_PORT = 7781
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
FILES = {"/": ("index.html", "text/html; charset=utf-8"),
         "/index.html": ("index.html", "text/html; charset=utf-8"),
         "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"),
         "/icon.png": ("icon.png", "image/png"),
         "/icon-512.png": ("icon-512.png", "image/png")}
MAX_FRAME = 1 << 20  # file chunks are ~64 KB
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
NO_WINDOW = 0x08000000 if os.name == "nt" else 0


class WebApp:
    def __init__(self, conf_dir, handle, hello, on_open, on_close, notify, ips, openssl="openssl"):
        self.conf = conf_dir
        self.handle = handle        # handle(msg, reply): the host's own message handler
        self.hello = hello          # () -> the hello message
        self.on_open = on_open      # (writer) -> start pushing focus changes etc. to it
        self.on_close = on_close
        self.notify = notify        # (title, text) -> show something on the PC's screen
        self.ips = ips              # () -> this PC's addresses on the home network
        self.openssl = openssl
        self.lock = threading.Lock()
        self.pair = {"code": None, "until": 0.0, "tries": 0, "last_request": 0.0}
        os.makedirs(conf_dir, exist_ok=True)
        self.ca_crt = os.path.join(conf_dir, "web-ca.crt")
        self.ca_key = os.path.join(conf_dir, "web-ca.key")
        self.crt = os.path.join(conf_dir, "web.crt")
        self.key = os.path.join(conf_dir, "web.key")
        self.devices_file = os.path.join(conf_dir, "web-devices.json")

    # ---- certificates ----

    def _ssl(self, *args):
        subprocess.run([self.openssl] + list(args), capture_output=True, check=True, creationflags=NO_WINDOW)

    def names(self):
        host = socket.gethostname().split(".")[0]
        return host, sorted(set(self.ips()))

    def ensure_certs(self):
        """The local CA (once) and a server certificate for this PC's current
        names and addresses (renewed when they change). iOS rules: SHA-256,
        RSA 2048, at most 825 days, serverAuth, names in subjectAltName."""
        if not (os.path.exists(self.ca_crt) and os.path.exists(self.ca_key)):
            self._ssl("req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes", "-days", "3650",
                      "-subj", "/CN=Fedora Pad local CA (" + socket.gethostname()[:30] + ")",
                      "-addext", "basicConstraints=critical,CA:TRUE",
                      "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                      "-keyout", self.ca_key, "-out", self.ca_crt)
            _private(self.ca_key)
        host, ips = self.names()
        san = ",".join(["DNS:" + host + ".local", "DNS:" + host, "DNS:localhost", "IP:127.0.0.1"]
                       + ["IP:" + ip for ip in ips])
        stamp = os.path.join(self.conf, "web.san")
        if os.path.exists(self.crt) and _read(stamp) == san:
            return
        ext = os.path.join(self.conf, "web.ext")
        csr = os.path.join(self.conf, "web.csr")
        with open(ext, "w") as f:
            f.write("subjectAltName=" + san + "\nextendedKeyUsage=serverAuth\n"
                    "keyUsage=critical,digitalSignature,keyEncipherment\nbasicConstraints=CA:FALSE\n")
        self._ssl("req", "-newkey", "rsa:2048", "-sha256", "-nodes", "-subj", "/CN=" + host,
                  "-keyout", self.key, "-out", csr)
        _private(self.key)
        self._ssl("x509", "-req", "-sha256", "-in", csr, "-CA", self.ca_crt, "-CAkey", self.ca_key,
                  "-CAcreateserial", "-days", "800", "-extfile", ext, "-out", self.crt)
        for p in (csr, ext):
            os.remove(p)
        with open(stamp, "w") as f:
            f.write(san)

    # ---- paired devices ----

    def _devices(self):
        try:
            with open(self.devices_file) as f:
                d = json.load(f)
            return d if isinstance(d, list) else []
        except (OSError, ValueError):
            return []

    def known(self, token):
        h = hashlib.sha256(token.encode()).hexdigest()
        return any(hmac.compare_digest(h, str(d.get("hash", ""))) for d in self._devices())

    def request_code(self):
        with self.lock:
            now = time.time()
            if now - self.pair["last_request"] < 10:  # no notification spam from the network
                return False
            self.pair = {"code": "%06d" % secrets.randbelow(1_000_000), "until": now + 120,
                         "tries": 0, "last_request": now}
            code = self.pair["code"]
        self.notify("Fedora Pad pairing", "Type this code on the iPad: " + code)
        print("web app pairing code:", code, flush=True)
        return True

    def confirm_code(self, code, name):
        with self.lock:
            p = self.pair
            if not p["code"] or time.time() > p["until"] or p["tries"] >= 5:
                return None
            p["tries"] += 1
            if not hmac.compare_digest(str(code).strip(), p["code"]):
                return None
            p["code"] = None
            token = secrets.token_urlsafe(32)
            devices = self._devices()
            devices.append({"hash": hashlib.sha256(token.encode()).hexdigest(),
                            "name": str(name)[:60], "added": time.strftime("%Y-%m-%d %H:%M")})
            tmp = self.devices_file + ".tmp"
            with open(tmp, "w") as f:
                json.dump(devices, f, indent=1)
            _private(tmp)
            os.replace(tmp, self.devices_file)
        return token

    # ---- servers ----

    def start(self):
        try:
            self.ensure_certs()
        except (OSError, subprocess.CalledProcessError) as err:
            print("web app: could not make certificates:", err, file=sys.stderr)
            return
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(self.crt, self.key)
        app = self

        class AppServer(_Server):
            def get_request(self):
                sock, addr = self.socket.accept()
                return tls.wrap_socket(sock, server_side=True, do_handshake_on_connect=False), addr

        class AppHandler(_Handler):
            def route(self, method, path, query, headers, body):
                return app.route_app(self, method, path, query, headers, body)

        class SetupHandler(_Handler):
            def route(self, method, path, query, headers, body):
                return app.route_setup(self, method, path)

        try:
            web = AppServer(("0.0.0.0", WEB_PORT), AppHandler)
            setup = _Server(("0.0.0.0", SETUP_PORT), SetupHandler)
        except OSError as err:
            print("web app: port busy:", err, file=sys.stderr)
            return
        for srv in (web, setup):
            threading.Thread(target=srv.serve_forever, daemon=True).start()
        host, ips = self.names()
        print(f"web app on https://{host}.local:{WEB_PORT} (set up an iPad at http://{host}.local:{SETUP_PORT})",
              flush=True)
        # addresses change (another Wi-Fi): renew the certificate, new connections use it
        def renew():
            while True:
                time.sleep(60)
                try:
                    self.ensure_certs()
                    tls.load_cert_chain(self.crt, self.key)
                except (OSError, subprocess.CalledProcessError, ssl.SSLError):
                    pass
        threading.Thread(target=renew, daemon=True).start()

    def route_setup(self, h, method, path):
        if path == "/ca.crt":
            with open(self.ca_crt) as f:
                der = ssl.PEM_cert_to_DER_cert(f.read())
            return h.send(200, "application/x-x509-ca-cert", der,
                          {"Content-Disposition": 'attachment; filename="Fedora Pad CA.crt"'})
        if path != "/":
            return h.send(404, "text/plain", b"not found")
        host, ips = self.names()
        links = "".join(f'<a class="go" href="https://{n}:{WEB_PORT}/">https://{n}:{WEB_PORT}</a>'
                        for n in [host + ".local"] + ips)
        page = SETUP_PAGE.replace("{LINKS}", links).replace("{HOST}", _html(host))
        return h.send(200, "text/html; charset=utf-8", page.encode())

    def route_app(self, h, method, path, query, headers, body):
        if path == "/ws":
            token = (query.get("token") or [""])[0]
            return self.websocket(h, headers, token)
        if method == "POST" and path == "/pair/request":
            ok = self.request_code()
            return h.json(200 if ok else 429, {"ok": ok})
        if method == "POST" and path == "/pair/confirm":
            try:
                data = json.loads(body or b"{}")
            except ValueError:
                data = {}
            token = self.confirm_code(data.get("code", ""), data.get("name", "device"))
            return h.json(200 if token else 403, {"token": token} if token else {"error": "wrong or old code"})
        if path in FILES and method == "GET":
            name, ctype = FILES[path]
            try:
                with open(os.path.join(WEB_DIR, name), "rb") as f:
                    data = f.read()
            except OSError:
                return h.send(404, "text/plain", b"missing")
            return h.send(200, ctype, data, {"Cache-Control": "no-cache"})
        return h.send(404, "text/plain", b"not found")

    def websocket(self, h, headers, token):
        key = headers.get("sec-websocket-key", "")
        if headers.get("upgrade", "").lower() != "websocket" or not key:
            return h.send(400, "text/plain", b"websocket only")
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        h.wfile.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Accept: {accept}\r\n\r\n").encode())
        h.wfile.flush()
        ws = _WebSocket(h.rfile, h.wfile)
        if not token or not self.known(token):
            ws.close(4401, "pair first")  # the app shows the pairing screen
            return
        h.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        ws.send(self.hello())
        self.on_open(ws)
        print("web app connected", flush=True)
        try:
            while True:
                text = ws.receive()
                if text is None:
                    break
                try:
                    msg = json.loads(text)
                    if isinstance(msg, dict):
                        self.handle(msg, ws.send)
                except (ValueError, TypeError, OSError) as err:
                    print("web app: bad message:", err, file=sys.stderr)
        finally:
            self.on_close(ws)
            print("web app disconnected", flush=True)


class _WebSocket:
    """Just enough of RFC 6455 for text messages from a browser."""

    def __init__(self, rfile, wfile):
        self.rfile, self.wfile = rfile, wfile
        self.lock = threading.Lock()
        self.closed = False

    def _frame(self, opcode, payload):
        n = len(payload)
        head = bytes([0x80 | opcode]) + (bytes([n]) if n < 126 else
                                          struct.pack("!BH", 126, n) if n < 65536 else
                                          struct.pack("!BQ", 127, n))
        with self.lock:
            if self.closed:
                raise OSError("closed")
            self.wfile.write(head + payload)
            self.wfile.flush()

    def send(self, obj):
        try:
            self._frame(0x1, json.dumps(obj).encode())
        except (OSError, ValueError):
            self.closed = True

    # the hosts push focus changes as JSON lines through write()/flush()
    def write(self, data):
        for line in data.decode().splitlines():
            if line.strip():
                self._frame(0x1, line.encode())

    def flush(self):
        pass

    def close(self, code=1000, reason=""):
        try:
            self._frame(0x8, struct.pack("!H", code) + reason.encode()[:100])
        except (OSError, ValueError):
            pass
        self.closed = True

    def _read(self, n):
        data = self.rfile.read(n)
        if len(data) < n:
            raise EOFError
        return data

    def receive(self):
        """The next text message, or None when the connection ends."""
        parts = []
        try:
            while True:
                b1, b2 = self._read(2)
                opcode, masked, n = b1 & 0x0F, b2 & 0x80, b2 & 0x7F
                if n == 126:
                    n = struct.unpack("!H", self._read(2))[0]
                elif n == 127:
                    n = struct.unpack("!Q", self._read(8))[0]
                if not masked or n > MAX_FRAME:  # browsers always mask; nothing huge
                    self.close(1009 if n > MAX_FRAME else 1002)
                    return None
                mask = self._read(4)
                data = bytearray(self._read(n))
                for i in range(n):
                    data[i] ^= mask[i % 4]
                if opcode == 0x8:
                    self.close()
                    return None
                if opcode == 0x9:
                    self._frame(0xA, bytes(data))
                    continue
                if opcode == 0xA:
                    continue
                parts.append(bytes(data))
                if sum(len(p) for p in parts) > MAX_FRAME:
                    self.close(1009)
                    return None
                if b1 & 0x80:
                    return b"".join(parts).decode("utf-8", "replace")
        except (EOFError, OSError, ValueError, ssl.SSLError):
            self.closed = True
            return None


class _Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = os.name != "nt"
    daemon_threads = True


class _Handler(socketserver.StreamRequestHandler):
    """A tiny HTTP/1.1 server: one request per connection."""
    timeout = 30

    def handle(self):
        try:
            line = self.rfile.readline(4096).decode("latin-1").strip()
            parts = line.split()
            if len(parts) != 3:
                return
            method, target = parts[0], parts[1]
            headers = {}
            while True:
                h = self.rfile.readline(8192).decode("latin-1")
                if h in ("\r\n", "\n", ""):
                    break
                k, _, v = h.partition(":")
                headers[k.strip().lower()] = v.strip()
                if len(headers) > 60:
                    return
            body = b""
            n = int(headers.get("content-length", "0") or 0)
            if 0 < n <= 4096:
                body = self.rfile.read(n)
            url = urlsplit(target)
            self.connection.settimeout(None)  # WebSockets stay open
            self.route(method, url.path, parse_qs(url.query), headers, body)
        except (OSError, ValueError, ssl.SSLError):
            pass

    def route(self, method, path, query, headers, body):
        raise NotImplementedError

    def send(self, status, ctype, data, extra=None):
        reason = {200: "OK", 400: "Bad Request", 403: "Forbidden", 404: "Not Found",
                  429: "Too Many Requests"}.get(status, "OK")
        head = [f"HTTP/1.1 {status} {reason}", f"Content-Type: {ctype}", f"Content-Length: {len(data)}",
                "Connection: close", "X-Content-Type-Options: nosniff", "Referrer-Policy: no-referrer",
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; "
                "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' wss:; "
                "frame-ancestors 'none'"]
        for k, v in (extra or {}).items():
            head.append(f"{k}: {v}")
        self.wfile.write(("\r\n".join(head) + "\r\n\r\n").encode() + data)
        self.wfile.flush()

    def json(self, status, obj):
        self.send(status, "application/json", json.dumps(obj).encode(), {"Cache-Control": "no-store"})


def _read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def _private(path):
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def _html(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


SETUP_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Set up Fedora Pad</title>
<style>
body{font:17px -apple-system,system-ui,sans-serif;max-width:620px;margin:0 auto;padding:24px;
 background:#f2f2f7;color:#1d1d1f}
@media(prefers-color-scheme:dark){body{background:#000;color:#f5f5f7}.card{background:#1c1c1e!important}}
h1{font-size:30px;margin:8px 0 18px}.card{background:#fff;border-radius:16px;padding:18px 20px;margin:14px 0}
b.n{display:inline-block;width:28px;height:28px;border-radius:14px;background:#007aff;color:#fff;
 text-align:center;line-height:28px;margin-right:8px}
a.btn,a.go{display:block;text-align:center;background:#007aff;color:#fff;text-decoration:none;
 padding:14px;border-radius:12px;margin-top:12px;font-weight:600}a.go{background:#34c759}
small{color:#8e8e93}
</style></head><body>
<h1>📱 Fedora Pad on this device</h1>
<div class="card"><b class="n">1</b><b>Install the certificate</b>
<p>So this iPad can talk to <b>{HOST}</b> securely. Tap the button, then <b>Allow</b>.</p>
<a class="btn" href="/ca.crt">Download the certificate</a>
<p><small>Then open <b>Settings</b> › <b>Profile Downloaded</b> › <b>Install</b> (asks for your iPad passcode).</small></p></div>
<div class="card"><b class="n">2</b><b>Trust it</b>
<p>Settings › General › About › <b>Certificate Trust Settings</b> › turn on <b>Fedora Pad local CA</b>.</p></div>
<div class="card"><b class="n">3</b><b>Open Fedora Pad</b>
<p>Tap one of these (the first works even when the address changes):</p>{LINKS}
<p><small>Then in Safari: <b>Share</b> › <b>Add to Home Screen</b>. Open it from the Home Screen and pair with the
code that appears on the PC.</small></p></div>
<p><small>Android or another computer: download the certificate and add it as a trusted CA, or just open the
link and accept the warning.</small></p>
</body></html>"""
