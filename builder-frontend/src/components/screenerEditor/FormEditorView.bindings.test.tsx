// @vitest-environment jsdom

import { createSignal } from "solid-js";
import { render } from "solid-js/web";
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

import {
  draftFormSchema,
  saveFormSchema,
  fetchFormPaths,
} from "@/api/screener";
import FormEditorView from "./FormEditorView";

let dispose: (() => void) | undefined;
beforeEach(() => {
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterEach(() => {
  dispose?.();
  document.body.replaceChildren();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
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
    expect(editor.instance.get("screeningResults").getSnapshot().mode).toBe(
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
