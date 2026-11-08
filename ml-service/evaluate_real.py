"""Evaluate the deployed time-and-route crowd model on REAL, labelled observations (held out from training).

The shipped model is trained on synthetic data, so its test scores say nothing about real buses. Once real
observations exist (manual head counts, validated camera counts), put them in a CSV and run:

    python evaluate_real.py data/real_observations.csv [--report models/real_eval.json]

CSV columns (one row per observed trip; see data/real_eval_template.csv):
    hour, day_of_week (0 = Monday), route_distance_km, stop_count, bus_capacity, and EITHER
    crowd_level (LOW / MEDIUM / HIGH, observed) OR onboard (observed passengers; converted with the same
    thresholds as the backend: onboard / capacity < 0.50 LOW, < 0.85 MEDIUM, otherwise HIGH).
Rows must not have been used for training. The script prints accuracy, macro-F1, per-class precision/recall,
the confusion matrix, a most-frequent-class baseline and the share of rows outside the training range.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score

from features import CLASSES, INPUT_FIELDS, add_derived_features, out_of_training_range, validate_instance

MODEL = Path(__file__).parent / "models" / "crowd_model.joblib"
MEDIUM_AT, HIGH_AT = 0.50, 0.85   # same as app.crowd.thresholds in the backend


def label_from_onboard(onboard: float, capacity: float) -> str:
    ratio = onboard / capacity
    return "LOW" if ratio < MEDIUM_AT else ("MEDIUM" if ratio < HIGH_AT else "HIGH")


def load_rows(path: Path) -> tuple[pd.DataFrame, list[str], list[str]]:
    df = pd.read_csv(path)
    missing = [c for c in INPUT_FIELDS if c not in df.columns]
    if missing:
        raise SystemExit(f"missing columns: {', '.join(missing)}")
    if "crowd_level" not in df.columns and "onboard" not in df.columns:
        raise SystemExit("need a crowd_level or an onboard column with the observed value")
    rows, labels, rejected = [], [], []
    for i, rec in df.iterrows():
        try:
            clean = validate_instance({k: (None if pd.isna(rec[k]) else float(rec[k])) for k in INPUT_FIELDS})
            if "crowd_level" in df.columns and not pd.isna(rec.get("crowd_level")):
                label = str(rec["crowd_level"]).strip().upper()
                label = "HIGH" if label == "FULL" else label
                if label not in CLASSES:
                    raise ValueError(f"crowd_level '{label}' is not one of {CLASSES}")
            else:
                label = label_from_onboard(float(rec["onboard"]), clean["bus_capacity"])
        except (ValueError, TypeError, KeyError) as exc:
            rejected.append(f"row {i + 2}: {exc}")
            continue
        rows.append(clean)
        labels.append(label)
    return pd.DataFrame(rows, columns=INPUT_FIELDS), labels, rejected


def main(argv=None) -> dict:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("csv", type=Path)
    ap.add_argument("--model", type=Path, default=MODEL)
    ap.add_argument("--report", type=Path, default=None, help="also write the results as JSON")
    args = ap.parse_args(argv)

    bundle = joblib.load(args.model)
    pipeline, version = bundle["pipeline"], bundle["metrics"]["model_version"]
    frame, truth, rejected = load_rows(args.csv)
    for r in rejected:
        print("skipped", r, file=sys.stderr)
    if not truth:
        raise SystemExit("no valid rows to evaluate")

    predicted = list(pipeline.predict(add_derived_features(frame)))
    majority = max(set(truth), key=truth.count)
    outside = sum(1 for _, row in frame.iterrows() if out_of_training_range(row.to_dict()))
    result = {
        "model_version": version,
        "rows": len(truth),
        "rows_skipped": len(rejected),
        "rows_outside_training_range": outside,
        "accuracy": round(accuracy_score(truth, predicted), 3),
        "macro_f1": round(f1_score(truth, predicted, labels=CLASSES, average="macro", zero_division=0), 3),
        "baseline_most_frequent": {"class": majority, "accuracy": round(truth.count(majority) / len(truth), 3)},
        "confusion_matrix": {"labels": CLASSES, "rows_true_cols_predicted": confusion_matrix(truth, predicted, labels=CLASSES).tolist()},
        "per_class": classification_report(truth, predicted, labels=CLASSES, output_dict=True, zero_division=0),
    }
    print(f"model {version} on {len(truth)} real observations ({len(rejected)} skipped, {outside} outside the training range)")
    print(f"accuracy {result['accuracy']}  macro-F1 {result['macro_f1']}  "
          f"(baseline '{majority}' always: accuracy {result['baseline_most_frequent']['accuracy']})")
    print(classification_report(truth, predicted, labels=CLASSES, zero_division=0))
    print("confusion matrix (rows = observed, columns = predicted):", CLASSES)
    for label, row in zip(CLASSES, result["confusion_matrix"]["rows_true_cols_predicted"]):
        print(f"  {label:7} {row}")
    if args.report:
        args.report.write_text(json.dumps(result, indent=2))
        print(f"report written to {args.report}")
    return result


if __name__ == "__main__":
    main()
