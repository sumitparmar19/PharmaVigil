// Vite + Vitest configuration for the PharmaVigil frontend (React 19, Tailwind 4, jsdom tests).
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Dev server: same-origin /api paths go to the service that owns them (the nginx image does the same).
  server: {
    port: 5173,
    proxy: {
      "/api/cases": "http://localhost:8083",
      "/api/signals": "http://localhost:8084",
      "/api/submissions": "http://localhost:8085",
      "/api/intake": "http://localhost:8082",
      "/api": "http://localhost:8081",
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
