import { html } from "htm/preact";
import { NEXT_STEPS_TYPE, copyBenefitContent } from "./content";
import type { Benefit, BenefitDetail } from "@/types";

/** Editor-only wiring. Imports and duplicates retain their saved per-screener copy. */
export function nextStepsEditorModule(options: {
  /** The screener's benefits, including any whose content failed to load. */
  benefits: () => BenefitDetail[];
  /** Loaded benefit content to copy into new components. */
  sources: () => Benefit[];
  /** Called after a new component copies the loaded content. */
  onInsert?: (field: any) => void;
  open: (field: any) => void;
}) {
  class NextStepsProperties {
    static $inject = ["propertiesPanel", "eventBus"];
    constructor(propertiesPanel: any, eventBus: any) {
      propertiesPanel.registerProvider(this, 500);
      eventBus.on(
        "commandStack.formField.add.preExecute",
        ({ context }: any) => {
          const field = context.formField;
          if (
            field.type === NEXT_STEPS_TYPE &&
            field.benefitContent === undefined
          ) {
            field.benefitContent = copyBenefitContent(options.sources());
            options.onInsert?.(field);
          }
        },
      );
    }
    getGroups(field: any, editField: any) {
      return (groups: any[]) => {
        if (field.type !== NEXT_STEPS_TYPE) return groups;
        groups.push({
          id: "next-steps",
          label: "Next steps",
          entries: [
            {
              id: "next-steps-content",
              component: () =>
                html` <div class="bio-properties-panel-entry">
                  <label
                    class="bio-properties-panel-label"
                    for="next-steps-show-when"
                    >When content appears</label
                  >
                  <select
                    id="next-steps-show-when"
                    class="bio-properties-panel-input"
                    value=${field.showWhen ?? "whenDecided"}
                    onChange=${(event: Event) =>
                      editField(
                        field,
                        "showWhen",
                        (event.target as HTMLSelectElement).value,
                      )}
                  >
                    <option value="whenDecided">
                      As each benefit is decided
                    </option>
                    <option value="whenComplete">
                      Once all benefits are decided
                    </option>
                  </select>
                  <button
                    type="button"
                    class="bio-properties-panel-input"
                    onClick=${() => options.open(field)}
                  >
                    Edit benefit content
                  </button>
                  ${options
                    .benefits()
                    .some(
                      (benefit) =>
                        !Object.hasOwn(field.benefitContent ?? {}, benefit.id),
                    ) &&
                  html`<p role="alert">
                    Some benefits have no content in this component. Edit
                    benefit content to add it.
                  </p>`}
                </div>`,
            },
          ],
        });
        return groups;
      };
    }
  }
  return {
    __init__: ["nextStepsProperties"],
    nextStepsProperties: ["type", NextStepsProperties],
  };
}
