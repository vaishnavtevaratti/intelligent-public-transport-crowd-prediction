"""Crowd prediction ML service (HTTP).

The Spring Boot backend calls this service; browsers never call it directly.

Endpoints
  GET  /health       -> service and model status
  GET  /model/info   -> model type, features, dataset source, evaluation metrics
  POST /predict      -> {"instances": [{hour, day_of_week, route_distance_km,
                                         stop_count, bus_capacity}, ...]}
                        returns one {crowd_level, confidence, probabilities} per instance
  POST /forecast     -> 15-minute-ahead forecast from LIVE occupancy:
                        {"instances": [{minute_of_day, day_of_week, route_distance_km, stop_count,
                                        bus_capacity, current_passengers, passengers_15min_ago}]}
                        returns {crowd_level (LOW/MEDIUM/HIGH/FULL), confidence, probabilities,
                                 predicted_passengers, horizon_minutes}
  GET  /forecast/info -> forecast model metrics (incl. persistence baseline)

Run:  python app.py            (serves on ML_SERVICE_HOST:ML_SERVICE_PORT, default 127.0.0.1:8001)
"""

import logging
import os
from pathlib import Path

import joblib
import pandas as pd
from flask import Flask, jsonify, request

import forecast_features
from features import CLASSES, add_derived_features, out_of_training_range, validate_instance

MODEL_PATH = Path(os.environ.get("MODEL_PATH", Path(__file__).parent / "models" / "crowd_model.joblib"))
FORECAST_MODEL_PATH = Path(os.environ.get("FORECAST_MODEL_PATH", Path(__file__).parent / "models" / "forecast_model.joblib"))
MAX_INSTANCES = 500

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("crowd-ml-service")


def load_model(path: Path):
    if not path.exists():
        log.error("Model file %s not found. Run 'python train.py' first.", path)
        return None, None
    bundle = joblib.load(path)
    log.info("Loaded model %s", bundle["metrics"]["model_version"])
    return bundle["pipeline"], bundle["metrics"]


def load_forecast_model(path: Path):
    if not path.exists():
        log.warning("Forecast model %s not found. Run 'python train_forecast.py' to enable /forecast.", path)
        return None
    bundle = joblib.load(path)
    log.info("Loaded forecast model %s", bundle["metrics"]["model_version"])
    return bundle


def parse_instances(validator):
    """Returns (rows, None) or (None, error_response)."""
    body = request.get_json(silent=True)
    if not isinstance(body, dict) or not isinstance(body.get("instances"), list):
        return None, (jsonify(error="Request body must be JSON: {\"instances\": [ ... ]}"), 400)
    instances = body["instances"]
    if not instances:
        return None, (jsonify(error="'instances' must not be empty"), 400)
    if len(instances) > MAX_INSTANCES:
        return None, (jsonify(error=f"At most {MAX_INSTANCES} instances per request"), 400)
    try:
        return [validator(item) for item in instances], None
    except ValueError as exc:
        return None, (jsonify(error=f"Invalid instance: {exc}"), 400)


def create_app(model_path: Path = MODEL_PATH, forecast_path: Path = FORECAST_MODEL_PATH) -> Flask:
    app = Flask(__name__)
    pipeline, metrics = load_model(model_path)
    forecast = load_forecast_model(forecast_path)

    @app.get("/health")
    def health():
        if pipeline is None:
            return jsonify(status="DOWN", reason="model not trained"), 503
        return jsonify(status="UP", model_version=metrics["model_version"],
                       forecast_model_version=forecast["metrics"]["model_version"] if forecast else None)

    @app.get("/model/info")
    def model_info():
        if pipeline is None:
            return jsonify(error="Model not trained. Run 'python train.py'."), 503
        return jsonify(metrics)

    @app.post("/predict")
    def predict():
        if pipeline is None:
            return jsonify(error="Model not trained. Run 'python train.py'."), 503
        body = request.get_json(silent=True)
        if not isinstance(body, dict) or not isinstance(body.get("instances"), list):
            return jsonify(error="Request body must be JSON: {\"instances\": [ ... ]}"), 400
        instances = body["instances"]
        if not instances:
            return jsonify(error="'instances' must not be empty"), 400
        if len(instances) > MAX_INSTANCES:
            return jsonify(error=f"At most {MAX_INSTANCES} instances per request"), 400
        try:
            rows = [validate_instance(item) for item in instances]
        except ValueError as exc:
            return jsonify(error=f"Invalid instance: {exc}"), 400

        X = add_derived_features(pd.DataFrame(rows))
        probabilities = pipeline.predict_proba(X)
        model_classes = list(pipeline.classes_)
        results = []
        for raw, row in zip(rows, probabilities):
            probs = {cls: round(float(row[model_classes.index(cls)]), 4) for cls in CLASSES}
            level = max(probs, key=probs.get)
            results.append({"crowd_level": level, "confidence": probs[level], "probabilities": probs,
                            "out_of_training_range": out_of_training_range(raw)})
        return jsonify(model_version=metrics["model_version"], predictions=results)

    @app.get("/forecast/info")
    def forecast_info():
        if forecast is None:
            return jsonify(error="Forecast model not trained. Run 'python train_forecast.py'."), 503
        return jsonify(forecast["metrics"])

    @app.post("/forecast")
    def forecast_endpoint():
        if forecast is None:
            return jsonify(error="Forecast model not trained. Run 'python train_forecast.py'."), 503
        rows, error = parse_instances(forecast_features.validate_instance)
        if error:
            return error
        X = forecast_features.add_derived_features(pd.DataFrame(rows))
        counts = forecast["regressor"].predict(X)
        probabilities = forecast["classifier"].predict_proba(X)
        classes = list(forecast["classifier"].classes_)
        results = []
        for raw, count, row in zip(rows, counts, probabilities):
            probs = {c: round(float(row[classes.index(c)]), 4) for c in forecast_features.CLASSES}
            level = max(probs, key=probs.get)
            results.append({"crowd_level": level, "confidence": probs[level], "probabilities": probs,
                            "predicted_passengers": round(max(0.0, float(count)), 1),
                            "horizon_minutes": forecast_features.HORIZON_MINUTES,
                            "out_of_training_range": out_of_training_range(raw, forecast_features.TRAINING_RANGES)})
        return jsonify(model_version=forecast["metrics"]["model_version"], predictions=results)

    @app.errorhandler(Exception)
    def unexpected(exc):
        if hasattr(exc, "code") and isinstance(exc.code, int):  # HTTP errors (404, 405, ...)
            return jsonify(error=exc.description), exc.code
        log.exception("Unexpected error")
        return jsonify(error="Internal error in ML service"), 500

    return app


if __name__ == "__main__":
    host = os.environ.get("ML_SERVICE_HOST", "127.0.0.1")
    port = int(os.environ.get("ML_SERVICE_PORT", "8001"))
    application = create_app()
    try:
        from waitress import serve
        log.info("Serving on http://%s:%d (waitress)", host, port)
        serve(application, host=host, port=port)
    except ImportError:
        application.run(host=host, port=port)
