"""Features for the SHORT-HORIZON occupancy forecast model (train_forecast.py / app.py /forecast).

This model answers: "given what a bus looks like NOW (live passenger estimate), how crowded will it be
in HORIZON_MINUTES?". It needs a live occupancy estimate, so the backend only uses it for buses whose
occupancy data is fresh; otherwise it falls back to the time-and-route model (features.py).
"""

import math

import pandas as pd

HORIZON_MINUTES = 15

# Crowd categories by occupancy ratio = passengers / total passenger capacity (seats + standing).
# Must match app.crowd.thresholds.* in the Spring Boot application.properties.
THRESHOLDS = {"MEDIUM": 0.50, "HIGH": 0.85, "FULL": 1.00}
CLASSES = ["LOW", "MEDIUM", "HIGH", "FULL"]

INPUT_FIELDS = ["minute_of_day", "day_of_week", "route_distance_km", "stop_count", "bus_capacity",
                "current_passengers", "passengers_15min_ago"]
OPTIONAL_FIELDS = {"route_distance_km", "stop_count", "passengers_15min_ago"}
LIMITS = {
    "minute_of_day": (0, 1439),
    "day_of_week": (0, 6),
    "route_distance_km": (0.1, 500.0),
    "stop_count": (2, 200),
    "bus_capacity": (1, 300),
    "current_passengers": (0, 600),
    "passengers_15min_ago": (0, 600),
}
# Ranges the forecast model was trained on (generate_occupancy_series.py).
TRAINING_RANGES = {"route_distance_km": (4.0, 28.0), "stop_count": (3, 25), "bus_capacity": (40, 70)}
INTEGER_FIELDS = {"minute_of_day", "day_of_week", "stop_count", "bus_capacity", "current_passengers", "passengers_15min_ago"}

MODEL_FEATURES = [
    "hour_sin", "hour_cos", "minute_of_day", "day_of_week", "is_weekend",
    "route_distance_km", "stop_count", "bus_capacity",
    "current_passengers", "current_occupancy", "change_15min", "change_ratio_15min",
]


def crowd_level(occupancy: float) -> str:
    if occupancy >= THRESHOLDS["FULL"]:
        return "FULL"
    if occupancy >= THRESHOLDS["HIGH"]:
        return "HIGH"
    if occupancy >= THRESHOLDS["MEDIUM"]:
        return "MEDIUM"
    return "LOW"


def add_derived_features(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    hours = out["minute_of_day"] / 60.0
    out["hour_sin"] = hours.apply(lambda h: math.sin(2 * math.pi * h / 24))
    out["hour_cos"] = hours.apply(lambda h: math.cos(2 * math.pi * h / 24))
    out["is_weekend"] = (out["day_of_week"] >= 5).astype(int)
    out["current_occupancy"] = out["current_passengers"] / out["bus_capacity"]
    out["change_15min"] = out["current_passengers"] - out["passengers_15min_ago"]  # NaN when history missing
    out["change_ratio_15min"] = out["change_15min"] / out["bus_capacity"]
    return out[MODEL_FEATURES]


def validate_instance(raw: dict) -> dict:
    if not isinstance(raw, dict):
        raise ValueError("each instance must be a JSON object")
    clean = {}
    for field in INPUT_FIELDS:
        value = raw.get(field)
        if value is None:
            if field in OPTIONAL_FIELDS:
                clean[field] = float("nan")
                continue
            raise ValueError(f"'{field}' is required")
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"'{field}' must be a number")
        low, high = LIMITS[field]
        if not (low <= value <= high):
            raise ValueError(f"'{field}' must be between {low} and {high}")
        if field in INTEGER_FIELDS and float(value) != int(value):
            raise ValueError(f"'{field}' must be a whole number")
        clean[field] = float(value)
    return clean
