// The domain disclaimers shown on every page (BUILD_INSTRUCTIONS §5.8).
export const NOT_FOR_CLINICAL_USE = "Not for clinical or regulatory use.";
export const NO_CAUSATION = "Spontaneous reports do not prove that a drug caused a reaction.";
export const SYNTHETIC_PATIENTS = "Synthetic patients only.";

export function Banners({ showSynthetic = false }: { showSynthetic?: boolean }) {
  return (
    <div role="note" aria-label="Disclaimers" className="bg-amber-100 text-amber-900 text-sm px-4 py-2">
      <strong>{NOT_FOR_CLINICAL_USE}</strong> {NO_CAUSATION}
      {showSynthetic && <> {SYNTHETIC_PATIENTS}</>}
    </div>
  );
}
