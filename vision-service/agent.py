"""Local counter agent: lets the web app (through Spring Boot) START and STOP passenger counters.

Runs on the computer that has the camera (e.g. the laptop, or a bus PC). It manages run_counter.py
processes and never handles images itself.

  python agent.py            -> http://127.0.0.1:8002   (only reachable from this computer)

Security
  * listens on 127.0.0.1 only; every request needs the header X-Agent-Token
  * the token is generated on first start and stored in agent_token.txt (git-ignored); the backend reads
    the same file (app.vision-agent.token-file) or the VISION_AGENT_TOKEN environment variable
  * sources are limited to a camera index or a video inside test_videos/ (no arbitrary paths)
  * a camera is only opened when the request confirms consent (confirmConsent = true)

API (JSON; every call except /health needs X-Agent-Token)
  GET  /health                        -> {"status": "UP", "running": n}
  GET  /counters                      -> status of every sensor in sensor_keys.json + available test videos
  POST /counters/start  {sensorCode, source, mode, confirmConsent, loop}
  POST /counters/stop   {sensorCode}  -> graceful stop (stop file), forced after 15 s
  GET  /counters/<code>/live          -> live numbers of a running counter (visible now, session in/out, fps, health)
  GET  /counters/<code>/frame.jpg     -> latest annotated frame (kept in memory only, never stored)
  POST /counters/<code>/reset         -> session in/out totals back to zero
"""

from __future__ import annotations

import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import error as urlerror
from urllib import request as urlrequest

BASE = Path(__file__).parent
OUTPUT = BASE / "output"
VIDEOS = BASE / "test_videos"
KEYS_FILE = BASE / "sensor_keys.json"
TOKEN_FILE = BASE / "agent_token.txt"
HOST = "127.0.0.1"
PORT = int(os.environ.get("VISION_AGENT_PORT", "8002"))
STOP_TIMEOUT = 15
VIDEO_EXTENSIONS = {".avi", ".mp4", ".mov", ".mkv"}


def load_token() -> str:
    token = os.environ.get("VISION_AGENT_TOKEN")
    if token:
        return token
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(32))
    return TOKEN_FILE.read_text().strip()


