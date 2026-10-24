// Reports per year for one drug–event pair: a single-series column chart (thin columns, rounded data ends,
// recessive axis, hover tooltip) with a table view of the same numbers for screen readers and exact values.
import { useState } from "react";

export interface TrendPoint {
  year: number;
  reports: number;
}

const HEIGHT = 140;
const COL = 18; // column width (<= 24px)
const GAP = 14;
const PAD = { top: 16, bottom: 22, left: 8 };

export function TrendChart({ title, points }: { title: string; points: TrendPoint[] }) {
  const [hover, setHover] = useState<number | null>(null);
  if (points.length === 0) return <p className="text-sm text-slate-600">No yearly counts for this pair.</p>;
  const max = Math.max(...points.map((p) => p.reports), 1);
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const width = PAD.left * 2 + points.length * (COL + GAP);
  const active = hover === null ? null : points[hover];
  return (
    <figure className="text-sm">
      <figcaption className="mb-1 font-medium text-slate-900">{title}</figcaption>
      <div className="relative">
        <svg role="img" aria-label={`${title}: reports per year`} width={width} height={HEIGHT}
          className="overflow-visible" onMouseLeave={() => setHover(null)}>
          <line x1={0} x2={width} y1={HEIGHT - PAD.bottom} y2={HEIGHT - PAD.bottom}
            className="stroke-slate-300" strokeWidth={1} />
          {points.map((p, i) => {
            const h = Math.max(2, (p.reports / max) * plotH);
            const x = PAD.left + i * (COL + GAP) + GAP / 2;
            const y = HEIGHT - PAD.bottom - h;
            const r = Math.min(4, h / 2);
            // Rounded 4px data end at the top, square at the baseline.
            const d = `M${x},${y + h} V${y + r} Q${x},${y} ${x + r},${y} H${x + COL - r} Q${x + COL},${y} ${x + COL},${y + r} V${y + h} Z`;
            return (
              <g key={p.year}>
                <path d={d} style={{ fill: "var(--series-1)" }} opacity={hover === null || hover === i ? 1 : 0.55} />
                <text x={x + COL / 2} y={HEIGHT - 6} textAnchor="middle" className="fill-slate-500 text-[10px]">
                  {String(p.year).slice(2)}
                </text>
                {/* Hit target wider than the mark. */}
                <rect data-testid={`trend-hit-${p.year}`} x={x - GAP / 2} y={PAD.top} width={COL + GAP} height={plotH}
                  fill="transparent" onMouseEnter={() => setHover(i)} onFocus={() => setHover(i)} tabIndex={0} />
              </g>
            );
          })}
        </svg>
        {active && (
          <div role="tooltip" className="pointer-events-none absolute -top-2 left-0 rounded border bg-white px-2 py-1 text-xs shadow">
            {active.year}: <strong>{active.reports}</strong> report{active.reports === 1 ? "" : "s"}
          </div>
        )}
      </div>
      <details className="mt-1">
        <summary className="cursor-pointer text-slate-600">Table view</summary>
        <table className="mt-1 text-left">
          <thead><tr><th scope="col" className="pr-4">Year</th><th scope="col">Reports</th></tr></thead>
          <tbody>{points.map((p) => <tr key={p.year}><td className="pr-4">{p.year}</td><td>{p.reports}</td></tr>)}</tbody>
        </table>
      </details>
    </figure>
  );
}
