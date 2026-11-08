"""In-memory live view of a running counter, for the web app (through agent.py and the backend).

Serves on 127.0.0.1 only, and every request needs the per-run token (X-Live-Token) that agent.py generates:
  GET  /status     -> JSON: people visible now, crossings counted this session, frames, fps, health
  GET  /frame.jpg  -> the latest annotated frame (boxes, counting line, numbers) as JPEG
  POST /reset      -> sets the session crossing totals back to zero
Frames are kept in memory only (the latest one replaces the previous one); nothing is written to disk.
"""

from __future__ import annotations

import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2

MAX_WIDTH = 640
MIN_FRAME_INTERVAL = 0.2   # encode at most ~5 frames per second


class LiveView:
    def __init__(self, port: int, token: str):
        self.port = port
        self.token = token
        self.lock = threading.Lock()
        self.jpeg: bytes | None = None
        self.status: dict = {}
        self.reset_requested = False
        self.last_encode = 0.0
        self.server: ThreadingHTTPServer | None = None

    # ---- called by the counter loop
    def publish_frame(self, frame) -> None:
        now = time.time()
        if now - self.last_encode < MIN_FRAME_INTERVAL:
            return
        self.last_encode = now
        h, w = frame.shape[:2]
        if w > MAX_WIDTH:
            frame = cv2.resize(frame, (MAX_WIDTH, int(h * MAX_WIDTH / w)))
        ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
        if ok:
            with self.lock:
                self.jpeg = buf.tobytes()

    def publish_status(self, **status) -> None:
        with self.lock:
            self.status = {**self.status, **status, "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S")}

    def take_reset(self) -> bool:
        with self.lock:
            requested, self.reset_requested = self.reset_requested, False
            return requested

    # ---- HTTP server
    def start(self) -> None:
        view = self

        class Handler(BaseHTTPRequestHandler):
            def _authorized(self):
                if secrets.compare_digest(self.headers.get("X-Live-Token", ""), view.token):
                    return True
                self._send(401, b'{"error":"invalid live token"}', "application/json")
                return False

            def _send(self, code, body, content_type):
                self.send_response(code)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if not self._authorized():
                    return
                if self.path == "/status":
                    with view.lock:
                        body = json.dumps(view.status).encode()
                    return self._send(200, body, "application/json")
                if self.path == "/frame.jpg":
                    with view.lock:
                        jpeg = view.jpeg
                    if jpeg is None:
                        return self._send(404, b'{"error":"no frame yet"}', "application/json")
                    return self._send(200, jpeg, "image/jpeg")
                self._send(404, b'{"error":"not found"}', "application/json")

            def do_POST(self):
                if not self._authorized():
                    return
                if self.path == "/reset":
                    with view.lock:
                        view.reset_requested = True
                    return self._send(200, b'{"reset":true}', "application/json")
                self._send(404, b'{"error":"not found"}', "application/json")

            def log_message(self, fmt, *args):  # keep the counter log readable
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self) -> None:
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        with self.lock:
            self.jpeg = None   # drop the last frame from memory
