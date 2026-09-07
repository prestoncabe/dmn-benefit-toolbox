import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/auth", () => ({
  authGet: vi.fn(),
  authPost: vi.fn(),
}));

import { authGet, authPost } from "@/api/auth";
import { fetchLibraryBenefits } from "./benefit";
import { importLibraryBenefit } from "./screener";

describe("library benefit API", () => {
  beforeEach(() => vi.clearAllMocks());

  it("fetches available library benefits", async () => {
    const benefits = [
      {
        id: "benefit-1",
        name: "Benefit",
        description: "Description",
        checks: [],
      },
    ];
    vi.mocked(authGet).mockResolvedValue(
      new Response(JSON.stringify(benefits), { status: 200 }),
    );

    await expect(fetchLibraryBenefits()).resolves.toEqual(benefits);
    expect(authGet).toHaveBeenCalledWith(
      expect.stringContaining("/library-benefits"),
    );
  });

  it("posts the selected library benefit to the screener import endpoint", async () => {
    vi.mocked(authPost).mockResolvedValue(new Response(null, { status: 200 }));

    await importLibraryBenefit("screener-1", "benefit-1");

    expect(authPost).toHaveBeenCalledWith(
      expect.stringContaining("/screener/screener-1/benefit/import"),
      { benefitId: "benefit-1" },
    );
  });

  it("reports an import failure", async () => {
    vi.mocked(authPost).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(
      importLibraryBenefit("screener-1", "benefit-1"),
    ).rejects.toThrow("Import benefit failed with status: 500");
  });
});
