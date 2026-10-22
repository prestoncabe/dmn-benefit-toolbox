// @vitest-environment jsdom
import { createSignal } from "solid-js";
import { render } from "solid-js/web";
import { h } from "preact";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ScreenerResult } from "@/types";
import {
  screeningResultsModule,
  type ScreeningResultsService,
} from "./screeningResults";
import { useScreeningResults } from "./useScreeningResults";

const forms = vi.hoisted(() => [] as any[]);
vi.mock("@bpmn-io/form-js-viewer", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("@bpmn-io/form-js-viewer")>();
  return {
    ...original,
    Form: class extends original.Form {
      constructor(options: any) {
        super({
          ...options,
          additionalModules: [...options.additionalModules, probeModule],
        });
        forms.push(this);
      }
    },
  };
});
import { Form, Text } from "@bpmn-io/form-js-viewer";
import { FormEditor } from "@bpmn-io/form-js-editor";
import PublishedForm from "@/components/screener/FormRenderer";
import PreviewForm from "@/components/screenerEditor/preview/FormRenderer";

function Probe() {
  return h(
    "output",
    { "data-testid": "results-probe" },
    JSON.stringify(useScreeningResults()),
  );
}
Probe.config = { ...Text.config, type: "results_probe" };
class RegisterProbe {
  static $inject = ["formFields"];
  constructor(fields: any) {
    fields.register(Probe.config.type, Probe);
  }
}
const probeModule = { __init__: ["probe"], probe: ["type", RegisterProbe] };
const schema = {
  type: "default",
  id: "results-test",
  schemaVersion: 18,
  components: [
    { id: "probe", type: "results_probe" },
    { id: "answer", type: "textfield", key: "answer", label: "Answer" },
  ],
};

describe.each(["published", "preview"])(
  "screening results in the real %s form",
  (mode) => {
    const disposers: (() => void)[] = [];
    afterEach(() => {
      disposers.splice(0).forEach((dispose) => dispose());
      forms.length = 0;
      document.body.replaceChildren();
    });

    async function mount() {
      const [results, setResults] = createSignal<ScreenerResult>();
      const [pending, setPending] = createSignal(false);
      const [failed, setFailed] = createSignal(false);
      const container = document.body.appendChild(
        document.createElement("div"),
      );
      const common = {
        formData: () => ({ answer: "Keep this answer" }),
        hiddenQuestionPaths: () => [],
        submitForm: vi.fn(),
        results,
        pending,
        failed,
      };
      disposers.push(
        render(
          () =>
            mode === "published" ? (
              <PublishedForm schema={schema} {...common} />
            ) : (
              <PreviewForm schema={() => schema} {...common} />
            ),
          container,
        ),
      );
      await vi.waitFor(() =>
        expect(container.querySelector("output")).not.toBeNull(),
      );
      const form = forms[forms.length - 1];
      const snapshot = () =>
        JSON.parse(container.querySelector("output")!.textContent!);
      return {
        container,
        form,
        snapshot,
        setResults,
        setPending,
        setFailed,
        common,
      };
    }

    it("updates custom fields without reimporting the schema or submitting answers", async () => {
      const s = await mount();
      const importSchema = vi.spyOn(s.form, "importSchema");
      const decided: ScreenerResult = {
        benefit: { name: "Benefit", result: "TRUE", check_results: {} },
      };
      s.setResults(decided);
      await vi.waitFor(() => expect(s.snapshot().results).toEqual(decided));
      s.setPending(true);
      await vi.waitFor(() => expect(s.snapshot().pending).toBe(true));
      expect(s.snapshot().results).toEqual(decided);
      s.setFailed(true);
      await vi.waitFor(() => expect(s.snapshot().failed).toBe(true));
      expect(s.snapshot().pending).toBe(true);
      s.setFailed(false);
      s.setResults({
        benefit: { ...decided.benefit, result: "UNABLE_TO_DETERMINE" },
      });

      s.setPending(false);
      await vi.waitFor(() => expect(s.snapshot().pending).toBe(false));
      expect(s.snapshot().results.benefit.result).toBe("UNABLE_TO_DETERMINE");
      expect(s.form._getState().data).toEqual({ answer: "Keep this answer" });
      expect(importSchema).not.toHaveBeenCalled();
      expect(s.common.submitForm).not.toHaveBeenCalled();
      expect(s.snapshot().mode).toBe("live");
    });

    it("isolates service state between simultaneous forms", async () => {
      const first = await mount();
      const second = await mount();
      first.setPending(true);
      await vi.waitFor(() => expect(first.snapshot().pending).toBe(true));
      expect(second.snapshot().pending).toBe(false);
      expect(first.form.get("screeningResults")).not.toBe(
        second.form.get("screeningResults"),
      );
    });
  },
);

it("renders custom fields with no results in a form without the module", async () => {
  const container = document.body.appendChild(document.createElement("div"));
  const form = new Form({ container, additionalModules: [] });
  try {
    await form.importSchema(schema);
    await vi.waitFor(() =>
      expect(
        JSON.parse(container.querySelector("output")!.textContent!),
      ).toEqual({
        pending: false,
        failed: false,
        mode: "live",
      }),
    );
  } finally {
    form.destroy();
    forms.length = 0;
    container.remove();
  }
});

it("renders editor samples through the same custom-field hook", async () => {
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  const container = document.body.appendChild(document.createElement("div"));
  const editor = new FormEditor({
    container,
    additionalModules: [screeningResultsModule, probeModule],
  });
  try {
    const service = editor.get<ScreeningResultsService>("screeningResults");
    service.showEditorSamples([
      { id: "housing", name: "Housing assistance", description: "" },
    ]);
    await editor.importSchema(schema);
    const snapshot = () =>
      JSON.parse(container.querySelector("output")!.textContent!);
    await vi.waitFor(() =>
      expect(snapshot().results.housing.name).toBe("Housing assistance"),
    );
    expect(snapshot().mode).toBe("editor");
    const saved = editor.saveSchema();
    service.showEditorSamples([
      { id: "food", name: "Food assistance", description: "" },
    ]);
    await vi.waitFor(() =>
      expect(snapshot().results.food.name).toBe("Food assistance"),
    );
    expect(snapshot().results.housing).toBeUndefined();
    expect(editor.saveSchema()).toEqual(saved);
  } finally {
    editor.destroy();
    container.remove();
    vi.unstubAllGlobals();
  }
});
