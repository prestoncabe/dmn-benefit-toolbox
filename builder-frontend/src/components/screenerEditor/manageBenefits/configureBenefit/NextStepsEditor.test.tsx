// @vitest-environment jsdom
import { createSignal } from "solid-js";
import { render } from "solid-js/web";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Benefit, NextSteps } from "@/types";
import { ApiError } from "@/api/check";
import NextStepsEditor from "./NextStepsEditor";
import { normalizeHttpUrl } from "@/utils/httpUrl";

describe("NextStepsEditor", () => {
  let dispose: (() => void) | undefined;
  afterEach(() => {
    dispose?.();
    document.body.innerHTML = "";
  });

  function setup(nextSteps?: NextSteps | null) {
    const [benefit, setBenefit] = createSignal<Benefit>({
      id: "benefit-1",
      name: "Benefit",
      description: "",
      checks: [],
      nextSteps,
    });
    const save = vi.fn().mockResolvedValue(undefined);
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () => <NextStepsEditor benefit={benefit} save={save} />,
      container,
    );
    const field = (key: string) =>
      container.querySelector<HTMLInputElement | HTMLTextAreaElement>(
        `#next-steps-${key}`,
      )!;
    const edit = (key: string, value: string) => {
      field(key).value = value;
      field(key).dispatchEvent(new Event("input", { bubbles: true }));
    };
    const submit = () =>
      container
        .querySelector("form")!
        .dispatchEvent(
          new Event("submit", { bubbles: true, cancelable: true }),
        );
    return { container, benefit, setBenefit, save, field, edit, submit };
  }

  it("edits legacy benefits and preserves Markdown while trimming contacts", async () => {
    const s = setup();
    expect(s.field("applyUrl").value).toBe("");
    s.edit("applyUrl", " https://example.org/apply ");
    s.edit("body", "Instructions\n\n- Bring ID\n");
    s.edit("note", "Ask about **other programs**.");
    s.submit();
    await vi.waitFor(() =>
      expect(s.save).toHaveBeenCalledWith({
        eligible: {
          applyUrl: "https://example.org/apply",
          helpUrl: null,
          helpPhone: null,
          body: "Instructions\n\n- Bring ID\n",
        },
        ineligible: { note: "Ask about **other programs**." },
      }),
    );
    expect(s.container.querySelector('[role="status"]')?.textContent).toBe(
      "Next steps saved.",
    );
  });

  it.each([
    "javascript:alert(1)",
    "ftp://example.org",
    "example.org",
    "https://",
    "https://www.phila.gov@evil.example/apply",
  ])("rejects invalid or unsafe URLs: %s", (url) => {
    const s = setup();
    s.edit("helpUrl", url);
    s.submit();
    expect(s.save).not.toHaveBeenCalled();
    expect(s.container.querySelector('[role="alert"]')?.textContent).toContain(
      "Help URL: use a full http:// or https:// URL.",
    );
  });

  it("rejects hosts the server cannot parse", () => {
    const s = setup();
    s.edit("applyUrl", "https://my_site.example.org/");
    s.submit();
    expect(s.save).not.toHaveBeenCalled();
    expect(s.container.querySelector('[role="alert"]')?.textContent).toContain(
      "Application URL: use a full http:// or https:// URL.",
    );
  });

  it.each([
    [
      "https://example.org/apply?q=one#form",
      "https://example.org/apply?q=one#form",
    ],
    ["https://example.org/my form", "https://example.org/my%20form"],
    ["https://example.org/apply?a|b", "https://example.org/apply?a%7Cb"],
    ["https://example.org/%zz", "https://example.org/%25zz"],
    ["https://example.org/a#b#c", "https://example.org/a#b%23c"],
    ["https://bücher.de/", "https://xn--bcher-kva.de/"],
    ["http://localhost:8080/apply", "http://localhost:8080/apply"],
  ])("normalizes %s to a URL the server accepts", (input, expected) => {
    expect(normalizeHttpUrl(input)).toBe(expected);
  });

  it("sends normalized URLs", async () => {
    const s = setup();
    s.edit("helpUrl", "https://example.org/my form");
    s.submit();
    await vi.waitFor(() =>
      expect(s.save).toHaveBeenCalledWith(
        expect.objectContaining({
          eligible: expect.objectContaining({
            helpUrl: "https://example.org/my%20form",
          }),
        }),
      ),
    );
  });

  it("clears previously populated contacts and notes using explicit nulls", async () => {
    const s = setup({
      eligible: {
        applyUrl: "https://example.org/apply",
        helpPhone: "215-555-0100",
      },
      ineligible: { note: "A note" },
    });
    expect(s.field("applyUrl").value).toBe("https://example.org/apply");
    s.edit("applyUrl", "");
    s.edit("helpPhone", " ");
    s.edit("note", "");
    s.submit();
    await vi.waitFor(() =>
      expect(s.save).toHaveBeenCalledWith({
        eligible: {
          applyUrl: null,
          helpUrl: null,
          helpPhone: null,
          body: null,
        },
        ineligible: { note: null },
      }),
    );
  });

  it("keeps edits after a failed save and allows retry", async () => {
    const s = setup();
    s.save.mockRejectedValueOnce(new Error("Offline"));
    s.edit("body", "Unsaved instructions");
    s.submit();
    await vi.waitFor(() =>
      expect(
        s.container.querySelector('[role="alert"]')?.textContent,
      ).toContain("Your edits are still here"),
    );
    expect(s.field("body").value).toBe("Unsaved instructions");
    s.submit();
    await vi.waitFor(() =>
      expect(s.container.querySelector('[role="status"]')?.textContent).toBe(
        "Next steps saved.",
      ),
    );
  });

  it("shows the server's reason when it rejects the content", async () => {
    const s = setup();
    s.save.mockRejectedValueOnce(
      new ApiError("Help URL: use a full http:// or https:// URL.", 400),
    );
    s.edit("helpUrl", "https://example.org/help");
    s.submit();
    await vi.waitFor(() =>
      expect(s.container.querySelector('[role="alert"]')?.textContent).toBe(
        "Could not save next steps. Help URL: use a full http:// or https:// URL.",
      ),
    );
    expect(s.field("helpUrl").value).toBe("https://example.org/help");
  });

  it("preserves edits when checks refetch", () => {
    const s = setup({ eligible: null, ineligible: null });
    s.edit("body", "Unsaved instructions");
    s.setBenefit({
      ...s.benefit(),
      checks: [],
      nextSteps: { eligible: { body: "Refetched" } },
    });
    expect(s.field("body").value).toBe("Unsaved instructions");
  });

  it("prevents duplicate saves while a request is pending", async () => {
    const s = setup();
    let finish!: () => void;
    s.save.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    );
    s.submit();
    s.submit();
    expect(s.save).toHaveBeenCalledTimes(1);
    expect(s.container.querySelector("fieldset")!.disabled).toBe(true);
    finish();
    await vi.waitFor(() =>
      expect(s.container.querySelector("fieldset")!.disabled).toBe(false),
    );
  });
});
