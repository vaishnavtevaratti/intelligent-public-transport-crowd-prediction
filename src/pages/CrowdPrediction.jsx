import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Brain, CalendarClock, Database, Info, Sparkles, Star } from 'lucide-react';
import { Alert, Card, CrowdBadge, EmptyState, ErrorState, PageHeader, SkeletonRows, Spinner } from '../components/ui/ui';
import { CrowdLegend, HourlyForecastChart, ProbabilityBars } from '../components/charts/charts';
import PredictionTable from '../components/PredictionTable';
import { useApi } from '../hooks/useApi';
import { useToast } from '../context/ToastContext';
import { busService } from '../services/busService';
import { routeService } from '../services/routeService';
import { crowdService } from '../services/crowdService';
import { getErrorMessage } from '../services/api';
import { CROWD_META, formatDateTime, hourLabel, percent, routeLabel, toLocalInputValue } from '../utils/format';

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

function ModelCard({ status, loading, error }) {
  if (loading) return <Card title="About the model"><SkeletonRows rows={4} /></Card>;
  if (error || !status?.available) {
    return (
      <Card title="About the model">
        <Alert type="error">{status?.message || error || 'The ML service is not reachable.'}</Alert>
        <p className="muted small" style={{ marginTop: 10 }}>Start it with <span className="mono">python ml-service/app.py</span>.</p>
      </Card>
    );
  }
  const d = status.details;
  return (
    <Card title="About the model" subtitle="Python scikit-learn service">
      <dl className="kv">
        <dt>Algorithm</dt><dd>{d.model_type.replace(/_/g, ' ')}</dd>
        <dt>Test accuracy</dt><dd>{percent(d.test.accuracy, 1)}</dd>
        <dt>Test macro-F1</dt><dd>{d.test.macro_f1.toFixed(3)}</dd>
        <dt>Training rows</dt><dd>{d.dataset.train_rows.toLocaleString()}</dd>
        <dt>Trained</dt><dd>{formatDateTime(d.trained_at)}</dd>
        <dt>Version</dt><dd className="mono" style={{ fontSize: '0.75rem' }}>{status.modelVersion}</dd>
      </dl>
      <div style={{ marginTop: 12 }}>
        <Alert type="warning" icon={Database}>
          Trained on a <strong>synthetic demonstration dataset</strong>, not real passenger counts. Scores show the model learned the simulated
          pattern; they do not prove real-world accuracy.
        </Alert>
      </div>
    </Card>
  );
}

