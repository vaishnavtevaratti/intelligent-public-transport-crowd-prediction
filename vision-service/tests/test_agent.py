"""Validation rules of the counter agent. No process or camera is started: Counter.start is replaced."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import agent  # noqa: E402


@pytest.fixture
def ag(monkeypatch, tmp_path):
    keys = tmp_path / "sensor_keys.json"
    keys.write_text('{"CAM-A": "k1", "CAM-B": "k2"}')
    videos = tmp_path / "test_videos"
    videos.mkdir()
    (videos / "clip.avi").write_bytes(b"x")
    monkeypatch.setattr(agent, "KEYS_FILE", keys)
    monkeypatch.setattr(agent, "VIDEOS", videos)
    started = []

    def fake_start(self, consent):
        started.append((self.sensor_code, self.source, consent))
        self.process = type("P", (), {"poll": lambda s: None, "returncode": None})()

    monkeypatch.setattr(agent.Counter, "start", fake_start)
    a = agent.Agent()
    a.started = started
    return a


def test_camera_requires_consent(ag):
    with pytest.raises(ValueError, match="confirmConsent"):
        ag.start({"sensorCode": "CAM-A", "source": "0", "mode": "cabin"})
    assert ag.started == []


def test_only_known_sensors_and_test_videos(ag):
    with pytest.raises(ValueError, match="sensor_keys"):
        ag.start({"sensorCode": "NOPE", "source": "test_videos/clip.avi", "mode": "door"})
    with pytest.raises(ValueError, match="test videos"):
        ag.start({"sensorCode": "CAM-A", "source": "C:/Windows/system.ini", "mode": "door"})
    with pytest.raises(ValueError, match="mode"):
        ag.start({"sensorCode": "CAM-A", "source": "test_videos/clip.avi", "mode": "x"})


def test_no_duplicate_counter_or_shared_camera(ag):
    ag.start({"sensorCode": "CAM-A", "source": "0", "mode": "cabin", "confirmConsent": True})
    with pytest.raises(RuntimeError, match="already running"):
        ag.start({"sensorCode": "cam-a", "source": "test_videos/clip.avi", "mode": "cabin"})
    with pytest.raises(RuntimeError, match="camera 0"):
        ag.start({"sensorCode": "CAM-B", "source": "0", "mode": "door", "confirmConsent": True})
    assert ag.started == [("CAM-A", "0", True)]


def test_listing_shows_every_sensor_and_video(ag):
    listing = ag.list()
    assert [c["sensorCode"] for c in listing["counters"]] == ["CAM-A", "CAM-B"]
    assert listing["testVideos"] == ["test_videos/clip.avi"]
    assert all(c["state"] == "STOPPED" for c in listing["counters"])


def test_start_command_passes_live_port_token_and_consent(monkeypatch, tmp_path):
    captured = {}

    class FakePopen:
        def __init__(self, cmd, **kwargs):
            captured["cmd"], captured["env"] = cmd, kwargs["env"]

        def poll(self):
            return None

    monkeypatch.setattr(agent, "OUTPUT", tmp_path)
    monkeypatch.setattr(agent.subprocess, "Popen", FakePopen)
    camera = agent.Counter("CAM-A", "0", "cabin", False)
    camera.start(True)
    cmd = captured["cmd"]
    assert "--confirm-consent" in cmd and "--no-preview" in cmd
    port = int(cmd[cmd.index("--live-port") + 1])
    assert port == camera.live_port and port > 0
    assert captured["env"]["LIVE_TOKEN"] == camera.live_token and len(camera.live_token) >= 16

    video = agent.Counter("CAM-B", "test_videos/clip.avi", "door", True)
    video.start(False)
    assert "--confirm-consent" not in captured["cmd"] and "--loop" in captured["cmd"]
    assert video.live_token != camera.live_token          # a separate token per run


def test_live_requests_need_a_running_counter():
    c = agent.Counter("CAM-A", "test_videos/clip.avi", "door", True)
    with pytest.raises(RuntimeError, match="no counter is running"):
        c.live_request("/status")
