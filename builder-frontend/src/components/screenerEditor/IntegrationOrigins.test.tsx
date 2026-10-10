// @vitest-environment jsdom
import { render } from "solid-js/web";
import { afterEach, describe, expect, it, vi } from "vitest";
import IntegrationOrigins from "./IntegrationOrigins";
import { updateScreener } from "@/api/screener";

vi.mock("@/api/screener", () => ({ updateScreener: vi.fn() }));

describe("IntegrationOrigins", () => {
  let dispose: (() => void) | undefined;
  afterEach(() => {
    dispose?.();
    document.body.innerHTML = "";
  });

  function setup(origins?: string[]) {
    const onSaved = vi.fn();
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () => (
        <IntegrationOrigins
          screenerId="screener-1"
          origins={() => origins}
          onSaved={onSaved}
        />
      ),
      container,
    );
    const textarea = container.querySelector("textarea")!;
    const save = () =>
      Array.from(container.querySelectorAll("button"))
        .find((button) => button.textContent === "Save CRM origins")!
        .click();
    return { container, textarea, save, onSaved };
  }

  it("saves one origin per line and shows the saved origins", async () => {
    vi.mocked(updateScreener).mockResolvedValueOnce({
      integrationOrigins: ["https://crm.example"],
    });
    const s = setup(["https://old.example"]);
    expect(s.textarea.value).toBe("https://old.example");
    s.textarea.value = "HTTPS://CRM.example/\n";
    s.textarea.dispatchEvent(new Event("input", { bubbles: true }));
    s.save();
    await vi.waitFor(() =>
      expect(s.onSaved).toHaveBeenCalledWith(["https://crm.example"]),
    );
    expect(updateScreener).toHaveBeenCalledWith("screener-1", {
      integrationOrigins: ["HTTPS://CRM.example/", ""],
    });
    expect(s.textarea.value).toBe("https://crm.example");
    expect(s.container.textContent).toContain("Publish the screener to apply");
  });

  it("shows the server's validation error", async () => {
    vi.mocked(updateScreener).mockRejectedValueOnce(
      new Error("Enter each CRM origin as https://host or https://host:port"),
    );
    const s = setup();
    s.save();
    await vi.waitFor(() =>
      expect(
        s.container.querySelector('[role="alert"]')?.textContent,
      ).toContain("Enter each CRM origin"),
    );
    expect(s.onSaved).not.toHaveBeenCalled();
  });
});
