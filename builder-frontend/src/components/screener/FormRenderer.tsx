import { Accessor, createEffect, on, onCleanup, onMount } from "solid-js";

import debounce from "lodash.debounce";
import cloneDeep from "lodash/cloneDeep";
import isEqual from "lodash/isEqual";
import { Form } from "@bpmn-io/form-js-viewer";
import { State } from "@bpmn-io/form-js-viewer/dist/types/Form";

import CustomFormFieldsModule from "../screenerEditor/formJsExtensions/customFormFields";
import { hideQuestions } from "@/utils/questionVotes";
import { normalizeArrayFieldData } from "@/utils/arrayFieldData";
import {
  findFilledUnknownPaths,
  keepUnansweredPaths,
  restoreUnknownAnswers,
} from "@/utils/unknownAnswers";

import "@bpmn-io/form-js/dist/assets/form-js.css";

function FormRenderer({
  schema,
  formData,
  hiddenQuestionPaths,
  submitForm,
  evaluateInitialData = false,
  onDataChange,
}: {
  schema: { [key: string]: any };
  formData: Accessor<any>;
  hiddenQuestionPaths: Accessor<string[]>;
  submitForm: (data: any) => void;
  /** Evaluate host prefill on load, keeping its unknown answers null until answered. */
  evaluateInitialData?: boolean;
  onDataChange?: () => void;
}) {
  let container: Element | null = null;
  let form: Form | undefined;
  let currentData: any = {};
  let importing = false;
  let unknownPaths: string[][] = [];
  const evaluationData = (data: any) =>
    normalizeArrayFieldData(schema, restoreUnknownAnswers(data, unknownPaths));

  const importVisibleSchema = async () => {
    if (!form) return;
    importing = true;
    try {
      // Preserve the live form state, not the last submitted data: answers
      // typed since the pending submit would otherwise be discarded.
      const preservedData = cloneDeep(currentData);
      await form.importSchema(
        hideQuestions(schema, hiddenQuestionPaths()),
        preservedData,
      );
    } finally {
      importing = false;
    }
  };

  createEffect(
    on(hiddenQuestionPaths, () => void importVisibleSchema(), { defer: true }),
  );

  onMount(() => {
    form = new Form({ container, additionalModules: [CustomFormFieldsModule] });
    currentData = cloneDeep(formData());

    const debouncedSubmit = debounce((data) => {
      submitForm(evaluationData(data));
    }, 1000);

    form
      .importSchema(hideQuestions(schema, hiddenQuestionPaths()), formData())
      .then(() => {
        currentData = cloneDeep(form?._getState().data || formData());
        if (evaluateInitialData) {
          unknownPaths = findFilledUnknownPaths(formData(), currentData);
          submitForm(evaluationData(currentData));
        }
        form?.on("changed", (event: State) => {
          const dataChanged = !isEqual(currentData, event.data);
          currentData = cloneDeep(event.data);
          if (importing || !dataChanged) return;
          unknownPaths = keepUnansweredPaths(unknownPaths, event.data);
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
