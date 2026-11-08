"""The in-memory live view served to the web app: token required, frame/status/reset work, nothing on disk."""

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import agent  # noqa: E402
from live_view import LiveView  # noqa: E402


@pytest.fixture
def view():
    v = LiveView(agent.free_port(), "secret-token")
    v.start()
    yield v
    v.stop()


def get(view, path, token="secret-token", method="GET"):
    req = urllib.request.Request(f"http://127.0.0.1:{view.port}{path}", method=method,
                                 headers={"X-Live-Token": token}, data=b"" if method == "POST" else None)
    with urllib.request.urlopen(req, timeout=3) as r:
        return r.status, r.headers.get("Content-Type"), r.read()


def test_token_is_required(view):
    with pytest.raises(urllib.error.HTTPError) as err:
        get(view, "/status", token="wrong")
    assert err.value.code == 401


def test_frame_status_and_reset(view, tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    with pytest.raises(urllib.error.HTTPError) as err:
        get(view, "/frame.jpg")                       # nothing published yet
    assert err.value.code == 404

    view.publish_frame(np.zeros((480, 1280, 3), dtype=np.uint8))
    view.publish_status(visible=3, sessionEntries=2, sessionExits=1, health="OK")
    status, ctype, body = get(view, "/frame.jpg")
    assert status == 200 and ctype == "image/jpeg" and body[:2] == b"\xff\xd8"
    status, _, body = get(view, "/status")
    data = json.loads(body)
    assert data["visible"] == 3 and data["sessionEntries"] == 2 and "updatedAt" in data

    assert view.take_reset() is False
    get(view, "/reset", method="POST")
    assert view.take_reset() is True and view.take_reset() is False
    assert list(tmp_path.iterdir()) == []             # no image was written anywhere


def test_stop_drops_the_last_frame(view):
    view.publish_frame(np.zeros((10, 10, 3), dtype=np.uint8))
    view.stop()
    assert view.jpeg is None
    view.server = None                                # fixture teardown: already stopped
