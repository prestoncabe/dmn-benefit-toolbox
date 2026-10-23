// @vitest-environment jsdom
import { render } from "preact";
import { afterEach, expect, it } from "vitest";
import { nextStepsEditorModule } from "./editorModule";
import { NEXT_STEPS_TYPE } from "./content";

afterEach(() => document.body.replaceChildren());

function panelFor(field: any, options: { benefits: any[]; sources: any[] }) {
  const module = nextStepsEditorModule({
    benefits: () => options.benefits,
    sources: () => options.sources,
    open: () => {},
  });
  const Provider = module.nextStepsProperties[1] as any;
  const provider = new Provider(
    { registerProvider: () => {} },
    { on: () => {} },
  );
  const [group] = provider.getGroups(field, () => {})([]);
  const container = document.body.appendChild(document.createElement("div"));
  render(group.entries[0].component(), container);
  return container;
}

it("warns about screener benefits whose content failed to load", () => {
  const container = panelFor(
    { type: NEXT_STEPS_TYPE, benefitContent: { food: {} } },
    {
      benefits: [
        { id: "food", name: "Food", description: "" },
        { id: "housing", name: "Housing", description: "" },
      ],
      // Housing's content could not be fetched.
      sources: [{ id: "food", name: "Food", description: "", checks: [] }],
    },
  );
  expect(container.querySelector('[role="alert"]')?.textContent).toContain(
    "Some benefits have no content",
  );
});

it("does not warn when every screener benefit has content", () => {
  const container = panelFor(
    { type: NEXT_STEPS_TYPE, benefitContent: { food: {} } },
    {
      benefits: [{ id: "food", name: "Food", description: "" }],
      sources: [],
    },
  );
  expect(container.querySelector('[role="alert"]')).toBeNull();
});
