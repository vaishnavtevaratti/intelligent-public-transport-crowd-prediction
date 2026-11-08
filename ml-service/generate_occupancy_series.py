"""Generate a SYNTHETIC 5-minute onboard-occupancy time series for the short-horizon forecast model.

IMPORTANT: this is simulated demonstration data, not measurements from real buses.
Real production training data would be per-bus onboard counts every few minutes from validated
door counters / automatic passenger counting (APC) systems, together with the route, timetable and
calendar (holidays, events, weather).

Simulation, per bus and service day (05:00-23:00, one row every 5 minutes):
  * expected load follows the same commuter demand curve as generate_dataset.py
    (weekday peaks ~08:30 and ~18:00, flatter weekends), scaled by route size;
  * the actual load drifts around the expectation with an AR(1) process (persistent deviations),
    plus occasional surges (events) that build up and decay over 30-90 minutes;
  * passengers are capped at 120 % of total capacity (crush load).
Each row stores the current load, the load 15 minutes earlier, and the TARGET = load 15 minutes later.

Usage:  python generate_occupancy_series.py [--buses 16] [--days 60] [--seed 7]
Output: data/occupancy_series.csv (git-ignored; regenerate deterministically with the same seed)
"""

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from forecast_features import HORIZON_MINUTES, crowd_level
from generate_dataset import hourly_demand

OUTPUT = Path(__file__).parent / "data" / "occupancy_series.csv"
STEP_MIN = 5
LAG = HORIZON_MINUTES // STEP_MIN


def simulate_day(rng, date, bus):
    minutes = np.arange(5 * 60, 23 * 60 + 1, STEP_MIN)
    dow = date.dayofweek
    route_size = (bus["stops"] / 10) ** 0.6 * (bus["distance"] / 15) ** 0.3
    load = []
    deviation = 0.0
    surge = 0.0
    for m in minutes:
        hour = m / 60.0
        # interpolate the hourly demand curve for smooth 5-minute values
        h0 = int(hour)
        frac = hour - h0
        demand = (1 - frac) * hourly_demand(h0, dow) + frac * hourly_demand(min(h0 + 1, 23), dow)
        expected = 58 * demand * route_size * bus["scale"]
        deviation = 0.85 * deviation + rng.normal(0, 0.06)          # persistent drift
        if surge <= 0.01 and rng.random() < 0.004:
            surge = rng.uniform(0.25, 0.6)                             # an event starts
        surge *= 0.93
        value = expected * (1 + deviation + surge) + rng.normal(0, 1.5)
        load.append(int(np.clip(round(value), 0, round(bus["capacity"] * 1.2))))
    return minutes, np.array(load)


def generate(n_buses, n_days, seed):
    rng = np.random.default_rng(seed)
    start = pd.Timestamp("2025-01-06")  # Monday
    buses = []
    for b in range(n_buses):
        distance = float(np.round(rng.uniform(4, 28), 1))
        buses.append({
            "bus": f"SIM-{b + 1:02d}",
            "distance": distance,
            "stops": int(np.clip(round(distance / 1.8 + rng.normal(0, 2)), 3, 25)),
            "capacity": int(rng.choice([40, 50, 60, 70])),
            "scale": rng.uniform(0.8, 1.2),
        })
    rows = []
    for day in range(n_days):
        date = start + pd.Timedelta(days=day)
        for bus in buses:
            minutes, load = simulate_day(rng, date, bus)
            for i in range(LAG, len(minutes) - LAG):
                future = load[i + LAG]
                rows.append({
                    "service_date": date.date().isoformat(),
                    "bus": bus["bus"],
                    "minute_of_day": int(minutes[i]),
                    "day_of_week": date.dayofweek,
                    "route_distance_km": bus["distance"],
                    "stop_count": bus["stops"],
                    "bus_capacity": bus["capacity"],
                    "current_passengers": int(load[i]),
                    "passengers_15min_ago": int(load[i - LAG]),
                    "target_passengers": int(future),
                    "target_crowd_level": crowd_level(future / bus["capacity"]),
                })
    df = pd.DataFrame(rows)
    # ~3 % of rows lose their 15-minute history (sensor gap), like a real feed
    mask = rng.random(len(df)) < 0.03
    df.loc[mask, "passengers_15min_ago"] = np.nan
    return df


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--buses", type=int, default=16)
    parser.add_argument("--days", type=int, default=60)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    df = generate(args.buses, args.days, args.seed)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(OUTPUT, index=False)
    print(f"Wrote {len(df)} synthetic 5-minute rows to {OUTPUT}")
    print(df["target_crowd_level"].value_counts(normalize=True).round(3).to_string())


if __name__ == "__main__":
    main()
