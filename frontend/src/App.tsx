// Root component: routes, sign-in guard and data-fetching cache. Pages need the role that their API requires;
// the signals page is open to every signed-in user; the landing and evidence pages are public.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { CaseDetailPage } from "./pages/CaseDetailPage";
import { DemoRunPage } from "./pages/DemoRunPage";
import { EvidencePage } from "./pages/EvidencePage";
import { LandingPage } from "./pages/LandingPage";
import { LoginPage } from "./pages/LoginPage";
import { PharmacyPage } from "./pages/PharmacyPage";
import { ReviewQueuePage } from "./pages/ReviewQueuePage";
import { SignalsPage } from "./pages/SignalsPage";

function RequireRole({ roles, children }: { roles: string[]; children: ReactNode }) {
  const { session, hasRole } = useAuth();
  if (!session) return <Navigate to="/login" replace />;
  if (roles.length > 0 && !hasRole(...roles)) return <Navigate to="/signals" replace />;
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/evidence" element={<EvidencePage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/demo" element={<RequireRole roles={["PHARMACIST", "SAFETY_REVIEWER", "ADMIN"]}><DemoRunPage /></RequireRole>} />
      <Route path="/pharmacy" element={<RequireRole roles={["PHARMACIST"]}><PharmacyPage /></RequireRole>} />
      <Route path="/review" element={<RequireRole roles={["SAFETY_REVIEWER", "ADMIN"]}><ReviewQueuePage /></RequireRole>} />
      <Route path="/review/:id" element={<RequireRole roles={["SAFETY_REVIEWER", "ADMIN"]}><CaseDetailPage /></RequireRole>} />
      <Route path="/signals" element={<RequireRole roles={[]}><SignalsPage /></RequireRole>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}
