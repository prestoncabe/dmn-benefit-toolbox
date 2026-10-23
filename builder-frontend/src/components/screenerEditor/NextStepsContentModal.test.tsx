// @vitest-environment jsdom
import { render } from "solid-js/web";
import { afterEach, expect, it, vi } from "vitest";
import NextStepsContentModal from "./NextStepsContentModal";

let dispose: () => void;
afterEach(() => {
  dispose?.();
  document.body.replaceChildren();
  vi.restoreAllMocks();
});
it("warns about new benefits, saves only the screener copy, and resets a benefit from its source", async () => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: () => {},
  });
  const source = {
    id: "food",
    name: "Food",
    description: "",
    checks: [],
    nextSteps: { eligible: { body: "Benefit instructions" } },
  };
  const field = {
    benefitContent: { removed: { eligible: { body: "Ignore removed" } } },
  };
  const save = vi.fn();
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => (
      <NextStepsContentModal
        benefits={() => [source]}
        sources={() => [source]}
        field={field}
        save={save}
        close={() => {}}
      />
    ),
    container,
  );
  expect(document.querySelector('[role="alert"]')?.textContent).toContain(
    "Food",
  );
  expect(document.querySelector("select")?.textContent).not.toContain(
    "removed",
  );
  const body = () =>
    document.querySelector<HTMLTextAreaElement>("#next-steps-body")!;
  body().value = "Screener-specific instructions";
  body().dispatchEvent(new Event("input", { bubbles: true }));
  document
    .querySelector("form")!
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  const editedBody = body();
  await vi.waitFor(() => expect(save).toHaveBeenCalled());
  // Saving keeps the same editor, so its status and focus survive.
  expect(body()).toBe(editedBody);
  await vi.waitFor(() =>
    expect(
      Array.from(document.querySelectorAll('[role="status"]')).map(
        (status) => status.textContent,
      ),
    ).toContain("Next steps saved."),
  );
  expect(save.mock.calls[0][0].food.eligible.body).toBe(
    "Screener-specific instructions",
  );
  expect(source.nextSteps.eligible.body).toBe("Benefit instructions");
  expect(field.benefitContent).not.toHaveProperty("food");
  expect(document.querySelector('[role="alert"]')).toBeNull();
  Array.from(document.querySelectorAll("button"))
    .find((button) => button.textContent === "Reset from benefit")!
    .click();
  expect(save.mock.lastCall![0].food).toEqual(source.nextSteps);
  expect(body().value).toBe("Benefit instructions");
});
