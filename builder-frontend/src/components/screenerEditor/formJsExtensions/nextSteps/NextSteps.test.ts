// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { Form } from "@bpmn-io/form-js-viewer";
import { NextSteps } from "./NextSteps";
import { hasNextSteps } from "./content";
import {
  screeningResultsModule,
  ScreeningResultsService,
} from "../screeningResults/screeningResults";
import type { ScreenerResult } from "@/types";

const registration = {
  __init__: ["nextStepsField"],
  nextStepsField: [
    "type",
    class {
      static $inject = ["formFields"];
      constructor(fields: any) {
        fields.register("next_steps", NextSteps);
      }
    },
  ],
};
let form: Form;
afterEach(() => {
  form?.destroy();
  document.body.replaceChildren();
});
const benefitContent = {
  housing: {
    eligible: {
      applyUrl: "https://example.org/apply",
      helpUrl: "https://example.org/help",
      helpPhone: "+1 (215) 555-0100",
      body: "Bring **proof of residence**.\n\n- Photo ID\n- [Application guide](https://example.org/guide)",
    },
    ineligible: { note: "Ask about **other housing help**." },
  },
  removed: { eligible: { body: "Removed benefit instructions" } },
};
async function mount(showWhen = "whenDecided", content = benefitContent) {
  const container = document.body.appendChild(document.createElement("div"));
  form = new Form({
    container,
    additionalModules: [registration, screeningResultsModule],
  });
  await form.importSchema({
    id: "form",
    type: "default",
    schemaVersion: 18,
    components: [
      { id: "next", type: "next_steps", showWhen, benefitContent: content },
    ],
  });
  return {
    container,
    service: form.get<ScreeningResultsService>("screeningResults"),
  };
}
const result = (status = "TRUE"): ScreenerResult => ({
  housing: {
    name: "Housing assistance",
    result: status as any,
    check_results: {},
  },
  food: {
    name: "Food assistance",
    result: "UNABLE_TO_DETERMINE",
    check_results: {},
  },
});

it("shows decided benefits immediately, preserves answers/schema, and lists undecided benefits", async () => {
  const { container, service } = await mount();
  expect(container.querySelector("h2")).toBeNull();
  service.update(result(), false);
  await vi.waitFor(() =>
    expect(container.textContent).toContain("Your next steps so far"),
  );
  expect(container.querySelector("strong")?.textContent).toBe(
    "proof of residence",
  );
  expect(
    container.querySelector('a[href="tel:+12155550100"]')?.textContent,
  ).toBe("+1 (215) 555-0100");
  expect(container.textContent).toContain("Still needs more information");
  expect(container.textContent).not.toContain("Removed benefit instructions");
  const links = Array.from(
    container.querySelectorAll('.bdt-next-steps a[href^="https:"]'),
  );
  expect(links).toHaveLength(3);
  links.forEach((link) => {
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
  });
  expect((form as any)._getState().data).toEqual({});
});

it("uses failed check aliases with name fallback and the analyst's note", async () => {
  const { container, service } = await mount();
  const results = result("FALSE");
  results.food.result = "FALSE";
  results.housing.check_results = Object.fromEntries([
    [
      "a",
      {
        name: "Residence",
        aliasName: "Primary residence required",
        result: "FALSE",
      },
    ],
    ["b", { name: "Income limit", aliasName: " ", result: "FALSE" }],
    ["c", { name: "Not failed", result: "TRUE" }],
  ]) as any;
  service.update(results, false);
  await vi.waitFor(() =>
    expect(container.textContent).toContain(
      "Screening complete — your next steps",
    ),
  );
  expect(container.textContent).toContain("Requirements not met");
  expect(
    Array.from(container.querySelectorAll("li")).map(
      (node) => node.textContent,
    ),
  ).toEqual(["Primary residence required", "Income limit"]);
  expect(container.textContent).toContain("other housing help");
  expect(container.querySelector('a[href*="apply"]')).toBeNull();
});

it("waits for every benefit in whenComplete mode and hides stale instructions during debounce and failure", async () => {
  const { container, service } = await mount("whenComplete");
  service.update(result(), false);
  await vi.waitFor(() =>
    expect(container.textContent).toContain("Answer the remaining questions"),
  );
  expect(container.querySelector(".bdt-next-steps a")).toBeNull();
  const complete = result();
  complete.food.result = "TRUE";
  service.update(complete, false);
  await vi.waitFor(() =>
    expect(container.querySelector("h2")?.textContent).toContain(
      "Screening complete",
    ),
  );
  service.update(complete, true);
  await vi.waitFor(() =>
    expect(container.textContent).toContain("Updating your next steps"),
  );
  expect(container.querySelector(".bdt-next-steps a")).toBeNull();
  service.update(complete, true, true);
  await vi.waitFor(() =>
    expect(container.querySelector('[role="alert"]')).not.toBeNull(),
  );
  expect(container.textContent).not.toContain("Screening complete");
  service.update(complete, false, false);
  await vi.waitFor(() =>
    expect(container.querySelector(".bdt-next-steps a")).not.toBeNull(),
  );
});

it("sanitizes imported Markdown and contact links without evaluating templates", async () => {
  const { container, service } = await mount("whenDecided", {
    housing: {
      eligible: {
        applyUrl: "javascript:alert(1)",
        helpUrl: "data:text/html,evil",
        helpPhone: "javascript:alert(1)",
        body: "<script>alert(1)</script><img src=x onerror=alert(1)>[Bad](javascript:alert(1))\n\n[Good](https://example.org) {{ custom.secret }}",
      },
    },
  } as any);
  service.update(result(), false);
  await vi.waitFor(() =>
    expect(container.textContent).toContain("custom.secret"),
  );
  expect(
    container.querySelector(
      "script, [onerror], a[href^='javascript:'], a[href^='data:']",
    ),
  ).toBeNull();
  expect(
    container.querySelector("a[target='_blank']")?.getAttribute("rel"),
  ).toContain("noopener");
});

it("detects nested components for legacy completion-message compatibility", () => {
  expect(
    hasNextSteps({
      components: [{ type: "group", components: [{ type: "next_steps" }] }],
    }),
  ).toBe(true);
  expect(hasNextSteps({ components: [{ type: "text" }] })).toBe(false);
  expect(hasNextSteps(undefined)).toBeFalsy();
});
