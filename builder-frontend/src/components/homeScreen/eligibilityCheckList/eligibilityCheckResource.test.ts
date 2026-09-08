import { createRoot } from "solid-js";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/check", () => ({
  addCheck: vi.fn(),
  archiveCheck: vi.fn(),
  restoreCheck: vi.fn(),
  fetchUserDefinedChecks: vi.fn().mockResolvedValue([]),
}));

vi.mock("solid-toast", () => ({
  default: { success: vi.fn(), error: vi.fn() },
}));

import { addCheck, fetchUserDefinedChecks, restoreCheck } from "@/api/check";
import eligibilityCheckResource from "./eligibilityCheckResource";
import type { EligibilityCheck } from "@/types";

describe("eligibilityCheckResource", () => {
  beforeEach(() => vi.clearAllMocks());

  it("propagates create failures to the modal", async () => {
    const failure = new Error("That check name is already in use.");
    vi.mocked(addCheck).mockRejectedValue(failure);

    await new Promise<void>((resolve, reject) => {
      createRoot((dispose) => {
        const resource = eligibilityCheckResource();
        resource.actions
          .addNewCheck({
            name: "incomeCheck",
            module: "income",
            description: "Checks income",
            parameterDefinitions: [],
          })
          .then(() => reject(new Error("Expected check creation to fail")))
          .catch((error) => {
            try {
              expect(error).toBe(failure);
              expect(resource.actionInProgress()).toBe(false);
              resolve();
            } catch (assertionError) {
              reject(assertionError);
            } finally {
              dispose();
            }
          });
      });
    });
  });

  it("splits one response into the active and archived lists", async () => {
    const active = { id: "active-id", isArchived: false };
    const archived = { id: "archived-id", isArchived: true };
    vi.mocked(fetchUserDefinedChecks).mockResolvedValue([
      active,
      archived,
    ] as unknown as EligibilityCheck[]);

    await new Promise<void>((resolve, reject) => {
      createRoot((dispose) => {
        const resource = eligibilityCheckResource();
        queueMicrotask(() => {
          try {
            expect(fetchUserDefinedChecks).toHaveBeenCalledTimes(1);
            expect(resource.checks()).toEqual([active]);
            expect(resource.archivedChecks()).toEqual([archived]);
            resolve();
          } catch (assertionError) {
            reject(assertionError);
          } finally {
            dispose();
          }
        });
      });
    });
  });

  it("survives a failed fetch instead of throwing out of the effect", async () => {
    vi.mocked(fetchUserDefinedChecks).mockRejectedValue(
      new Error("Fetch failed with status: 500"),
    );

    await new Promise<void>((resolve, reject) => {
      createRoot((dispose) => {
        const resource = eligibilityCheckResource();
        queueMicrotask(() => {
          try {
            expect(resource.checks()).toEqual([]);
            expect(resource.archivedChecks()).toEqual([]);
            expect(resource.initialLoadStatus.error()).toBeInstanceOf(Error);
            resolve();
          } catch (assertionError) {
            reject(assertionError);
          } finally {
            dispose();
          }
        });
      });
    });
  });

  it("restores a check and refreshes the check list", async () => {
    await new Promise<void>((resolve, reject) => {
      createRoot((dispose) => {
        const resource = eligibilityCheckResource();
        resource.actions
          .restoreCheck("archived-check-id")
          .then(() => {
            try {
              expect(restoreCheck).toHaveBeenCalledWith("archived-check-id");
              expect(fetchUserDefinedChecks).toHaveBeenCalledWith({
                working: true,
                includeArchived: true,
              });
              expect(resource.actionInProgress()).toBe(false);
              resolve();
            } catch (assertionError) {
              reject(assertionError);
            } finally {
              dispose();
            }
          })
          .catch(reject);
      });
    });
  });
});
