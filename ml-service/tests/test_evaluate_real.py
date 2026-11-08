"""evaluate_real.py runs end to end. The CSV here is a tiny made-up fixture for the test only, not data."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import evaluate_real  # noqa: E402


def test_scores_a_labelled_csv_and_skips_invalid_rows(tmp_path):
    csv = tmp_path / "obs.csv"
    csv.write_text(
        "hour,day_of_week,route_distance_km,stop_count,bus_capacity,crowd_level,onboard\n"
        "8,0,6.0,5,50,HIGH,\n"
        "3,6,6.0,5,50,,2\n"            # label from onboard: 2/50 -> LOW
        "13,2,9.0,6,40,MEDIUM,\n"
        "25,1,6.0,5,50,LOW,\n")        # invalid hour -> skipped
    result = evaluate_real.main([str(csv), "--report", str(tmp_path / "r.json")])
    assert result["rows"] == 3 and result["rows_skipped"] == 1
    assert 0 <= result["accuracy"] <= 1 and (tmp_path / "r.json").exists()
    assert result["confusion_matrix"]["labels"] == ["LOW", "MEDIUM", "HIGH"]


def test_onboard_uses_backend_thresholds():
    assert evaluate_real.label_from_onboard(24, 50) == "LOW"
    assert evaluate_real.label_from_onboard(25, 50) == "MEDIUM"
    assert evaluate_real.label_from_onboard(43, 50) == "HIGH"


def test_rejects_csv_without_observed_values(tmp_path):
    csv = tmp_path / "bad.csv"
    csv.write_text("hour,day_of_week,route_distance_km,stop_count,bus_capacity\n8,0,6,5,50\n")
    with pytest.raises(SystemExit):
        evaluate_real.main([str(csv)])