class Counter:
    def __init__(self, sensor_code, source, mode, loop):
        self.sensor_code = sensor_code
        self.source = source
        self.mode = mode
        self.loop = loop
        self.started_at = datetime.now().replace(microsecond=0).isoformat()
        self.stop_file = OUTPUT / f"stop_{sensor_code}"
        self.log_path = OUTPUT / f"agent_{sensor_code}.log"
        self.process = None
        self.stop_requested_at = None
        self.stopped_at = None
        self.live_port = None
        self.live_token = secrets.token_urlsafe(16)

    def start(self, confirm_consent):
        OUTPUT.mkdir(exist_ok=True)
        cmd = [sys.executable, "-u", str(BASE / "run_counter.py"), "--source", self.source, "--mode", self.mode,
               "--sensor-code", self.sensor_code, "--stop-file", str(self.stop_file)]
        if self.loop:
            cmd.append("--loop")
        if self.source.isdigit():
            cmd.append("--confirm-consent")  # consent confirmed in the web app
        self.live_port = free_port()
        cmd += ["--no-preview", "--live-port", str(self.live_port)]   # the live view is shown in the web app
        log = open(self.log_path, "w", encoding="utf8")
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        env = {**os.environ, "LIVE_TOKEN": self.live_token}
        self.process = subprocess.Popen(cmd, cwd=BASE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags, env=env)

    def live_request(self, path, method="GET"):
        """Calls the counter's own live endpoint; returns (status, content type, body)."""
        if not self.running() or not self.live_port:
            raise RuntimeError(f"no counter is running for {self.sensor_code}")
        req = urlrequest.Request(f"http://127.0.0.1:{self.live_port}{path}", method=method,
                                 headers={"X-Live-Token": self.live_token}, data=b"" if method == "POST" else None)
        try:
            with urlrequest.urlopen(req, timeout=3) as r:
                return r.status, r.headers.get("Content-Type", "application/octet-stream"), r.read()
        except urlerror.HTTPError as e:
            if e.code == 404 and path == "/frame.jpg":
                return 204, "image/jpeg", b""          # no frame processed yet
            return e.code, "application/json", e.read() or b'{"error":"counter live view error"}'
        except (urlerror.URLError, OSError):
            # the counter is still loading the detector (its live server is not up yet): a normal state, not an error
            if path == "/frame.jpg":
                return 204, "image/jpeg", b""
            if path == "/status":
                return 200, "application/json", b'{"health":"STARTING","message":"loading the detector"}'
            return 409, "application/json", b'{"error":"the counter is still starting"}'

    def running(self):
        return self.process is not None and self.process.poll() is None

    def request_stop(self):
        if not self.running():
            return
        self.stop_requested_at = time.time()
        self.stop_file.write_text("stop")
        threading.Thread(target=self._force_after_timeout, daemon=True).start()

    def _force_after_timeout(self):
        try:
            self.process.wait(timeout=STOP_TIMEOUT)
        except subprocess.TimeoutExpired:
            self.process.kill()  # last resort; the camera is released by the OS
            self.process.wait()
        self.stopped_at = datetime.now().replace(microsecond=0).isoformat()

    def last_log_line(self):
        try:
            lines = [l for l in self.log_path.read_text(encoding="utf8", errors="replace").splitlines() if l.strip()]
            return lines[-1][-220:] if lines else None
        except OSError:
            return None

    def status(self):
        state = "RUNNING" if self.running() else "STOPPED"
        if self.running() and self.stop_requested_at:
            state = "STOPPING"
        return {"sensorCode": self.sensor_code, "state": state, "source": self.source, "mode": self.mode,
                "loop": self.loop, "startedAt": self.started_at, "stoppedAt": self.stopped_at,
                "exitCode": None if self.running() else self.process.returncode, "lastLog": self.last_log_line()}


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Agent:
    def __init__(self):
        self.counters: dict[str, Counter] = {}
        self.lock = threading.Lock()

    def sensors(self):
        if not KEYS_FILE.exists():
            return []
        return sorted(json.loads(KEYS_FILE.read_text()).keys())

    def videos(self):
        if not VIDEOS.exists():
            return []
        return sorted(f"test_videos/{p.name}" for p in VIDEOS.iterdir() if p.suffix.lower() in VIDEO_EXTENSIONS)

    def list(self):
        result = []
        for code in self.sensors():
            c = self.counters.get(code)
            result.append(c.status() if c else {"sensorCode": code, "state": "STOPPED", "source": None, "mode": None,
                                                 "loop": False, "startedAt": None, "stoppedAt": None,
                                                 "exitCode": None, "lastLog": None})
        return {"counters": result, "testVideos": self.videos(), "agentTime": datetime.now().replace(microsecond=0).isoformat()}

    def start(self, body):
        code = str(body.get("sensorCode", "")).strip().upper()
        source = str(body.get("source", "")).strip()
        mode = body.get("mode")
        if code not in self.sensors():
            raise ValueError(f"sensor {code or '?'} has no key in sensor_keys.json on this computer")
        if mode not in ("door", "cabin"):
            raise ValueError("mode must be 'door' or 'cabin'")
        if source.isdigit():
            if int(source) > 9:
                raise ValueError("camera index must be 0-9")
            if body.get("confirmConsent") is not True:
                raise ValueError("starting a camera requires confirmConsent = true (everyone in view has agreed)")
        elif source not in self.videos():
            raise ValueError("source must be a camera index or one of the test videos: " + ", ".join(self.videos()))
        with self.lock:
            current = self.counters.get(code)
            if current and current.running():
                raise RuntimeError(f"a counter for {code} is already running")
            if source.isdigit():
                busy = [c.sensor_code for c in self.counters.values() if c.running() and c.source == source]
                if busy:
                    raise RuntimeError(f"camera {source} is already used by the counter for {busy[0]}")
            counter = Counter(code, source, mode, bool(body.get("loop", not source.isdigit())))
            counter.start(body.get("confirmConsent") is True)
            self.counters[code] = counter
            return counter.status()

    def stop(self, body):
        code = str(body.get("sensorCode", "")).strip().upper()
        counter = self.counters.get(code)
        if not counter or not counter.running():
            raise RuntimeError(f"no counter is running for {code or '?'}")
        counter.request_stop()
        return counter.status()

    def counter(self, code):
        counter = self.counters.get(code.upper())
        if not counter or not counter.running():
            raise RuntimeError(f"no counter is running for {code}")
        return counter

    def running_count(self):
        return sum(1 for c in self.counters.values() if c.running())

    def stop_all(self):
        for c in self.counters.values():
            c.request_stop()
        for c in self.counters.values():
            if c.process:
                try:
                    c.process.wait(timeout=STOP_TIMEOUT)
                except subprocess.TimeoutExpired:
                    c.process.kill()


