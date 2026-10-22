// @vitest-environment jsdom
import { createRoot, createSignal } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import BenefitResource from "./benefitResource";
import {
  addCheckToBenefit,
  fetchScreenerBenefit,
  updateScreenerBenefit,
} from "@/api/benefit";
import type { Benefit } from "@/types";

vi.mock("@/api/benefit", () => ({
  fetchScreenerBenefit: vi.fn(),
  updateScreenerBenefit: vi.fn(),
  addCheckToBenefit: vi.fn(),
  removeCheckFromBenefit: vi.fn(),
  updateCheckParameters: vi.fn(),
  updateCheckAlias: vi.fn(),
  generateCheckAlias: vi.fn(),
}));

describe("benefit next-steps updates", () => {
  let dispose: (() => void) | undefined;
  afterEach(() => {
    dispose?.();
    vi.clearAllMocks();
  });

  function setup() {
    const benefit: Benefit = {
      id: "benefit-1",
      name: "Benefit",
      description: "Description",
      checks: [],
    };
    vi.mocked(fetchScreenerBenefit).mockImplementation(
      async (_screenerId, id) => ({ ...benefit, id }),
    );
    let resource!: ReturnType<typeof BenefitResource>;
    let switchBenefit!: (id: string) => void;
    createRoot((cleanup) => {
      dispose = cleanup;
      const [id, setId] = createSignal(benefit.id);
      switchBenefit = setId;
      resource = BenefitResource(() => "screener-1", id);
    });
    return { resource, switchBenefit, benefit };
  }

  it("patches only next steps and keeps the returned benefit in sync", async () => {
    const s = setup();
    await vi.waitFor(() => expect(s.resource.benefit()?.id).toBe("benefit-1"));
    const content = {
      eligible: { body: "Apply here" },
      ineligible: { note: null },
    };
    vi.mocked(updateScreenerBenefit).mockResolvedValue({
      ...s.benefit,
      checks: [],
      nextSteps: content,
    });
    const checks = s.resource.benefit().checks;
    await s.resource.actions.updateNextSteps(content);
    expect(s.resource.benefit().checks).toBe(checks);
    expect(updateScreenerBenefit).toHaveBeenCalledWith(
      "screener-1",
      "benefit-1",
      { nextSteps: content },
    );
    expect(s.resource.benefit().nextSteps).toEqual(content);
    expect(s.resource.actionInProgress()).toBe(false);
    expect(fetchScreenerBenefit).toHaveBeenCalledTimes(1);
  });

  it("keeps saved next steps when an older refetch resolves afterwards", async () => {
    const s = setup();
    await vi.waitFor(() => expect(s.resource.benefit()?.id).toBe("benefit-1"));
    vi.mocked(addCheckToBenefit).mockResolvedValue({ aliasGenerated: true });
    let finishFetch!: (benefit: Benefit) => void;
    vi.mocked(fetchScreenerBenefit).mockReturnValueOnce(
      new Promise((resolve) => {
        finishFetch = resolve;
      }),
    );
    const adding = s.resource.actions.addCheck("check-1", {});
    await vi.waitFor(() =>
      expect(fetchScreenerBenefit).toHaveBeenCalledTimes(2),
    );
    const content = { eligible: { body: "Saved" }, ineligible: null };
    vi.mocked(updateScreenerBenefit).mockResolvedValue({
      ...s.benefit,
      nextSteps: content,
    });
    await s.resource.actions.updateNextSteps(content);
    finishFetch({
      ...s.benefit,
      name: "Refetched",
      nextSteps: { eligible: { body: "Old" } },
    });
    await adding;
    expect(s.resource.benefit().name).toBe("Refetched");
    expect(s.resource.benefit().nextSteps).toEqual(content);
  });

  it("propagates save failures without discarding saved benefit data", async () => {
    const s = setup();
    await vi.waitFor(() => expect(s.resource.benefit()?.id).toBe("benefit-1"));
    vi.mocked(updateScreenerBenefit).mockRejectedValueOnce(
      new Error("Offline"),
    );
    await expect(s.resource.actions.updateNextSteps({})).rejects.toThrow(
      "Offline",
    );
    expect(s.resource.benefit().name).toBe("Benefit");
    expect(s.resource.actionInProgress()).toBe(false);
  });

  it("does not replace a different benefit when an earlier save finishes", async () => {
    const s = setup();
    await vi.waitFor(() => expect(s.resource.benefit()?.id).toBe("benefit-1"));
    let finish!: (benefit: Benefit) => void;
    vi.mocked(updateScreenerBenefit).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const saving = s.resource.actions.updateNextSteps({});
    s.switchBenefit("benefit-2");
    await vi.waitFor(() => expect(s.resource.benefit()?.id).toBe("benefit-2"));
    finish(s.benefit);
    await saving;
    expect(s.resource.benefit().id).toBe("benefit-2");
  });
});
