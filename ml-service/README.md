# Crowd Prediction ML Service

This Python service predicts how crowded a bus will be (**LOW / MEDIUM / HIGH**) from time and route data, as the SRS requires (Python, scikit-learn, pandas, NumPy; a separate ML server reached over HTTP).

The Spring Boot backend calls it. Browsers never call it directly.

```
React  â”€â”€RESTâ”€â”€â–¶  Spring Boot  â”€â”€HTTP JSONâ”€â”€â–¶  ml-service (Flask + scikit-learn)
                      â”‚                              â”‚
                   MySQL (stores every prediction)   models/crowd_model.joblib
```

## Run it

```powershell
cd ml-service
python -m venv .venv                      # once
.venv\Scripts\python -m pip install -r requirements.txt   # once
.venv\Scripts\python generate_dataset.py  # optional: regenerate data/crowd_history.csv
.venv\Scripts\python train.py             # optional: retrain models/crowd_model.joblib
.venv\Scripts\python app.py               # serves http://127.0.0.1:8001
.venv\Scripts\python -m pytest -q         # 12 tests
```

The trained model and the dataset are committed, so `app.py` works without retraining.
Both scripts use a fixed random seed (42), so re-running them reproduces the same results.

## 1. Prediction target

`crowd_level` is the bus occupancy ratio (passengers Ã· capacity), grouped into three classes:

| Class  | Occupancy |
|--------|-----------|
| LOW    | < 50 %    |
| MEDIUM | 50 â€“ 85 % |
| HIGH   | â‰¥ 85 %    |

## 2. Input features

| Feature | Source in the backend | Notes |
|---|---|---|
| `hour` (0â€“23) | the requested travel time | also encoded as `hour_sin`/`hour_cos`, so 23:00 and 00:00 count as close together |
| `day_of_week` (0 = Monday) | the requested travel time | also `is_weekend` |
| `route_distance_km` | `routes.distance` | may be missing; the median is used |
| `stop_count` | number of `route_stops` | may be missing; the median is used |
| `bus_capacity` | `buses.capacity` | |

`passenger_count` and `occupancy_ratio` are in the dataset but are **not** features. They would leak the answer.

## 3. Dataset â€” SYNTHETIC (important)

No real passenger-count data was available, so `generate_dataset.py` **simulates** 60,000 bus trips over 180 days. It uses a documented demand model:
- weekday commuter peaks around 08:30 and 18:00, plus a smaller lunchtime bump;
- flatter, lower demand on weekends;
- more passengers on longer routes with more stops;
- smaller buses fill up faster;
- about 12 % random noise, about 3 % random "event" surges, and about 2 % of route values deliberately blanked.

Class balance: LOW 39,745 Â· MEDIUM 10,423 Â· HIGH 9,832.

**This is demonstration data.** The scores below show that the model learned the simulated pattern. They do **not** prove real-world accuracy. To use real data, replace `data/crowd_history.csv` with data in the same columns (for example from ticketing or automatic passenger counters) and run `train.py`.

## 4. Training and evaluation method (`train.py`)

1. **Time-based split.** The model trains on days before 2025-05-30 (48,000 trips) and is tested on the later days (12,000 trips). It is always tested on days it has never seen.
2. **Model selection.** Three candidates are compared with 5-fold `GroupKFold` cross-validation on the training days only. Folds are grouped by day, so one day never appears in both training and validation. Selection uses **macro-F1**, because the classes are imbalanced and plain accuracy would reward always predicting LOW.
3. The best candidate is refitted on all training days, evaluated **once** on the test days, and saved with its metrics.

Every candidate includes a median `SimpleImputer` for missing values. Class weights are set to "balanced".

| Candidate | CV macro-F1 |
|---|---|
| Logistic regression | 0.594 |
| Random forest | 0.881 |
| **HistGradientBoosting (selected)** | **0.885** |

## 5. Results on the held-out test days

Overall: **accuracy 0.921 Â· macro-F1 0.888**

| Class | Precision | Recall | F1 | Support |
|---|---|---|---|---|
| LOW | 0.989 | 0.937 | 0.962 | 7,878 |
| MEDIUM | 0.724 | 0.880 | 0.795 | 2,092 |
| HIGH | 0.916 | 0.900 | 0.908 | 2,030 |

Confusion matrix (rows = actual, columns = predicted: LOW, MEDIUM, HIGH):

```
LOW     7379   499     0
MEDIUM    83  1842   167
HIGH       0   202  1828
```

The model never confuses LOW with HIGH. Its errors are between neighbouring levels, mostly near the 50 % and 85 % boundaries, where noise makes the label genuinely uncertain.

## 6. API

`POST /predict`
```json
{"instances": [{"hour": 8, "day_of_week": 1, "route_distance_km": 12.5, "stop_count": 6, "bus_capacity": 50}]}
```
returns
```json
{"model_version": "hist_gradient_boosting-â€¦", "predictions": [{"crowd_level": "MEDIUM", "confidence": 0.71,
  "probabilities": {"LOW": 0.2, "MEDIUM": 0.71, "HIGH": 0.09}}]}
```
- Invalid input (for example `hour` 24, a missing field, or text where a number is expected) returns **400** with a message.
- A missing model file returns **503**.
- At most 500 instances are accepted per call.

`GET /health` returns the service status. `GET /model/info` returns the metrics shown above. The admin dashboard and the Crowd Prediction page display both.

## 7. How the application uses it

- **On demand:** a user picks a bus and a travel time on the Crowd Prediction page. The result is stored in `crowd_predictions` with its probabilities and model version.
- **Scheduled:** every 5 minutes, one prediction is made for each active bus and pushed live on WebSocket `/topic/crowd`. These are kept for 30 days.
- **Best time to travel** (SRS algorithm 6): the model predicts all 24 hours of a day for a route. The hours with the lowest expected crowding (P(MEDIUM)Ã—0.5 + P(HIGH)) are suggested.
- **When this service is down,** the backend answers **503** with a clear message. It never invents a prediction.

