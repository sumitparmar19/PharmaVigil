// The trend chart: one column per year with a hover tooltip, and a table view with the exact numbers.
import { fireEvent, render, screen } from "@testing-library/react";
import { TrendChart } from "./TrendChart";

describe("TrendChart", () => {
  it("draws a column per year and a tooltip on hover", () => {
    render(<TrendChart title="drug1 – rash" points={[{ year: 2020, reports: 3 }, { year: 2021, reports: 9 }]} />);
    expect(screen.getByRole("img", { name: "drug1 – rash: reports per year" })).toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByTestId("trend-hit-2021"));
    expect(screen.getByRole("tooltip")).toHaveTextContent("2021: 9 reports");
  });

  it("says when there is nothing to draw", () => {
    render(<TrendChart title="t" points={[]} />);
    expect(screen.getByText("No yearly counts for this pair.")).toBeInTheDocument();
  });
});
