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

  it("restores a check and refreshes both active and archived lists", async () => {
    await new Promise<void>((resolve, reject) => {
      createRoot((dispose) => {
        const resource = eligibilityCheckResource();
        resource.actions
          .restoreCheck("archived-check-id")
          .then(() => {
            try {
              expect(restoreCheck).toHaveBeenCalledWith("archived-check-id");
              expect(fetchUserDefinedChecks).toHaveBeenCalledWith(true);
              expect(fetchUserDefinedChecks).toHaveBeenCalledWith(true, true);
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
