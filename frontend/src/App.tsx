// Root component of the PharmaVigil UI.
import { Banners } from "./components/Banners";

export default function App() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <Banners />
      <main className="p-6">
        <h1 className="text-2xl font-semibold">PharmaVigil</h1>
      </main>
    </div>
  );
}
