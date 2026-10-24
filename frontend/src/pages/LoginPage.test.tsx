// Sign-in: credentials go to pharmacy-service, each role lands on its own page, failures are explained, and the
// disclaimers are shown before anyone signs in.
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NOT_FOR_CLINICAL_USE, SYNTHETIC_PATIENTS } from "../components/Banners";
import { pharmacist, renderApp, reviewer, scriptedFetch } from "../test/harness";

async function signIn(user = "pharm", password = "a-long-demo-password") {
  await userEvent.type(screen.getByLabelText("Username"), user);
  await userEvent.type(screen.getByLabelText("Password"), password);
  await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

describe("LoginPage", () => {
  it("shows the disclaimers before sign-in", () => {
    renderApp("/login", scriptedFetch({}).impl, null);
    expect(screen.getByRole("note")).toHaveTextContent(NOT_FOR_CLINICAL_USE);
    expect(screen.getByRole("note")).toHaveTextContent(SYNTHETIC_PATIENTS);
  });

  it("sends the credentials and takes a pharmacist to the counter", async () => {
    const f = scriptedFetch({ "POST /api/auth/login": pharmacist, "GET /api/patients": [], "GET /api/drugs": [] });
    renderApp("/login", f.impl, null);
    await signIn();
    expect(await screen.findByRole("heading", { name: "Pharmacy counter" })).toBeInTheDocument();
    expect(f.calls[0]).toMatchObject({ method: "POST", path: "/api/auth/login", body: { username: "pharm" } });
  });

  it("takes a reviewer to the queue", async () => {
    const f = scriptedFetch({ "POST /api/auth/login": reviewer, "GET /api/cases/queue": [] });
    renderApp("/login", f.impl, null);
    await signIn("rev");
    expect(await screen.findByRole("heading", { name: "Review queue" })).toBeInTheDocument();
  });

  it("explains a failed sign-in and stays on the form", async () => {
    const f = scriptedFetch({
      "POST /api/auth/login": () => ({ status: 401, body: { status: 401, detail: "Invalid username or password." } }),
    });
    renderApp("/login", f.impl, null);
    await signIn("x", "wrong-password-123");
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid username or password.");
    expect(screen.getByRole("heading", { name: "Sign in to PharmaVigil" })).toBeInTheDocument();
  });

  it("sends signed-out users from a protected page to sign-in", async () => {
    renderApp("/review", scriptedFetch({}).impl, null);
    await waitFor(() => expect(screen.getByRole("heading", { name: "Sign in to PharmaVigil" })).toBeInTheDocument());
  });
});
