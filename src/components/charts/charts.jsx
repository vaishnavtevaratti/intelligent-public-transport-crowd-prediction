// Lightweight SVG charts. Crowd levels use the status colours and always have a text legend.
import { useEffect, useRef, useState } from 'react';
import { CROWD_LEVELS, CROWD_META, OCCUPANCY_LEVELS, hourLabel, percent } from '../../utils/format';

function useWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(320);
  useEffect(() => {
    if (!ref.current) return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(280, entry.contentRect.width)));
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

export function CrowdLegend() {
  return (
    <div className="legend" aria-label="Legend">
      {CROWD_LEVELS.map((level) => (
        <span key={level}>
          <span className="swatch" style={{ background: CROWD_META[level].color }} aria-hidden="true" />
          {CROWD_META[level].label} crowd
        </span>
      ))}
    </div>
  );
}

const AXIS = { top: 12, right: 8, bottom: 28, left: 40 };

/**
 * 24 bars, one per hour. Height = expected crowding score (0-100 %), colour = predicted level.
 * Best hours get a marker above the bar; the selected hour is outlined.
 */
export function HourlyForecastChart({ hours, bestHours = [], highlightHour, height = 240 }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const plotW = width - AXIS.left - AXIS.right;
  const plotH = height - AXIS.top - AXIS.bottom;
  const slot = plotW / 24;
  const barW = Math.max(4, slot - 2); // 2px gap between bars
  const y = (v) => AXIS.top + plotH * (1 - v);

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <svg width={width} height={height} role="img" aria-label="Hourly crowd forecast bar chart">
        {[0, 0.5, 1].map((t) => (
          <g key={t}>
            <line x1={AXIS.left} x2={width - AXIS.right} y1={y(t)} y2={y(t)} stroke="var(--border)" strokeWidth="1" />
            <text x={AXIS.left - 6} y={y(t) + 4} textAnchor="end" fontSize="10" fill="var(--text-3)">{t * 100}%</text>
          </g>
        ))}
        {hours.map((h) => {
          const x = AXIS.left + h.hour * slot + 1;
          const value = Math.max(h.crowdScore, 0.02);
          const top = y(value);
          const isBest = bestHours.includes(h.hour);
          return (
            <g key={h.hour}
              onMouseEnter={() => setHover(h)} onMouseLeave={() => setHover(null)}
              onFocus={() => setHover(h)} onBlur={() => setHover(null)} tabIndex={0}
              aria-label={`${hourLabel(h.hour)}: ${CROWD_META[h.crowdLevel].label} crowd, score ${percent(h.crowdScore)}`}>
              <rect x={x - 1} y={AXIS.top} width={slot} height={plotH} fill="transparent" />
              <rect x={x} y={top} width={barW} height={y(0) - top} rx={Math.min(4, barW / 2)}
                fill={CROWD_META[h.crowdLevel].color}
                opacity={hover && hover.hour !== h.hour ? 0.55 : 1}
                stroke={highlightHour === h.hour ? 'var(--text)' : 'none'} strokeWidth="2" />
              {isBest && <text x={x + barW / 2} y={top - 4} textAnchor="middle" fontSize="11" fill="var(--success-text)" fontWeight="700">★</text>}
              {h.hour % 3 === 0 && (
                <text x={x + barW / 2} y={height - 10} textAnchor="middle" fontSize="10" fill="var(--text-3)">
                  {hourLabel(h.hour).replace(' ', '')}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      {hover && (
        <div className="chart-tooltip" style={{
          left: Math.min(width - 190, Math.max(0, AXIS.left + hover.hour * slot - 60)), top: 0,
        }}>
          <strong>{hourLabel(hover.hour)}</strong> · {CROWD_META[hover.crowdLevel].label} crowd
          {bestHours.includes(hover.hour) && <span style={{ color: 'var(--success-text)' }}> · best time</span>}
          <div className="muted">Low {percent(hover.probabilities.LOW)} · Medium {percent(hover.probabilities.MEDIUM)} · High {percent(hover.probabilities.HIGH)}</div>
        </div>
      )}
      <table className="sr-only">
        <caption>Hourly crowd forecast</caption>
        <thead><tr><th>Hour</th><th>Level</th><th>Low</th><th>Medium</th><th>High</th></tr></thead>
        <tbody>
          {hours.map((h) => (
            <tr key={h.hour}><td>{hourLabel(h.hour)}</td><td>{h.crowdLevel}</td>
              <td>{percent(h.probabilities.LOW)}</td><td>{percent(h.probabilities.MEDIUM)}</td><td>{percent(h.probabilities.HIGH)}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Horizontal stacked bar of LOW / MEDIUM / HIGH counts with labelled counts beneath. */
export function CrowdDistribution({ counts }) {
  // FULL only exists for live occupancy / 15-minute forecasts; show it when it occurs.
  const levels = counts?.FULL ? OCCUPANCY_LEVELS : CROWD_LEVELS;
  const total = levels.reduce((sum, l) => sum + (counts?.[l] || 0), 0);
  return (
    <div className="stack">
      <div className="stacked-bar" role="img"
        aria-label={levels.map((l) => `${CROWD_META[l].label}: ${counts?.[l] || 0}`).join(', ')}>
        {total > 0 && levels.map((l) => (counts[l] > 0 && (
          <div key={l} style={{ width: `${(counts[l] / total) * 100}%`, background: CROWD_META[l].color }}
            title={`${CROWD_META[l].label}: ${counts[l]}`} />
        )))}
      </div>
      <div className={`grid ${levels.length === 4 ? 'grid-4' : 'grid-3'}`} style={{ gap: 8 }}>
        {levels.map((l) => (
          <div key={l} className="stack-sm" style={{ gap: 2 }}>
            <span className="row small text-2"><span className="swatch" style={{ background: CROWD_META[l].color }} />{CROWD_META[l].label}</span>
            <strong style={{ fontSize: '1.2rem' }}>{counts?.[l] || 0}</strong>
            <span className="muted small">{total ? percent((counts[l] || 0) / total) : '0%'}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** Model probabilities as three labelled bars. */
export function ProbabilityBars({ probabilities }) {
  return (
    <div className="stack-sm">
      {CROWD_LEVELS.map((l) => (
        <div key={l} className="prob-row">
          <span className="text-2">{CROWD_META[l].label}</span>
          <div className="prob-track" role="progressbar" aria-valuenow={Math.round((probabilities[l] || 0) * 100)}
            aria-valuemin={0} aria-valuemax={100} aria-label={`${CROWD_META[l].label} probability`}>
            <div className="prob-fill" style={{ width: percent(probabilities[l] || 0, 1), background: CROWD_META[l].color }} />
          </div>
          <span className="mono" style={{ textAlign: 'right' }}>{percent(probabilities[l] || 0)}</span>
        </div>
      ))}
    </div>
  );
}

/** Stacked bars per hour of day: rows = [{hour, LOW, MEDIUM, HIGH}]. */
export function HourlyStackedChart({ rows, height = 220 }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const plotW = width - AXIS.left - AXIS.right;
  const plotH = height - AXIS.top - AXIS.bottom;
  const max = Math.max(1, ...rows.map((r) => OCCUPANCY_LEVELS.reduce((s, l) => s + (r[l] || 0), 0)));
  const slot = plotW / 24;
  const barW = Math.max(4, slot - 2);
  const scale = (v) => (v / max) * plotH;
  const ticks = [0, Math.round(max / 2), max];

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <svg width={width} height={height} role="img" aria-label="Predictions per hour of day, stacked by crowd level">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={AXIS.left} x2={width - AXIS.right} y1={AXIS.top + plotH - scale(t)} y2={AXIS.top + plotH - scale(t)} stroke="var(--border)" />
            <text x={AXIS.left - 6} y={AXIS.top + plotH - scale(t) + 4} textAnchor="end" fontSize="10" fill="var(--text-3)">{t}</text>
          </g>
        ))}
        {rows.map((r) => {
          const x = AXIS.left + r.hour * slot + 1;
          let base = AXIS.top + plotH;
          return (
            <g key={r.hour} onMouseEnter={() => setHover(r)} onMouseLeave={() => setHover(null)}>
              <rect x={x - 1} y={AXIS.top} width={slot} height={plotH} fill="transparent" />
              {OCCUPANCY_LEVELS.map((l) => {
                const h = scale(r[l] || 0);
                if (h <= 0) return null;
                base -= h;
                // 2px surface gap between stacked segments
                return <rect key={l} x={x} y={base} width={barW} height={Math.max(1, h - 2)} fill={CROWD_META[l].color} rx="2" />;
              })}
              {r.hour % 3 === 0 && (
                <text x={x + barW / 2} y={height - 10} textAnchor="middle" fontSize="10" fill="var(--text-3)">{hourLabel(r.hour).replace(' ', '')}</text>
              )}
            </g>
          );
        })}
      </svg>
      {hover && (
        <div className="chart-tooltip" style={{ left: Math.min(width - 170, Math.max(0, AXIS.left + hover.hour * slot - 50)), top: 0 }}>
          <strong>{hourLabel(hover.hour)}</strong>
          <div className="muted">Low {hover.LOW} · Medium {hover.MEDIUM} · High {hover.HIGH}{hover.FULL ? ` · Full ${hover.FULL}` : ''}</div>
        </div>
      )}
    </div>
  );
}