export default function CrowdPrediction() {
  const toast = useToast();
  const [searchParams] = useSearchParams();
  const busesApi = useApi(() => busService.getAllBuses());
  const routesApi = useApi(() => routeService.getAllRoutes());
  const modelApi = useApi(() => crowdService.getModelStatus());

  const [chosenBusId, setBusId] = useState(searchParams.get('busId') || '');
  const [travelTime, setTravelTime] = useState(() => toLocalInputValue(new Date()));
  const [result, setResult] = useState(null);
  const [predicting, setPredicting] = useState(false);
  const [predictError, setPredictError] = useState(null);
  const [historyKey, setHistoryKey] = useState(0);

  const buses = useMemo(() => (busesApi.data || []).filter((b) => b.routeId), [busesApi.data]);
  // Default to the first bus until the user picks one.
  const busId = chosenBusId || (buses[0] ? String(buses[0].id) : '');
  const selectedBus = buses.find((b) => String(b.id) === String(busId));
  const forecastDate = travelTime.slice(0, 10);

  const forecastApi = useApi(
    () => (selectedBus ? crowdService.getForecast(selectedBus.routeId, { date: forecastDate }) : Promise.resolve(null)),
    [selectedBus?.routeId, forecastDate],
  );

  const now = new Date();
  const minTime = toLocalInputValue(new Date(now.getTime() - 24 * 3600 * 1000));
  const maxTime = toLocalInputValue(new Date(now.getTime() + 30 * 24 * 3600 * 1000));

  const handlePredict = async (event) => {
    event.preventDefault();
    setPredictError(null);
    if (!busId) { setPredictError('Choose a bus first.'); return; }
    if (!travelTime || travelTime < minTime || travelTime > maxTime) {
      setPredictError('Travel time must be between yesterday and 30 days from now.');
      return;
    }
    setPredicting(true);
    try {
      const prediction = await crowdService.predict(Number(busId), `${travelTime}:00`);
      setResult(prediction);
      setHistoryKey((k) => k + 1);
      toast.success(`Prediction saved: ${CROWD_META[prediction.crowdLevel].label} crowd`);
    } catch (err) {
      setPredictError(getErrorMessage(err));
    } finally {
      setPredicting(false);
    }
  };

  const travelDate = new Date(travelTime);
  const forecast = forecastApi.data;

  return (
    <>
      <PageHeader title="Crowd prediction" description="Ask the machine learning model how crowded a bus will be at a given time." />

      <div className="grid grid-main-side">
        <div className="stack">
          <Card title="New prediction" subtitle="Inputs: travel time (hour, weekday) and route data (length, stops) plus the bus capacity">
            {busesApi.error ? <ErrorState message={busesApi.error} onRetry={busesApi.reload} /> : busesApi.loading ? <SkeletonRows rows={2} /> : buses.length === 0 ? (
              <EmptyState title="No buses with a route" message="An administrator must assign buses to routes before crowd levels can be predicted." />
            ) : (
              <form className="form-grid" onSubmit={handlePredict}>
                <div className="field">
                  <label htmlFor="bus">Bus</label>
                  <select id="bus" className="select" value={busId} onChange={(e) => { setBusId(e.target.value); setResult(null); }}>
                    {buses.map((b) => <option key={b.id} value={b.id}>{b.busNumber} · {routeLabel(b)}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="time">Travel date &amp; time</label>
                  <input id="time" type="datetime-local" className="input" value={travelTime} min={minTime} max={maxTime}
                    onChange={(e) => setTravelTime(e.target.value)} />
                </div>
                {predictError && <div className="full"><Alert type="error">{predictError}</Alert></div>}
                <div className="full row wrap">
                  <button className="btn btn-primary" type="submit" disabled={predicting}>
                    {predicting ? <Spinner label="Predicting" /> : <Sparkles size={16} />} Predict crowd level
                  </button>
                  <button type="button" className="btn btn-ghost" onClick={() => setTravelTime(toLocalInputValue(new Date()))}>
                    <CalendarClock size={16} /> Use current time
                  </button>
                </div>
              </form>
            )}
          </Card>

          {result && (
            <Card title="Prediction result" subtitle={`Saved ${formatDateTime(result.timestamp)}`}>
              <div className="grid grid-2">
                <div className="stack">
                  <div className="row" style={{ gap: 12 }}>
                    <div className="stat-icon" style={{ width: 54, height: 54, background: `var(--crowd-${result.crowdLevel.toLowerCase()}-soft)`, color: `var(--crowd-${result.crowdLevel.toLowerCase()}-text)` }}>
                      <Brain size={26} />
                    </div>
                    <div>
                      <div className="muted small">Bus {result.busNumber} · {formatDateTime(result.predictedFor)}</div>
                      <div style={{ fontSize: '1.5rem', fontWeight: 700 }}>{CROWD_META[result.crowdLevel].label} crowd</div>
                      <CrowdBadge level={result.crowdLevel} confidence={result.confidence} />
                    </div>
                  </div>
                  <p className="text-2">{CROWD_META[result.crowdLevel].advice}</p>
                </div>
                <div className="stack">
                  <strong className="small">Model probabilities</strong>
                  <ProbabilityBars probabilities={result.probabilities} />
                  <p className="muted small">
                    <Info size={12} style={{ verticalAlign: -2 }} /> Features used: {hourLabel(travelDate.getHours())}, {DAYS[(travelDate.getDay() + 6) % 7]},
                    route {routeLabel(result)}, capacity {result.busCapacity}.
                  </p>
                </div>
              </div>
            </Card>
          )}

          <Card title="Best time to travel" subtitle={selectedBus ? `${routeLabel(selectedBus)} · ${new Date(`${forecastDate}T00:00`).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' })}` : ''}
            actions={<CrowdLegend />}>
            {!selectedBus ? <EmptyState title="Choose a bus to see its route forecast" /> : forecastApi.error ? (
              <ErrorState message={forecastApi.error} onRetry={forecastApi.reload} />
            ) : forecastApi.loading || !forecast ? <SkeletonRows rows={5} /> : (
              <div className="stack">
                <div className="row wrap" style={{ gap: 6 }}>
                  <Star size={16} color="var(--success-text)" />
                  <span>Least crowded (6 AM–10 PM):</span>
                  {forecast.bestHours.map((h) => <span key={h} className="badge badge-low">{hourLabel(h)}</span>)}
                </div>
                <HourlyForecastChart hours={forecast.hours} bestHours={forecast.bestHours} highlightHour={travelDate.getHours()} />
                <p className="muted small">
                  Bar height = expected crowding (P(Medium)×0.5 + P(High)); colour = most likely level; ★ = suggested hours.
                  Based on the model for an average bus capacity of {forecast.busCapacityUsed} on this route.
                  {forecast.history.length > 0 && ` ${forecast.history.reduce((s, h) => s + h.low + h.medium + h.high, 0)} stored predictions for this route in the last 30 days.`}
                </p>
              </div>
            )}
          </Card>
        </div>

        <div className="stack">
          <ModelCard status={modelApi.data} loading={modelApi.loading} error={modelApi.error} />
          <Card title="What the levels mean">
            <div className="stack-sm">
              {['LOW', 'MEDIUM', 'HIGH'].map((l) => (
                <div key={l} className="row" style={{ alignItems: 'flex-start' }}>
                  <CrowdBadge level={l} />
                  <span className="text-2 small">{l === 'LOW' ? 'Under 50% of seats' : l === 'MEDIUM' ? '50–85% occupied' : 'Over 85% occupied'} — {CROWD_META[l].advice}</span>
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>

      <Card title="Prediction history" subtitle="All stored predictions (on-demand and scheduled every 5 minutes)" flush>
        <PredictionTable routes={routesApi.data || []} refreshKey={historyKey} />
      </Card>
    </>
  );
}
