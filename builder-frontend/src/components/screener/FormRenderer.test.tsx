// @vitest-environment jsdom
import { render } from "solid-js/web";
import { afterEach, describe, expect, it, vi } from "vitest";
import FormRenderer from "./FormRenderer";

const schema = {
  type: "default",
  id: "Form_test",
  schemaVersion: 18,
  components: [
    {
      type: "checkbox",
      id: "cash",
      key: "custom.wantsExtraCash",
      label: "Interested in cash assistance",
    },
    { type: "textfield", id: "city", key: "custom.city", label: "City" },
    {
      type: "checkbox",
      id: "resident",
      key: "simpleChecks.livesInPhiladelphiaPa",
      label: "Philadelphia resident",
    },
  ],
};

describe("FormRenderer host prefill", () => {
  let dispose: (() => void) | undefined;
  afterEach(() => {
    dispose?.();
    document.body.innerHTML = "";
  });

  it("evaluates unknown host answers as null until the user answers them", async () => {
    const submitForm = vi.fn();
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () => (
        <FormRenderer
          schema={schema}
          formData={() => ({
            custom: { wantsExtraCash: null },
            simpleChecks: { livesInPhiladelphiaPa: false },
          })}
          hiddenQuestionPaths={() => []}
          submitForm={submitForm}
          evaluateInitialData
        />
      ),
      container,
    );
    await vi.waitFor(() => expect(submitForm).toHaveBeenCalledOnce());
    expect(submitForm).toHaveBeenLastCalledWith({
      custom: { wantsExtraCash: null, city: null },
      simpleChecks: { livesInPhiladelphiaPa: false },
    });

    const cash = container.querySelector<HTMLInputElement>(
      'input[type="checkbox"]',
    )!;
    cash.click();
    cash.click();
    await vi.waitFor(() => expect(submitForm).toHaveBeenCalledTimes(2), {
      timeout: 3000,
    });
    expect(submitForm).toHaveBeenLastCalledWith({
      custom: { wantsExtraCash: false, city: null },
      simpleChecks: { livesInPhiladelphiaPa: false },
    });
  });
});
