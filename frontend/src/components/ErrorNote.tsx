// Shows an API error the way the server explained it (problem-details detail plus any listed reasons).
import { ApiError } from "../api/client";

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  const details = error instanceof ApiError ? error.details : [];
  return (
    <div role="alert" className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-800">
      {message}
      {details.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {details.map((d) => <li key={d}>{d}</li>)}
        </ul>
      )}
    </div>
  );
}
