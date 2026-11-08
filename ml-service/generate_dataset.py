"""Generate the SYNTHETIC historical ridership dataset used to train the crowd model.

IMPORTANT: no real passenger-count data was available for this academic project.
This script simulates one record per bus trip with a documented, rule-based
demand model plus random noise. It is a demonstration dataset only; a model
trained on it does NOT prove real-world accuracy. Replace data/crowd_history.csv
with real automatic-passenger-counter or ticketing data when available (same
columns) and re-run train.py.

Demand model (what the generator assumes):
  * weekday commuter peaks around 08:30 and 18:00, a smaller lunchtime bump,
    very low demand late at night;
  * Saturday demand is ~75 % of weekday and flatter, Sunday ~55 %;
  * longer routes with more stops carry more passengers;
  * occupancy = passengers / bus capacity, so smaller buses fill up faster;
  * ~12 % multiplicative noise, plus ~3 % of trips with a random event surge;
  * ~2 % of stop_count / route_distance_km values are blanked to mimic gaps
    in operational logs (the training pipeline imputes them).

Labels (target): LOW if occupancy < 0.50, MEDIUM if < 0.85, otherwise HIGH.

Usage:  python generate_dataset.py [--rows 60000] [--seed 42]
"""

import argparse
import math
from pathlib import Path

import numpy as np
import pandas as pd

OUTPUT = Path(__file__).parent / "data" / "crowd_history.csv"


def hourly_demand(hour: int, day_of_week: int) -> float:
    """Relative passenger demand (0..~1.2) for a given hour and day."""
    def bump(center, width, height):
        return height * math.exp(-((hour - center) ** 2) / (2 * width ** 2))

    if day_of_week < 5:  # Monday-Friday
        demand = 0.12 + bump(8.5, 1.2, 0.95) + bump(18.0, 1.5, 0.90) + bump(13.0, 1.5, 0.30)
    elif day_of_week == 5:  # Saturday: later, flatter
        demand = 0.75 * (0.12 + bump(11.5, 2.5, 0.65) + bump(18.5, 2.0, 0.60))
    else:  # Sunday
        demand = 0.55 * (0.12 + bump(12.0, 3.0, 0.60) + bump(18.0, 2.0, 0.45))
    if hour < 5 or hour >= 23:
        demand *= 0.25  # night service
    return demand


def label(occupancy: float) -> str:
    if occupancy < 0.50:
        return "LOW"
    if occupancy < 0.85:
        return "MEDIUM"
    return "HIGH"


def generate(rows: int, seed: int) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    start = pd.Timestamp("2025-01-06")  # a Monday
    records = []
    for _ in range(rows):
        day_offset = int(rng.integers(0, 180))
        date = start + pd.Timedelta(days=day_offset)
        hour = int(rng.integers(0, 24))
        dow = date.dayofweek
        distance = float(np.round(rng.uniform(3.0, 30.0), 1))
        stops = int(np.clip(round(distance / 1.8 + rng.normal(0, 2)), 3, 25))
        capacity = int(rng.choice([30, 40, 50, 60, 70]))

        route_size = (stops / 10) ** 0.6 * (distance / 15) ** 0.3
        expected_passengers = 58 * hourly_demand(hour, dow) * route_size
        noise = rng.lognormal(mean=0.0, sigma=0.12)
        surge = 1.4 if rng.random() < 0.03 else 1.0
        passengers = max(0, int(round(expected_passengers * noise * surge)))
        occupancy = passengers / capacity

        records.append({
            "trip_date": date.date().isoformat(),
            "hour": hour,
            "day_of_week": dow,
            "route_distance_km": distance,
            "stop_count": stops,
            "bus_capacity": capacity,
            "passenger_count": passengers,      # NOT a model feature (would leak the label)
            "occupancy_ratio": round(occupancy, 3),
            "crowd_level": label(occupancy),
        })

    df = pd.DataFrame(records).sort_values(["trip_date", "hour"]).reset_index(drop=True)
    # Simulate gaps in operational logs.
    for col in ("stop_count", "route_distance_km"):
        mask = rng.random(len(df)) < 0.02
        df.loc[mask, col] = np.nan
    return df


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rows", type=int, default=60000)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    df = generate(args.rows, args.seed)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(OUTPUT, index=False)
    print(f"Wrote {len(df)} synthetic trip records to {OUTPUT}")
    print(df["crowd_level"].value_counts(normalize=True).round(3).to_string())


if __name__ == "__main__":
    main()
