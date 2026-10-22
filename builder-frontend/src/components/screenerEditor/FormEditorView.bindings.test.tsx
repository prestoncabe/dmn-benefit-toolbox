// @vitest-environment jsdom

import { createSignal } from "solid-js";
import { render } from "solid-js/web";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { extractFormPaths } from "@/utils/formSchemaUtils";

const editor = vi.hoisted(() => ({ instance: undefined as any }));
vi.mock("@solidjs/router", () => ({
  useParams: () => ({ screenerId: "screener-1" }),
}));
vi.mock("@bpmn-io/form-js-editor", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("@bpmn-io/form-js-editor")>();
  return {
    ...original,
    FormEditor: class extends original.FormEditor {
      constructor(options: any) {
        super(options);
        editor.instance = this;
      }
    },
  };
});
vi.mock("@/api/screener", () => ({
  draftFormSchema: vi.fn(),
  saveFormSchema: vi.fn(),
  fetchFormPaths: vi.fn(),
}));
vi.mock("@/api/benefit", () => ({
  fetchScreenerBenefit: vi.fn(async (_screenerId, id) => ({
    id,
    name: id,
    checks: [],
  })),
}));

import {
  draftFormSchema,
  saveFormSchema,
  fetchFormPaths,
} from "@/api/screener";
import FormEditorView from "./FormEditorView";
import { fetchScreenerBenefit } from "@/api/benefit";

let dispose: (() => void) | undefined;
beforeEach(() => {
  editor.instance = undefined;
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterEach(async () => {
  await act(async () => {});
  dispose?.();
  document.body.replaceChildren();
  vi.clearAllMocks();
});
// form-js has deferred Preact effects; keep the DOM shim for this file's environment.

it("preserves saved component content after a failed source load and retry", async () => {
  vi.mocked(fetchFormPaths).mockResolvedValue({ paths: [] });
  const benefit = {
    id: "housing",
    name: "Housing",
    description: "",
    checks: [],
    nextSteps: { eligible: { body: "New source instructions" } },
  };
  vi.mocked(fetchScreenerBenefit)
    .mockRejectedValueOnce(new Error("Offline"))
    .mockResolvedValueOnce(benefit);
  const [schema, setSchema] = createSignal<any>({
    id: "form",
    type: "default",
    schemaVersion: 18,
    components: [
      {
        id: "next",
        type: "next_steps",
        benefitContent: { housing: { eligible: { body: "Published copy" } } },
      },
    ],
  });
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => (
      <FormEditorView
        formSchema={schema}
        setFormSchema={setSchema}
        benefits={() => [benefit]}
      />
    ),
    container,
  );
  await vi.waitFor(() =>
    expect(container.querySelector('[role="alert"]')?.textContent).toContain(
      "could not be loaded",
    ),
  );
  expect(
    editor.instance.saveSchema().components[0].benefitContent.housing.eligible
      .body,
  ).toBe("Published copy");
  Array.from(container.querySelectorAll("button"))
    .find((button) => button.textContent === "Try again")!
    .click();
  await vi.waitFor(() =>
    expect(container.querySelector('[role="alert"]')).toBeNull(),
  );
  expect(
    editor.instance.saveSchema().components[0].benefitContent.housing.eligible
      .body,
  ).toBe("Published copy");
});

it("keeps every required binding after the actual editor finishes importing and saving a draft", async () => {
  const paths = [
    { path: "simpleChecks.resident", type: "boolean" },
    { path: "people.client.enrollments", type: "array:string" },
    { path: "people.client.dateOfBirth", type: "date" },
    { path: "custom.income", type: "number" },
  ];
  const draft = {
    id: "BDT_Form",
    type: "default",
    schemaVersion: 18,
    components: [
      {
        id: "Field_draft_0",
        key: paths[0].path,
        label: "Do you live here?",
        type: "yes_no",
      },
      {
        id: "Field_draft_1",
        key: paths[1].path,
        label: "Your benefits",
        type: "checklist_none",
        values: [{ label: "Housing", value: "Housing" }],
      },
      {
        id: "Field_draft_2",
        key: paths[2].path,
        label: "Your date of birth",
        type: "datetime",
        subtype: "date",
        dateLabel: "Your date of birth",
      },
      {
        id: "Field_draft_3",
        key: paths[3].path,
        label: "Your income",
        type: "number",
      },
    ],
  };
  vi.mocked(fetchFormPaths).mockResolvedValue({ paths });
  vi.mocked(draftFormSchema).mockResolvedValue(draft);
  const [schema, setSchema] = createSignal<any>();
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => <FormEditorView formSchema={schema} setFormSchema={setSchema} />,
    container,
  );
  const draftButton = () =>
    Array.from(container.querySelectorAll("button")).find(
      (button) => button.textContent === "Draft with AI",
    );
  await vi.waitFor(() => expect(draftButton()?.disabled).toBe(false));
  draftButton()!.click();
  await vi.waitFor(() =>
    expect(schema()?.components?.length).toBe(paths.length),
  );
  // Imports emit formField.add; let its deferred handlers run before checking the exported schema.
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(extractFormPaths(editor.instance.saveSchema())).toEqual(
    paths.map(({ path }) => path),
  );
  expect(extractFormPaths(schema())).toEqual(paths.map(({ path }) => path));
  container
    .querySelector<HTMLButtonElement>(
      '[data-testid="form-editor-save-button"]',
    )!
    .click();
  await vi.waitFor(() => expect(saveFormSchema).toHaveBeenCalled());
  expect(extractFormPaths(vi.mocked(saveFormSchema).mock.calls[0][1])).toEqual(
    paths.map(({ path }) => path),
  );
});

