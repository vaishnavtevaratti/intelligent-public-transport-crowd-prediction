"""Feature definitions shared by training (train.py) and serving (app.py).

Keeping them in one place guarantees the model sees exactly the same columns,
in the same order, at training time and at prediction time.
"""

import math

import pandas as pd

# Raw inputs the Spring Boot backend sends for each prediction ("time and route data").
INPUT_FIELDS = ["hour", "day_of_week", "route_distance_km", "stop_count", "bus_capacity"]

# Columns the model is trained on (raw inputs + derived features).
MODEL_FEATURES = [
    "hour",
    "hour_sin",
    "hour_cos",
    "day_of_week",
    "is_weekend",
    "route_distance_km",
    "stop_count",
    "bus_capacity",
]

CLASSES = ["LOW", "MEDIUM", "HIGH"]

# Validation limits for incoming requests. stop_count / route_distance_km may be
# missing (None); the model pipeline imputes them with the training median.
LIMITS = {
    "hour": (0, 23),
    "day_of_week": (0, 6),  # 0 = Monday ... 6 = Sunday (Python weekday())
    "route_distance_km": (0.1, 500.0),
    "stop_count": (2, 200),
    "bus_capacity": (1, 300),
}
OPTIONAL_FIELDS = {"route_distance_km", "stop_count"}

# Ranges the time-and-route model was trained on (generate_dataset.py). Outside them it extrapolates,
# so predictions are flagged and the backend does not use them.
TRAINING_RANGES = {"route_distance_km": (3.0, 30.0), "stop_count": (3, 25), "bus_capacity": (30, 70)}


def out_of_training_range(clean: dict, ranges: dict = TRAINING_RANGES) -> list:
    """Names of inputs outside the training range (missing optional values are imputed, so they are fine)."""
    result = []
    for field, (low, high) in ranges.items():
        value = clean.get(field)
        if value is not None and value == value and not (low <= value <= high):  # value == value: not NaN
            result.append(field)
    return result


def add_derived_features(df: pd.DataFrame) -> pd.DataFrame:
    """Return a copy of df with the derived columns used by the model."""
    out = df.copy()
    # Encode hour on a circle so 23:00 and 00:00 are close to each other.
    out["hour_sin"] = out["hour"].apply(lambda h: math.sin(2 * math.pi * h / 24))
    out["hour_cos"] = out["hour"].apply(lambda h: math.cos(2 * math.pi * h / 24))
    out["is_weekend"] = (out["day_of_week"] >= 5).astype(int)
    return out[MODEL_FEATURES]


def validate_instance(raw: dict) -> dict:
    """Validate one prediction request. Raises ValueError with a readable message."""
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
        if field in ("hour", "day_of_week", "stop_count", "bus_capacity") and float(value) != int(value):
            raise ValueError(f"'{field}' must be a whole number")
        clean[field] = float(value)
    return clean
