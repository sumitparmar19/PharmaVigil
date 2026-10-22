// Checks that the disclaimer banner always carries the required wording.
import { render, screen } from "@testing-library/react";
import { Banners, NO_CAUSATION, NOT_FOR_CLINICAL_USE, SYNTHETIC_PATIENTS } from "./Banners";

describe("Banners", () => {
  it("shows the clinical-use and causation disclaimers", () => {
    render(<Banners />);
    expect(screen.getByRole("note")).toHaveTextContent(NOT_FOR_CLINICAL_USE);
    expect(screen.getByRole("note")).toHaveTextContent(NO_CAUSATION);
  });

  it("adds the synthetic-patients note on pharmacy pages", () => {
    render(<Banners showSynthetic />);
    expect(screen.getByRole("note")).toHaveTextContent(SYNTHETIC_PATIENTS);
  });
});
