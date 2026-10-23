// @vitest-environment jsdom

import { createSignal } from "solid-js";
import { render } from "solid-js/web";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const editor = vi.hoisted(() => ({
  importSchema: vi.fn(async () => {}),
  saveSchema: vi.fn(),
  destroy: vi.fn(),
  handlers: {} as Record<string, (event: any) => void>,
  screeningResults: { showEditorSamples: vi.fn() },
}));
vi.mock("@solidjs/router", () => ({
  useParams: () => ({ screenerId: "screener-1" }),
}));
vi.mock("@bpmn-io/form-js-editor", () => ({
  FormEditor: class {
    importSchema = editor.importSchema;
    saveSchema = editor.saveSchema;
    destroy = editor.destroy;
    on(event: string, handler: (event: any) => void) {
      editor.handlers[event] = handler;
    }
    get(name: string) {
      if (name === "screeningResults") return editor.screeningResults;
      return {
        on: vi.fn(),
        fire: vi.fn(),
        editFormField: vi.fn(),
        setOptions: vi.fn(),
      };
    }
  },
}));
vi.mock("./formJsExtensions/customFormFields", () => ({ default: {} }));
vi.mock(
  "./formJsExtensions/customKeyDropdown/customKeyDropdownProvider",
  () => ({ customKeyModule: {} }),
);
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

describe("AI form drafting", () => {
  let dispose: (() => void) | undefined;
  const draft = {
    type: "default",
    components: [{ id: "question", type: "yes_no", key: "custom.resident" }],
  };

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(fetchFormPaths).mockResolvedValue({
      paths: [{ path: "custom.resident", type: "boolean" }],
    });
    vi.mocked(draftFormSchema).mockResolvedValue(draft);
    editor.saveSchema.mockReturnValue(draft);
  });
  afterEach(() => {
    dispose?.();
    document.body.replaceChildren();
  });

  async function mount(initial?: any) {
    const [schema, setSchema] = createSignal(initial);
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () => <FormEditorView formSchema={schema} setFormSchema={setSchema} />,
      container,
    );
    await vi.waitFor(() => expect(fetchFormPaths).toHaveBeenCalled());
    const button = () =>
      Array.from(container.querySelectorAll("button")).find((button) =>
        /Draft/.test(button.textContent ?? ""),
      );
    return { container, schema, button };
  }

  it("imports a draft for review, prevents duplicate requests, and saves only on Save", async () => {
    let resolve!: (schema: any) => void;
    vi.mocked(draftFormSchema).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const { container, schema, button } = await mount();
    await vi.waitFor(() => expect(button()?.disabled).toBe(false));
    button()!.click();
    expect(button()?.disabled).toBe(true);
    button()!.click();
    expect(draftFormSchema).toHaveBeenCalledOnce();
    resolve(draft);
    await vi.waitFor(() => expect(schema()).toEqual(draft));
    expect(editor.importSchema).toHaveBeenLastCalledWith(draft);
    expect(button()).toBeUndefined();
    expect(saveFormSchema).not.toHaveBeenCalled();
    container
      .querySelector<HTMLButtonElement>(
        '[data-testid="form-editor-save-button"]',
      )!
      .click();
    await vi.waitFor(() =>
      expect(saveFormSchema).toHaveBeenCalledWith("screener-1", draft),
    );
  });

  it("shows a retryable error and preserves the blank form", async () => {
    vi.mocked(draftFormSchema).mockRejectedValue(
      new Error("Gemini unavailable"),
    );
    const { container, schema, button } = await mount({ components: [] });
    await vi.waitFor(() => expect(button()?.disabled).toBe(false));
    button()!.click();
    await vi.waitFor(() =>
      expect(container.querySelector('[role="alert"]')?.textContent).toBe(
        "Gemini unavailable",
      ),
    );
    expect(schema()).toEqual({ components: [] });
    expect(button()?.disabled).toBe(false);
  });

  it("prevents duplicate form saves and keeps edits made during a save unsaved", async () => {
    let finishSave!: () => void;
    vi.mocked(saveFormSchema).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve;
      }),
    );
    const { container } = await mount(draft);
    editor.handlers.changed({ schema: draft });
    const saveButton = () =>
      container.querySelector<HTMLButtonElement>(
        '[data-testid="form-editor-save-button"]',
      )!;
    saveButton().click();
    expect(saveButton().disabled).toBe(true);
    saveButton().click();
    expect(saveFormSchema).toHaveBeenCalledTimes(1);
    const newer = {
      ...draft,
      components: [...draft.components, { id: "next", type: "next_steps" }],
    };
    editor.handlers.changed({ schema: newer });
    finishSave();
    await vi.waitFor(() => expect(saveButton().disabled).toBe(false));
    expect(saveButton().className).toContain("btn-yellow");
  });

  it("does not offer drafting on an existing form", async () => {
    expect((await mount(draft)).button()).toBeUndefined();
  });

  it("does not overwrite questions added during generation", async () => {
    let resolve!: (schema: any) => void;
    vi.mocked(draftFormSchema).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const { container, schema, button } = await mount();
    await vi.waitFor(() => expect(button()?.disabled).toBe(false));
    button()!.click();
    const edited = { components: [{ type: "textfield", key: "myQuestion" }] };
    editor.handlers.changed({ schema: edited });
    resolve(draft);
    await vi.waitFor(() =>
      expect(container.querySelector('[role="alert"]')?.textContent).toContain(
        "form changed",
      ),
    );
    expect(schema()).toEqual(edited);
    expect(editor.importSchema).not.toHaveBeenCalledWith(draft);
  });

  it("disables drafting when no checks provide input paths", async () => {
    vi.mocked(fetchFormPaths).mockResolvedValue({ paths: [] });
    const { container, button } = await mount();
    expect(button()?.disabled).toBe(true);
    expect(container.textContent).toContain(
      "Add benefits with configured checks",
    );
  });

  it("rejects drafts with missing or unmapped questions before importing them", async () => {
    const incomplete = {
      ...draft,
      components: [{ id: "question", type: "yes_no", key: "Field_draft_0" }],
    };
    vi.mocked(draftFormSchema).mockResolvedValue(incomplete);
    const { container, schema, button } = await mount({ components: [] });
    await vi.waitFor(() => expect(button()?.disabled).toBe(false));
    button()!.click();
    await vi.waitFor(() =>
      expect(container.querySelector('[role="alert"]')?.textContent).toContain(
        "every question must be connected",
      ),
    );
    expect(schema()).toEqual({ components: [] });
    expect(editor.importSchema).not.toHaveBeenCalledWith(incomplete);
    expect(saveFormSchema).not.toHaveBeenCalled();
  });

  it("rejects keys lost during import and restores the previous blank form", async () => {
    editor.saveSchema.mockReturnValue({
      ...draft,
      components: [{ id: "question", type: "yes_no", key: "Field_draft_0" }],
    });
    const previous = { components: [] };
    const { container, schema, button } = await mount(previous);
    await vi.waitFor(() => expect(button()?.disabled).toBe(false));
    button()!.click();
    await vi.waitFor(() =>
      expect(container.querySelector('[role="alert"]')?.textContent).toContain(
        "every question must be connected",
      ),
    );
    expect(editor.importSchema).toHaveBeenLastCalledWith(previous);
    expect(schema()).toEqual(previous);
    expect(saveFormSchema).not.toHaveBeenCalled();
    expect(button()?.disabled).toBe(false);
  });
});
