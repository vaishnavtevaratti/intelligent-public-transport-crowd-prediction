"""Train and evaluate the 15-minute-ahead occupancy forecast models.

Two targets, both 15 minutes after the observation time:
  * passengers onboard (regression)        -> MAE, RMSE
  * crowd level LOW/MEDIUM/HIGH/FULL       -> precision, recall, F1, confusion matrix

Method
  1. Time-based split: the last 25 % of service days are the test set (no day appears in both).
  2. Baseline = PERSISTENCE ("in 15 minutes the bus will look exactly like now"). A model is only
     useful if it beats this; the result is reported either way.
  3. Candidates are compared with GroupKFold cross-validation on training days (groups = days).
  4. The selected models are refitted on all training days and evaluated once on the test days.

Usage:  python generate_occupancy_series.py && python train_forecast.py
Output: models/forecast_model.joblib, models/forecast_metrics.json
"""

import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.metrics import (accuracy_score, classification_report, confusion_matrix, f1_score,
                             mean_absolute_error, mean_squared_error)
from sklearn.model_selection import GroupKFold, cross_val_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from forecast_features import CLASSES, HORIZON_MINUTES, MODEL_FEATURES, THRESHOLDS, add_derived_features, crowd_level

BASE = Path(__file__).parent
DATA = BASE / "data" / "occupancy_series.csv"
MODEL_PATH = BASE / "models" / "forecast_model.joblib"
METRICS_PATH = BASE / "models" / "forecast_metrics.json"
SEED = 42


def regressors():
    return {
        "ridge_regression": Pipeline([("impute", SimpleImputer(strategy="median")), ("scale", StandardScaler()),
                                      ("model", Ridge(alpha=1.0))]),
        "hist_gradient_boosting": Pipeline([("impute", SimpleImputer(strategy="median")),
                                            ("model", HistGradientBoostingRegressor(max_iter=300, learning_rate=0.08,
                                                                                    random_state=SEED))]),
    }


def classifiers():
    return {
        "logistic_regression": Pipeline([("impute", SimpleImputer(strategy="median")), ("scale", StandardScaler()),
                                         ("model", LogisticRegression(max_iter=2000, class_weight="balanced"))]),
        "hist_gradient_boosting": Pipeline([("impute", SimpleImputer(strategy="median")),
                                            ("model", HistGradientBoostingClassifier(max_iter=300, learning_rate=0.08,
                                                                                     class_weight="balanced",
                                                                                     random_state=SEED))]),
    }


def rmse(y, p):
    return float(np.sqrt(mean_squared_error(y, p)))


def class_report(y_true, y_pred):
    rep = classification_report(y_true, y_pred, labels=CLASSES, output_dict=True, zero_division=0)
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=CLASSES, average="macro", zero_division=0)),
        "per_class": {c: {k: float(rep[c][k]) for k in ("precision", "recall", "f1-score", "support")} for c in CLASSES},
        "confusion_matrix": {"labels": CLASSES, "matrix": confusion_matrix(y_true, y_pred, labels=CLASSES).tolist()},
    }


