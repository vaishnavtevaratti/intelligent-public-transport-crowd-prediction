"""Tests for the crowd prediction ML service. Run from ml-service/:  python -m pytest -q"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import MODEL_PATH, create_app  # noqa: E402

VALID = {"hour": 8, "day_of_week": 1, "route_distance_km": 20.0, "stop_count": 12, "bus_capacity": 40}


@pytest.fixture(scope="module")
def client():
    if not MODEL_PATH.exists():
        pytest.skip("model not trained; run train.py")
    return create_app().test_client()


def test_health_reports_model(client):
    res = client.get("/health")
    assert res.status_code == 200
    assert res.get_json()["status"] == "UP"


def test_model_info_has_metrics_and_synthetic_label(client):
    info = client.get("/model/info").get_json()
    assert info["classes"] == ["LOW", "MEDIUM", "HIGH"]
    assert "SYNTHETIC" in info["dataset"]["source"]
    assert 0 <= info["test"]["macro_f1"] <= 1


def test_predict_returns_level_and_probabilities(client):
    res = client.post("/predict", json={"instances": [VALID]})
    assert res.status_code == 200
    pred = res.get_json()["predictions"][0]
    assert pred["crowd_level"] in ("LOW", "MEDIUM", "HIGH")
    assert abs(sum(pred["probabilities"].values()) - 1) < 0.01
    assert pred["confidence"] == pred["probabilities"][pred["crowd_level"]]


def test_peak_is_more_crowded_than_night(client):
    """The model should have learned the commuter peak vs. night pattern."""
    peak = dict(VALID, hour=8)
    night = dict(VALID, hour=2)
    preds = client.post("/predict", json={"instances": [peak, night]}).get_json()["predictions"]
    assert preds[1]["crowd_level"] == "LOW"
    assert preds[0]["probabilities"]["HIGH"] > preds[1]["probabilities"]["HIGH"]


def test_missing_optional_route_feature_is_imputed(client):
    res = client.post("/predict", json={"instances": [dict(VALID, stop_count=None)]})
    assert res.status_code == 200


@pytest.mark.parametrize("bad, message", [
    ({**VALID, "hour": 24}, "hour"),
    ({**VALID, "day_of_week": -1}, "day_of_week"),
    ({**VALID, "bus_capacity": "fifty"}, "bus_capacity"),
    ({k: v for k, v in VALID.items() if k != "hour"}, "hour"),
    ({**VALID, "hour": 7.5}, "whole number"),
])
def test_invalid_input_rejected(client, bad, message):
    res = client.post("/predict", json={"instances": [bad]})
    assert res.status_code == 400
    assert message in res.get_json()["error"]


def test_malformed_body_rejected(client):
    assert client.post("/predict", data="not json", content_type="text/plain").status_code == 400
    assert client.post("/predict", json={"instances": []}).status_code == 400


def test_missing_model_reports_down(tmp_path):
    app = create_app(tmp_path / "missing.joblib").test_client()
    assert app.get("/health").status_code == 503
    assert app.post("/predict", json={"instances": [VALID]}).status_code == 503


FORECAST_VALID = {"minute_of_day": 510, "day_of_week": 1, "route_distance_km": 14.0, "stop_count": 8,
                  "bus_capacity": 50, "current_passengers": 30, "passengers_15min_ago": 22}


def test_forecast_returns_four_levels_and_count(client):
    res = client.post("/forecast", json={"instances": [FORECAST_VALID]})
    assert res.status_code == 200
    pred = res.get_json()["predictions"][0]
    assert set(pred["probabilities"]) == {"LOW", "MEDIUM", "HIGH", "FULL"}
    assert pred["horizon_minutes"] == 15
    assert pred["predicted_passengers"] >= 0


def test_forecast_follows_live_occupancy(client):
    """A bus that is already full should be forecast more crowded than an almost empty one."""
    full = dict(FORECAST_VALID, current_passengers=55, passengers_15min_ago=50)
    empty = dict(FORECAST_VALID, current_passengers=3, passengers_15min_ago=4)
    preds = client.post("/forecast", json={"instances": [full, empty]}).get_json()["predictions"]
    assert preds[0]["predicted_passengers"] > preds[1]["predicted_passengers"] + 20
    assert preds[1]["crowd_level"] == "LOW"


def test_forecast_accepts_missing_history_and_rejects_bad_input(client):
    assert client.post("/forecast", json={"instances": [dict(FORECAST_VALID, passengers_15min_ago=None)]}).status_code == 200
    bad = client.post("/forecast", json={"instances": [dict(FORECAST_VALID, current_passengers=-1)]})
    assert bad.status_code == 400 and "current_passengers" in bad.get_json()["error"]
    assert client.post("/forecast", json={"instances": [{k: v for k, v in FORECAST_VALID.items() if k != "bus_capacity"}]}).status_code == 400


def test_forecast_info_reports_baseline(client):
    info = client.get("/forecast/info").get_json()
    assert "persistence_regression" in info["baseline_test"]
    assert "SYNTHETIC" in info["dataset"]["source"]


def test_inputs_outside_training_range_are_flagged(client):
    """A 4-passenger demo bus is far outside the training data (capacities 30-70 / 40-70): flag, do not hide."""
    small = dict(VALID, bus_capacity=4)
    pred = client.post("/predict", json={"instances": [small, VALID]}).get_json()["predictions"]
    assert pred[0]["out_of_training_range"] == ["bus_capacity"]
    assert pred[1]["out_of_training_range"] == []
    fc = client.post("/forecast", json={"instances": [dict(FORECAST_VALID, bus_capacity=4, current_passengers=1, passengers_15min_ago=1)]})
    assert fc.get_json()["predictions"][0]["out_of_training_range"] == ["bus_capacity"]
