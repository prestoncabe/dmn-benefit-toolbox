import { describe, expect, it } from "vitest";
import { titleCase } from "./title_case";

describe("titleCase", () => {
  it("formats endpoint slugs", () => {
    expect(titleCase("phl-senior-citizen-tax-freeze"))
      .toBe("Phl senior citizen tax freeze");
  });

  it("formats DMN context aliases", () => {
    expect(titleCase("NotAlreadyOnHomestead"))
      .toBe("Not Already On Homestead");
  });
});
