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
  // Saving keeps the same editor, so focus survives, and one status reports it.
  expect(body()).toBe(editedBody);
  await vi.waitFor(() =>
    expect(
      Array.from(document.querySelectorAll('[role="status"]')).map((status) =>
        status.textContent?.trim(),
      ),
    ).toEqual([
      "Component content updated. Save the form to keep your changes.",
    ]),
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

it("asks before discarding unsaved edits when switching benefits or closing", async () => {
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: () => {},
  });
  const benefits = [
    { id: "food", name: "Food", description: "", checks: [] },
    { id: "housing", name: "Housing", description: "", checks: [] },
  ];
  const close = vi.fn();
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => (
      <NextStepsContentModal
        benefits={() => benefits}
        sources={() => benefits}
        field={{ benefitContent: {} }}
        save={() => {}}
        close={close}
      />
    ),
    container,
  );
  const body = () =>
    document.querySelector<HTMLTextAreaElement>("#next-steps-body")!;
  const select = document.querySelector<HTMLSelectElement>("select")!;
  const closeButton = Array.from(document.querySelectorAll("button")).find(
    (button) => button.textContent?.trim() === "Close",
  )!;
  const choose = (id: string) => {
    select.value = id;
    select.dispatchEvent(new Event("change", { bubbles: true }));
  };

  choose("housing");
  closeButton.click();
  expect(confirm).not.toHaveBeenCalled();
  expect(close).toHaveBeenCalledTimes(1);

  body().value = "Unsaved instructions";
  body().dispatchEvent(new Event("input", { bubbles: true }));
  choose("food");
  closeButton.click();
  expect(confirm).toHaveBeenCalledTimes(2);
  expect(select.value).toBe("housing");
  expect(body().value).toBe("Unsaved instructions");
  expect(close).toHaveBeenCalledTimes(1);

  confirm.mockReturnValue(true);
  choose("food");
  expect(select.value).toBe("food");
  expect(body().value).toBe("");
  closeButton.click();
  expect(confirm).toHaveBeenCalledTimes(3);
  expect(close).toHaveBeenCalledTimes(2);
});