AGENT = Agent()
TOKEN = None


class Handler(BaseHTTPRequestHandler):
    server_version = "CounterAgent/1.0"

    def _send(self, status, payload, content_type="application/json"):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        if not secrets.compare_digest(self.headers.get("X-Agent-Token", ""), TOKEN):
            self._send(401, {"error": "missing or invalid X-Agent-Token"})
            return False
        return True

    def _read_body(self, limit=65536):
        """Request body, with or without Content-Length (Java HTTP clients may send it chunked)."""
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()  # blank line after the last chunk
                    return data
                data += self.rfile.read(size)
                self.rfile.readline()      # CRLF after each chunk
                if len(data) > limit:
                    raise ValueError("request body too large")
        length = int(self.headers.get("Content-Length", "0"))
        if length > limit:
            raise ValueError("request body too large")
        return self.rfile.read(length)

    def _live_path(self):
        """('CODE', 'live'|'frame.jpg'|'reset') for /counters/<code>/<action>, else None."""
        parts = self.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "counters" and parts[2] in ("live", "frame.jpg", "reset"):
            return parts[1], parts[2]
        return None

    def do_GET(self):
        if self.path == "/health":   # no token: says only that the agent runs
            return self._send(200, {"status": "UP", "running": AGENT.running_count()})
        if not self._authorized():
            return
        if self.path == "/counters":
            return self._send(200, AGENT.list())
        live = self._live_path()
        if live and live[1] in ("live", "frame.jpg"):
            try:
                status, ctype, body = AGENT.counter(live[0]).live_request("/status" if live[1] == "live" else "/frame.jpg")
            except RuntimeError as exc:
                return self._send(409, {"error": str(exc)})
            return self._send(status, body, ctype)
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self._authorized():
            return
        try:
            body = json.loads(self._read_body() or b"{}")
            if not isinstance(body, dict):
                raise ValueError("JSON object expected")
        except (ValueError, json.JSONDecodeError):
            return self._send(400, {"error": "request body must be a JSON object"})
        live = self._live_path()
        if live and live[1] == "reset":
            try:
                status, ctype, body = AGENT.counter(live[0]).live_request("/reset", "POST")
            except RuntimeError as exc:
                return self._send(409, {"error": str(exc)})
            return self._send(status, body, ctype)
        try:
            if self.path == "/counters/start":
                return self._send(200, AGENT.start(body))
            if self.path == "/counters/stop":
                return self._send(200, AGENT.stop(body))
            return self._send(404, {"error": "not found"})
        except ValueError as exc:
            return self._send(400, {"error": str(exc)})
        except RuntimeError as exc:
            return self._send(409, {"error": str(exc)})

    def log_message(self, fmt, *args):  # keep the console readable
        sys.stderr.write(f"{datetime.now():%H:%M:%S} {self.command} {self.path} -> {args[1] if len(args) > 1 else ''}\n")


def main():
    global TOKEN
    TOKEN = load_token()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Counter agent on http://{HOST}:{PORT} (token in {TOKEN_FILE.name}); Ctrl+C stops it and all counters")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        print("Stopping all counters…")
        AGENT.stop_all()
        server.server_close()


if __name__ == "__main__":
    main()
