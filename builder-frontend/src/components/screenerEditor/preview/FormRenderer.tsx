import { Accessor, createEffect, on, onCleanup, onMount } from "solid-js";
import debounce from "lodash.debounce";
import cloneDeep from "lodash/cloneDeep";
import isEqual from "lodash/isEqual";

import { Form } from "@bpmn-io/form-js-viewer";

import { PreviewFormData } from "./types";
import CustomFormFieldsModule from "../formJsExtensions/customFormFields";
import {
  connectScreeningResults,
  screeningResultsModule,
} from "../formJsExtensions/screeningResults/screeningResults";
import type { ScreenerResult } from "@/types";
import { hideQuestions } from "@/utils/questionVotes";
import { normalizeArrayFieldData } from "@/utils/arrayFieldData";

import "@bpmn-io/form-js/dist/assets/form-js.css";

function FormRenderer({
  schema,
  formData,
  hiddenQuestionPaths,
  submitForm,
  onDataChange,
  results = () => undefined,
  pending = () => false,
  failed = () => false,
  onNextStepsShownChange,
}: {
  schema: Accessor<any>;
  formData: Accessor<PreviewFormData>;
  hiddenQuestionPaths: Accessor<string[]>;
  submitForm: (data: PreviewFormData) => void;
  onDataChange?: () => void;
  results?: Accessor<ScreenerResult | undefined>;
  pending?: Accessor<boolean>;
  failed?: Accessor<boolean>;
  /** Whether a Next steps field is rendered, so the page can skip its own completion message. */
  onNextStepsShownChange?: (shown: boolean) => void;
}) {
  let container: HTMLDivElement | undefined;
  let form: Form | undefined;
  let currentData: PreviewFormData = {};
  let importing = false;

  const importVisibleSchema = async () => {
    if (!form) return;
    importing = true;
    try {
      // Preserve the live form state, not the last submitted data: answers
      // typed since the pending submit would otherwise be discarded.
      const preservedData = cloneDeep(currentData);
      await form.importSchema(
        hideQuestions(schema(), hiddenQuestionPaths()),
        preservedData,
      );
    } finally {
      importing = false;
    }
  };

  createEffect(
    on(
      () => [schema(), hiddenQuestionPaths()] as const,
      () => void importVisibleSchema(),
      { defer: true },
    ),
  );

  onMount(() => {
    form = new Form({
      container,
      additionalModules: [CustomFormFieldsModule, screeningResultsModule],
    });
    connectScreeningResults(form, {
      results,
      pending,
      failed,
      onNextStepsShownChange,
    });
    currentData = cloneDeep(formData());

    const debouncedSubmit = debounce(
      (data: PreviewFormData) =>
        submitForm(normalizeArrayFieldData(schema(), data)),
      500,
    );

    form
      .importSchema(hideQuestions(schema(), hiddenQuestionPaths()), formData())
      .then(() => {
        form?.on("changed", (event) => {
          const dataChanged = !isEqual(currentData, event.data);
          currentData = cloneDeep(event.data);
          if (importing || !dataChanged) return;
          onDataChange?.();
          debouncedSubmit(event.data);
        });
      })
      .catch(console.error);

    onCleanup(() => {
      debouncedSubmit.cancel();
      form?.destroy();
    });
  });

  return (
    <div>
      <div ref={(el) => (container = el)} />
    </div>
  );
}

export default FormRenderer;
