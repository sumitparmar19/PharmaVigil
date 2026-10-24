// The API client: bearer token on every call, JSON bodies, problem-details errors with their reasons.
import { ApiClient, ApiError } from "./client";

describe("ApiClient", () => {
  it("sends the token and JSON body, and parses problem details", async () => {
    const seen: RequestInit[] = [];
    const fetchImpl = (async (_: RequestInfo | URL, init?: RequestInit) => {
      seen.push(init!);
      return new Response(JSON.stringify({ status: 422, detail: "Submission rejected", reasons: ["bad XML"] }), { status: 422 });
    }) as typeof fetch;
    const api = new ApiClient(() => "tok", fetchImpl);
    const err = await api.report(1, 2, "text", null).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(422);
    expect(err.message).toBe("Submission rejected");
    expect(err.details).toEqual(["bad XML"]);
    expect((seen[0]!.headers as Record<string, string>).Authorization).toBe("Bearer tok");
    expect(JSON.parse(String(seen[0]!.body))).toEqual({ patientId: 1, drugId: 2, narrative: "text", onsetDate: null });
  });

  it("omits the header when signed out and tolerates non-JSON errors", async () => {
    const fetchImpl = (async (_: RequestInfo | URL, init?: RequestInit) => {
      expect((init!.headers as Record<string, string>).Authorization).toBeUndefined();
      return new Response("gateway down", { status: 502, statusText: "Bad Gateway" });
    }) as typeof fetch;
    await expect(new ApiClient(() => null, fetchImpl).queue()).rejects.toThrow("502 Bad Gateway");
  });
});
