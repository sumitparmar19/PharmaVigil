// The system map as an inline SVG for the landing page: the pharmacy app and partners feed intake, Kafka carries
// each report through de-duplication, MedDRA coding and triage into a case, a reviewer approves it, and the
// E2B(R3) file goes over JMS to the mock regulator; signals from public FAERS data flow back to the counter.
const boxes = [
  { x: 10, y: 20, w: 150, h: 54, title: "Pharmacy app", sub: "dispense · one-click report" },
  { x: 10, y: 110, w: 150, h: 54, title: "Partners (SOAP)", sub: "E2B(R3) + WS-Security" },
  { x: 210, y: 65, w: 150, h: 54, title: "intake-gateway", sub: "REST · SOAP · XSD check" },
  { x: 410, y: 65, w: 170, h: 54, title: "Kafka (outbox)", sub: "dedup → coder → triage" },
  { x: 630, y: 65, w: 150, h: 54, title: "case-service", sub: "ONNX models · review" },
  { x: 630, y: 170, w: 150, h: 54, title: "submission-gateway", sub: "E2B(R3) over JMS" },
  { x: 410, y: 170, w: 170, h: 54, title: "Mock regulator", sub: "schema check · ACK/NACK" },
  { x: 210, y: 170, w: 150, h: 54, title: "signal-service", sub: "PRR ROR IC EBGM · SafetyFormer" },
];

const arrows: [number, number, number, number][] = [
  [160, 47, 210, 85], [160, 137, 210, 100], [360, 92, 410, 92], [580, 92, 630, 92],
  [705, 119, 705, 170], [630, 197, 580, 197], [210, 197, 85, 75],
];

export function ArchitectureDiagram() {
  return (
    <svg viewBox="0 0 790 240" role="img" aria-label="PharmaVigil architecture: reports flow from the pharmacy app and partners through intake, Kafka and case-service to the regulator; signals flow back to the pharmacy"
      className="h-auto w-full">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#475569" />
        </marker>
      </defs>
      {arrows.map(([x1, y1, x2, y2], i) => (
        <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} stroke="#475569" strokeWidth="1.6" markerEnd="url(#arrow)" />
      ))}
      {boxes.map((b) => (
        <g key={b.title}>
          <rect x={b.x} y={b.y} width={b.w} height={b.h} rx="8" fill="#ffffff" stroke="#334155" strokeWidth="1.2" />
          <text x={b.x + b.w / 2} y={b.y + 23} textAnchor="middle" fontSize="13" fontWeight="600" fill="#0f172a">{b.title}</text>
          <text x={b.x + b.w / 2} y={b.y + 41} textAnchor="middle" fontSize="10.5" fill="#475569">{b.sub}</text>
        </g>
      ))}
      <text x="150" y="232" fontSize="10.5" fill="#475569">signals from public FAERS data back to the counter (evidence, not a verdict)</text>
    </svg>
  );
}
