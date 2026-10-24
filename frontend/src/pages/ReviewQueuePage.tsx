// The safety reviewer's work queue: serious cases first, then unknown, then non-serious; within each, the nearest
// regulatory deadline (15 days for serious, 90 otherwise) first. The clock shows days left.
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import type { QueueItem } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { ErrorNote } from "../components/ErrorNote";
import { Layout } from "../components/Layout";

export function daysLeft(dueDate: string, today: Date = new Date()): number {
  const due = new Date(`${dueDate}T00:00:00Z`).getTime();
  const start = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
  return Math.round((due - start) / 86_400_000);
}

const seriousnessStyle: Record<QueueItem["seriousness"], string> = {
  SERIOUS: "bg-red-100 text-red-800",
  UNKNOWN: "bg-slate-200 text-slate-800",
  NON_SERIOUS: "bg-green-100 text-green-800",
};

function Clock({ dueDate, today }: { dueDate: string; today?: Date }) {
  const d = daysLeft(dueDate, today);
  const style = d < 0 ? "text-red-700 font-semibold" : d <= 3 ? "text-amber-700 font-semibold" : "text-slate-700";
  const text = d < 0 ? `${-d} day${d === -1 ? "" : "s"} overdue` : d === 0 ? "due today" : `${d} day${d === 1 ? "" : "s"} left`;
  return <span className={style}>{text}</span>;
}

export function ReviewQueuePage({ today }: { today?: Date }) {
  const { api } = useAuth();
  const queue = useQuery({ queryKey: ["queue"], queryFn: () => api.queue(), refetchInterval: 15_000 });
  return (
    <Layout>
      <h1 className="mb-4 text-2xl font-semibold">Review queue</h1>
      <ErrorNote error={queue.error} />
      {queue.isLoading && <p>Loading…</p>}
      {queue.data && queue.data.length === 0 && <p>No cases are waiting for review.</p>}
      {queue.data && queue.data.length > 0 && (
        <table className="w-full rounded-lg border bg-white text-left text-sm">
          <thead className="bg-slate-100">
            <tr>
              <th scope="col" className="px-3 py-2">Case</th>
              <th scope="col" className="px-3 py-2">Seriousness</th>
              <th scope="col" className="px-3 py-2">Status</th>
              <th scope="col" className="px-3 py-2">Received</th>
              <th scope="col" className="px-3 py-2">Deadline</th>
              <th scope="col" className="px-3 py-2">Flags</th>
            </tr>
          </thead>
          <tbody>
            {queue.data.map((c) => (
              <tr key={c.id} className="border-t">
                <td className="px-3 py-2"><Link className="text-blue-700 underline" to={`/review/${c.id}`}>{c.caseNumber}</Link></td>
                <td className="px-3 py-2">
                  <span className={`rounded px-2 py-0.5 ${seriousnessStyle[c.seriousness]}`}>
                    {c.seriousness.replace("_", "-").toLowerCase()}
                  </span>
                  {c.seriousScore !== null && <span className="ml-2 text-slate-500">model {c.seriousScore.toFixed(2)}</span>}
                </td>
                <td className="px-3 py-2">{c.status.replaceAll("_", " ").toLowerCase()}</td>
                <td className="px-3 py-2">{c.receivedDate}</td>
                <td className="px-3 py-2"><Clock dueDate={c.dueDate} today={today} /></td>
                <td className="px-3 py-2">{c.duplicateOfId !== null && <span>possible duplicate of #{c.duplicateOfId}</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Layout>
  );
}