def main():
    if not DATA.exists():
        raise SystemExit(f"{DATA} not found. Run: python generate_occupancy_series.py")
    df = pd.read_csv(DATA)
    # --- data validation ---
    assert set(df["target_crowd_level"].unique()) <= set(CLASSES), "unexpected labels"
    assert (df["current_passengers"] >= 0).all() and (df["target_passengers"] >= 0).all(), "negative counts"
    assert (df["bus_capacity"] > 0).all(), "invalid capacity"
    print(f"Rows: {len(df)}, missing 15-min history: {df['passengers_15min_ago'].isna().mean():.1%}")

    X = add_derived_features(df)
    y_reg = df["target_passengers"]
    y_cls = df["target_crowd_level"]

    days = np.sort(df["service_date"].unique())
    cutoff = days[int(len(days) * 0.75)]
    tr = df["service_date"] < cutoff
    te = ~tr
    groups = df.loc[tr, "service_date"]
    print(f"Train rows {tr.sum()} (days < {cutoff}), test rows {te.sum()}")

    # --- baselines on the test days ---
    persistence_count = df.loc[te, "current_passengers"]
    persistence_level = [crowd_level(c / cap) for c, cap in zip(df.loc[te, "current_passengers"], df.loc[te, "bus_capacity"])]
    baseline = {
        "persistence_regression": {"mae": float(mean_absolute_error(y_reg[te], persistence_count)),
                                   "rmse": rmse(y_reg[te], persistence_count)},
        "persistence_classification": class_report(y_cls[te], persistence_level),
    }
    print(f"Persistence baseline: MAE {baseline['persistence_regression']['mae']:.2f}, "
          f"RMSE {baseline['persistence_regression']['rmse']:.2f}, "
          f"macro-F1 {baseline['persistence_classification']['macro_f1']:.3f}")

    cv = GroupKFold(n_splits=4)
    cv_reg, cv_cls = {}, {}
    for name, pipe in regressors().items():
        s = -cross_val_score(pipe, X[tr], y_reg[tr], groups=groups, cv=cv, scoring="neg_mean_absolute_error")
        cv_reg[name] = {"mae_mean": float(s.mean()), "mae_std": float(s.std())}
        print(f"  regressor  {name:24s} CV MAE {s.mean():.3f} ± {s.std():.3f}")
    for name, pipe in classifiers().items():
        s = cross_val_score(pipe, X[tr], y_cls[tr], groups=groups, cv=cv, scoring="f1_macro")
        cv_cls[name] = {"macro_f1_mean": float(s.mean()), "macro_f1_std": float(s.std())}
        print(f"  classifier {name:24s} CV macro-F1 {s.mean():.3f} ± {s.std():.3f}")

    best_reg = min(cv_reg, key=lambda n: cv_reg[n]["mae_mean"])
    best_cls = max(cv_cls, key=lambda n: cv_cls[n]["macro_f1_mean"])
    reg = regressors()[best_reg].fit(X[tr], y_reg[tr])
    cls = classifiers()[best_cls].fit(X[tr], y_cls[tr])

    pred_count = np.clip(reg.predict(X[te]), 0, None)
    pred_level = cls.predict(X[te])
    test = {
        "regression": {"model": best_reg, "mae": float(mean_absolute_error(y_reg[te], pred_count)),
                       "rmse": rmse(y_reg[te], pred_count)},
        "classification": {"model": best_cls, **class_report(y_cls[te], pred_level)},
    }
    version = f"forecast15-{best_cls}-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    metrics = {
        "model_version": version,
        "horizon_minutes": HORIZON_MINUTES,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "sklearn_version": sklearn.__version__,
        "thresholds": THRESHOLDS,
        "classes": CLASSES,
        "features": MODEL_FEATURES,
        "dataset": {
            "path": "data/occupancy_series.csv",
            "source": "SYNTHETIC - generated by generate_occupancy_series.py (not real passenger data)",
            "rows": int(len(df)), "train_rows": int(tr.sum()), "test_rows": int(te.sum()),
            "split": f"time-based: train days < {cutoff}, test days >= {cutoff}",
            "class_distribution": {k: int(v) for k, v in y_cls.value_counts().items()},
        },
        "cross_validation": {"regression_mae": cv_reg, "classification_macro_f1": cv_cls},
        "baseline_test": baseline,
        "test": test,
    }
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump({"regressor": reg, "classifier": cls, "metrics": metrics}, MODEL_PATH)
    METRICS_PATH.write_text(json.dumps(metrics, indent=2))

    print(f"\nTEST (unseen days) - regression {best_reg}: MAE {test['regression']['mae']:.2f} "
          f"(persistence {baseline['persistence_regression']['mae']:.2f}), RMSE {test['regression']['rmse']:.2f} "
          f"(persistence {baseline['persistence_regression']['rmse']:.2f})")
    print(f"TEST - classification {best_cls}: accuracy {test['classification']['accuracy']:.3f}, "
          f"macro-F1 {test['classification']['macro_f1']:.3f} (persistence {baseline['persistence_classification']['macro_f1']:.3f})")
    print(classification_report(y_cls[te], pred_level, labels=CLASSES, zero_division=0))
    print("Confusion matrix (rows actual, cols predicted):", CLASSES)
    print(np.array(test["classification"]["confusion_matrix"]["matrix"]))
    print(f"Saved {MODEL_PATH}")


if __name__ == "__main__":
    main()