---

# Model 2: 15-minute live occupancy forecast

The first model only knows the time and the route. The second model also uses the **live onboard estimate** from the cameras (vision-service) or a manual count, together with its **change over the last 15 minutes**. It predicts two things **15 minutes ahead**:
- the number of passengers onboard (regression);
- the crowd level **LOW / MEDIUM / HIGH / FULL** (classification).

| Level | Occupancy = onboard / total passenger capacity |
|---|---|
| LOW | < 50 % |
| MEDIUM | 50 â€“ 85 % |
| HIGH | 85 â€“ 100 % |
| FULL | â‰¥ 100 % |

The backend uses the same thresholds (`app.crowd.thresholds.*`).

```powershell
.venv\Scripts\python generate_occupancy_series.py   # data/occupancy_series.csv (git-ignored, deterministic, seed 7)
.venv\Scripts\python train_forecast.py               # models/forecast_model.joblib + models/forecast_metrics.json
```

## Data (SYNTHETIC)

`generate_occupancy_series.py` simulates 16 buses over 60 days, one row every 5 minutes from 05:00 to 23:00 (202,560 rows). The load follows the same commuter demand curve as Model 1, with:
- persistent random drift (an AR(1) process);
- occasional event surges that build up and fade over 30â€“90 minutes;
- a crush-load cap of 120 % of capacity;
- 3 % of rows with no 15-minute history.

For real use, the training data would need per-bus onboard counts every few minutes from validated door counters (an APC system), plus the timetable and calendar.

**Features:** time of day (with sine/cosine), weekday, weekend flag, route length, stop count, capacity, current passengers, current occupancy, and the change over the last 15 minutes. The target values (passengers 15 minutes later) are **never** used as inputs.

## Method

1. **Time-based split:** training uses days before 2025-02-20 (151,920 rows); testing uses the last 25 % of days (50,640 rows).
2. **Baseline = persistence:** "in 15 minutes the bus will look exactly like now". A forecast is only useful if it beats this.
3. **Model selection:** 4-fold `GroupKFold` cross-validation by day, on training days only.

| Candidate | CV result |
|---|---|
| Ridge regression | MAE 2.81 |
| **HistGradientBoosting regressor (selected)** | **MAE 2.13** |
| Logistic regression | macro-F1 0.765 |
| **HistGradientBoosting classifier (selected)** | **macro-F1 0.785** |

## Results on unseen test days

| Target | Model | Persistence baseline |
|---|---|---|
| Passengers in 15 min, MAE | **2.12** | 2.93 |
| Passengers in 15 min, RMSE | **2.87** | 3.99 |
| Crowd level, accuracy | **0.896** | â€” |
| Crowd level, macro-F1 | **0.789** | 0.728 |

| Class | Precision | Recall | F1 | Support |
|---|---|---|---|---|
| LOW | 0.99 | 0.93 | 0.96 | 35,808 |
| MEDIUM | 0.75 | 0.82 | 0.79 | 10,054 |
| HIGH | 0.44 | 0.77 | 0.56 | 1,854 |
| FULL | 0.90 | 0.81 | 0.85 | 2,924 |

Confusion matrix (rows = actual, columns = predicted: LOW, MEDIUM, HIGH, FULL):

```
LOW     33280  2528     0     0
MEDIUM    504  8281  1242    27
HIGH        0   179  1425   250
FULL        0    14   544  2366
```

**Weakness:** HIGH is a narrow band (85â€“100 %) and only 3.7 % of rows, so the model over-predicts it (precision 0.44). Treat a predicted HIGH as "close to full". All figures come from **synthetic data**, so they show the method works, not real-world accuracy.

## Use in the application

- **Event-driven:** after each sensor report, the backend calls `POST /forecast`, at most once per bus per minute.
- **Scheduled:** every 5 minutes, the backend forecasts every bus whose onboard estimate is fresh (updated within 2 minutes).
- Forecasts are stored in `crowd_predictions` with `modelKind = LIVE_FORECAST`, `horizonMinutes = 15`, the predicted passenger count and the onboard value they started from.
- A bus **without** fresh live data never gets a live forecast. It keeps using Model 1, so stale or missing inputs never produce a confident-looking number.
- **Monitoring:** `GET /api/admin/forecast-performance` compares past live forecasts with the onboard estimate recorded 15 minutes later, and computes the persistence baseline for the same cases. Those "actual" values are themselves sensor estimates.
- Every model version and its metrics are copied into the `ml_models` table (at startup and hourly).


## Evaluating on real data (future work)

The models are trained and tested on **synthetic** data, so their scores are not evidence of real-world accuracy. Once real labelled observations exist (manual head counts, or camera counts validated against them), evaluate the deployed model on them, without using them for training:

```powershell
.venv\Scripts\python evaluate_real.py dataeal_observations.csv --report modelseal_eval.json
```

- **Columns:** see `data/real_eval_template.csv`, which contains only the header. Each row is `hour, day_of_week, route_distance_km, stop_count, bus_capacity` plus either an observed `crowd_level` or an observed `onboard` count. Onboard counts are converted with the backend thresholds (< 50 % LOW, < 85 % MEDIUM, else HIGH).
- **Output:** accuracy, macro-F1, per-class precision and recall, the confusion matrix, a most-frequent-class baseline, and how many rows fall outside the training range.
- **Next step:** if real accuracy is poor, retrain with `train.py` on real data. Keep a held-out set of later days for testing, never shuffled with training days.
