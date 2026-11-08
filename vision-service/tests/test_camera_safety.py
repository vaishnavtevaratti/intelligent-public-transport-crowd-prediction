"""The camera must never be opened without explicit consent, and webcam data must never be labelled as a bus camera.
These tests do NOT open any camera: cv2.VideoCapture is replaced by a function that fails the test if called."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_counter  # noqa: E402


def forbid_camera(*args, **kwargs):
    raise AssertionError(f"camera was opened: VideoCapture{args}")


def test_camera_is_not_opened_without_consent(monkeypatch):
    monkeypatch.setattr(run_counter.cv2, "VideoCapture", forbid_camera)
    monkeypatch.setattr(sys, "argv", ["run_counter.py", "--source", "0", "--mode", "cabin",
                                      "--sensor-code", "CAM-X", "--sensor-key", "k"])
    with pytest.raises(SystemExit) as stop:
        run_counter.main()
    assert "--confirm-consent" in str(stop.value)
    assert "nothing is recorded" in str(stop.value)


def test_unavailable_camera_gives_clear_message(monkeypatch):
    class Closed:
        def isOpened(self):
            return False

    monkeypatch.setattr(run_counter.cv2, "VideoCapture", lambda *a, **k: Closed())
    cap, error = run_counter.open_source("3")
    assert cap is None
    assert "camera index 3" in error and "Privacy" in error


def test_open_file_error_message(monkeypatch):
    class Closed:
        def isOpened(self):
            return False

    monkeypatch.setattr(run_counter.cv2, "VideoCapture", lambda *a, **k: Closed())
    cap, error = run_counter.open_source("missing.avi")
    assert cap is None and "missing.avi" in error


def test_black_picture_is_reported_as_unavailable_not_as_zero_people():
    health, message = run_counter.health_for_quality(0.0)
    assert health == "CAMERA_UNAVAILABLE" and "no count" in message
    assert run_counter.health_for_quality(0.2)[0] == "DEGRADED"
    assert run_counter.health_for_quality(0.6) == ("OK", None)
