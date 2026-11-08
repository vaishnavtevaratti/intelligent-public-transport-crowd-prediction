"""Tests of the counting logic (no detection model needed). Run from vision-service/: python -m pytest -q"""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from counting import CountingLine, LineCrossingCounter, VisibleCounter, frame_quality  # noqa: E402

W, H = 1000, 500


def counter():
    return LineCrossingCounter(CountingLine("vertical", 0.5, "positive", dead_band=0.03))


def walk(c, track_id, xs, start_frame=0):
    for i, x in enumerate(xs):
        c.update(start_frame + i, [(track_id, x, 400)], W, H)


def test_one_crossing_counts_once_even_if_person_stays_in_view():
    c = counter()
    walk(c, 1, [100, 300, 450, 560, 700] + [800] * 50)   # crosses, then stands still for 50 frames
    assert c.take_counts() == (1, 0)


def test_opposite_direction_is_an_exit():
    c = counter()
    walk(c, 7, [900, 700, 540, 460, 200])
    assert c.take_counts() == (0, 1)


def test_jitter_on_the_line_is_not_counted():
    c = counter()
    walk(c, 3, [300, 490, 505, 495, 510, 492, 508, 300])   # wobbles inside the dead band, returns
    assert c.take_counts() == (0, 0)


def test_two_people_counted_separately_and_back_and_forth_is_entry_then_exit():
    c = counter()
    for f, (x1, x2) in enumerate(zip([100, 400, 600, 800], [900, 600, 400, 100])):
        c.update(f, [(1, x1, 400), (2, x2, 400)], W, H)
    assert c.take_counts() == (1, 1)
    walk(c, 5, [200, 700, 200], start_frame=10)   # in, then back out
    assert c.take_counts() == (1, 1)


def test_take_counts_resets_window():
    c = counter()
    walk(c, 1, [100, 900])
    assert c.take_counts() == (1, 0)
    assert c.take_counts() == (0, 0)


def test_horizontal_line_negative_entry_direction():
    c = LineCrossingCounter(CountingLine("horizontal", 0.5, "negative"))
    for f, y in enumerate([450, 300, 100]):   # moving up = towards smaller y = entry
        c.update(f, [(1, 500, y)], W, H)
    assert c.take_counts() == (1, 0)


def test_visible_counter_median_smooths_flicker():
    v = VisibleCounter(window=5)
    assert v.value() is None
    for n in [4, 4, 9, 4, 3]:
        v.update(n)
    assert v.value() == 4


def test_frame_quality_flags_dark_frames():
    dark = np.zeros((100, 100, 3), dtype=np.uint8)
    rng = np.random.default_rng(0)
    textured = rng.integers(0, 255, (100, 100, 3), dtype=np.uint8)
    assert frame_quality(dark) < 0.35
    assert frame_quality(textured) > frame_quality(dark)


def test_geometry_warning_flags_people_too_close_to_the_camera():
    from counting import camera_geometry_warning
    close = [(0.64, 0.6, 1.0)] * 20          # measured on the laptop webcam: wide boxes, feet cut off
    far = [(0.06, 0.15, 0.55)] * 20          # test video: whole bodies seen from above
    assert "too close" in camera_geometry_warning(close)
    assert camera_geometry_warning(far) is None
    assert camera_geometry_warning(close[:3]) is None   # too few detections to judge