it("defaults the key of a newly added field to its ID", async () => {
  vi.mocked(fetchFormPaths).mockResolvedValue({ paths: [] });
  const [schema, setSchema] = createSignal<any>();
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => <FormEditorView formSchema={schema} setFormSchema={setSchema} />,
    container,
  );
  await vi.waitFor(() => expect(editor.instance).toBeDefined());
  const formField = editor.instance.get("formFieldRegistry").getAll()[0];
  const added = editor.instance
    .get("modeling")
    .addFormField({ type: "textfield" }, formField, 0);
  // The palette's field factory assigns a random key before formField.add fires.
  expect(added.key).toMatch(/^textfield_/);
  await vi.waitFor(() => expect(added.key).toBe(added.id));
});

it("provides reactive editor samples keyed to the screener's benefits", async () => {
  vi.mocked(fetchFormPaths).mockResolvedValue({ paths: [] });
  const [schema, setSchema] = createSignal<any>();
  const [benefits, setBenefits] = createSignal([
    { id: "housing", name: "Housing assistance", description: "" },
  ]);
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => (
      <FormEditorView
        formSchema={schema}
        setFormSchema={setSchema}
        benefits={benefits}
      />
    ),
    container,
  );
  await vi.waitFor(() =>
    expect(editor.instance?.get("screeningResults").getSnapshot().mode).toBe(
      "editor",
    ),
  );
  const service = editor.instance.get("screeningResults");
  expect(service.getSnapshot().results.housing.name).toBe("Housing assistance");
  const saved = editor.instance.saveSchema();
  setBenefits([{ id: "food", name: "Food assistance", description: "" }]);
  expect(service.getSnapshot().results.food.name).toBe("Food assistance");
  expect(service.getSnapshot().results.housing).toBeUndefined();
  expect(editor.instance.saveSchema()).toEqual(saved);
});

it("copies next steps only on insertion, keeps them out of answers, and preserves imports and undo/redo", async () => {
  vi.mocked(fetchFormPaths).mockResolvedValue({ paths: [] });
  const source = {
    id: "housing",
    name: "Housing",
    description: "",
    checks: [],
    nextSteps: { eligible: { body: "Original benefit instructions" } },
  };
  vi.mocked(fetchScreenerBenefit).mockResolvedValue(source);
  const [schema, setSchema] = createSignal<any>();
  const container = document.body.appendChild(document.createElement("div"));
  dispose = render(
    () => (
      <FormEditorView
        formSchema={schema}
        setFormSchema={setSchema}
        benefits={() => [source]}
      />
    ),
    container,
  );
  await vi.waitFor(() => expect(editor.instance).toBeDefined());
  const model = editor.instance.get("modeling");
  const registry = editor.instance.get("formFieldRegistry");
  const field = model.addFormField(
    { type: "next_steps" },
    registry.getAll()[0],
    0,
  );
  expect(field.benefitContent.housing).toEqual(source.nextSteps);
  expect(field.key).toBeUndefined();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(field.key).toBeUndefined();
  expect(extractFormPaths(editor.instance.saveSchema())).toEqual([]);
  source.nextSteps.eligible.body = "Updated benefit instructions";
  expect(field.benefitContent.housing.eligible.body).toBe(
    "Original benefit instructions",
  );
  editor.instance.get("commandStack").undo();
  expect(registry.get(field.id)).toBeUndefined();
  editor.instance.get("commandStack").redo();
  expect(registry.get(field.id).benefitContent.housing.eligible.body).toBe(
    "Original benefit instructions",
  );
  const saved = editor.instance.saveSchema();
  await editor.instance.importSchema(saved);
  expect(
    editor.instance.saveSchema().components[0].benefitContent.housing.eligible
      .body,
  ).toBe("Original benefit instructions");
});
