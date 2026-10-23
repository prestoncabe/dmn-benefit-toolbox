import {
  onCleanup,
  onMount,
  createEffect,
  createSignal,
  createResource,
  For,
  Match,
  Show,
  Switch,
  Accessor,
} from "solid-js";
import { useParams } from "@solidjs/router";

import { FormEditor } from "@bpmn-io/form-js-editor";
import Drawer from "@corvu/drawer"; // 'corvu/drawer'

import CustomFormFieldsModule from "./formJsExtensions/customFormFields";
import {
  screeningResultsModule,
  ScreeningResultsService,
} from "./formJsExtensions/screeningResults/screeningResults";
import { customKeyModule } from "./formJsExtensions/customKeyDropdown/customKeyDropdownProvider";
import PathOptionsService, {
  pathOptionsModule,
  isTypeCompatible,
} from "./formJsExtensions/customKeyDropdown/pathOptionsService";

import {
  saveFormSchema,
  fetchFormPaths,
  draftFormSchema,
} from "../../api/screener";
import { extractFormPaths } from "../../utils/formSchemaUtils";
import Loading from "../Loading";
import { fetchScreenerBenefit } from "@/api/benefit";
import NextStepsContentModal from "./NextStepsContentModal";
import { nextStepsEditorModule } from "./formJsExtensions/nextSteps/editorModule";
import {
  NEXT_STEPS_TYPE,
  copyBenefitContent,
} from "./formJsExtensions/nextSteps/content";

import "@bpmn-io/form-js/dist/assets/form-js.css";
import "@bpmn-io/form-js-editor/dist/assets/form-js-editor.css";
import { Benefit, FormPath } from "@/types";

function FormEditorView({ formSchema, setFormSchema, benefits = () => [] }) {
  const [isUnsaved, setIsUnsaved] = createSignal(false);
  const [isSaving, setIsSaving] = createSignal(false);
  const [isDrafting, setIsDrafting] = createSignal(false);
  const [draftError, setDraftError] = createSignal("");
  let disposed = false;
  let importingDraft = false;
  const params = useParams();
  const [editorReady, setEditorReady] = createSignal(false);
  const [contentField, setContentField] = createSignal<any>();
  // Benefit content is loaded in the background and only used to copy into a new
  // Next steps component or reset one, so it never holds up the editor. Each benefit
  // is fetched once; a failed fetch is retried on the next load.
  const sourceRequests = new Map<string, Promise<Benefit>>();
  const fetchSource = (screenerId: string, benefitId: string) => {
    const key = `${screenerId}/${benefitId}`;
    let request = sourceRequests.get(key);
    if (!request) {
      request = fetchScreenerBenefit(screenerId, benefitId);
      sourceRequests.set(key, request);
      request.catch(() => {
        if (sourceRequests.get(key) === request) sourceRequests.delete(key);
      });
    }
    return request;
  };
  const [benefitSources, { refetch: reloadBenefitSources }] = createResource(
    () => [params.screenerId, benefits()] as const,
    async ([screenerId, details]) => {
      const loaded = await Promise.allSettled(
        details.map((benefit) => fetchSource(screenerId, benefit.id)),
      );
      return {
        failed: loaded.some((result) => result.status === "rejected"),
        benefits: loaded.flatMap((result) =>
          result.status === "fulfilled" ? [result.value] : [],
        ),
      };
    },
  );
  const benefitContentError = () => benefitSources()?.failed ?? false;
  // Components added before the content loaded get it once it arrives.
  const fieldsAwaitingSources = new Set<string>();

  // Fetch form paths from backend (replaces local transformation logic)
  const [formPaths] = createResource<FormPath[]>(
    () => params.screenerId,
    async (screenerId: string) => {
      if (!screenerId) return [];
      const response = await fetchFormPaths(screenerId);
      return response.paths;
    },
  );

  let timeoutId;
  let container;
  let formEditor: FormEditor;
  let emptySchema = {
    components: [],
    exporter: { name: "form-js (https://demo.bpmn.io)", version: "1.15.0" },
    id: "BDT_Form",
    schemaVersion: 18,
    type: "default",
  };

  onMount(() => {
    // Existing schema content is never refreshed from benefit sources automatically.
    formEditor = new FormEditor({
      container,
      additionalModules: [
        // FilterFormComponentsModule,
        CustomFormFieldsModule,
        screeningResultsModule,
        pathOptionsModule,
        customKeyModule,
        nextStepsEditorModule({
          benefits,
          sources: () => benefitSources()?.benefits ?? [],
          onInsert: (field) => {
            if (benefitSources.loading) fieldsAwaitingSources.add(field.id);
          },
          open: (field) => setContentField(field),
        }),
      ],
    });
    const screeningResults =
      formEditor.get<ScreeningResultsService>("screeningResults");
    createEffect(() => screeningResults.showEditorSamples(benefits()));

    if (formSchema()) {
      formEditor.importSchema(formSchema()).catch((err) => {
        console.error("Failed to load schema", err);
      });
    } else {
      formEditor.importSchema(emptySchema).catch((err) => {
        console.error("Failed to load schema", err);
      });
    }

    formEditor.on("changed", (e) => {
      if (importingDraft) return;
      setIsUnsaved(true);
      setFormSchema(e.schema);
    });

    // Set default key to field ID when a new form field is added
    const eventBus = formEditor.get("eventBus") as any;
    const modeling = formEditor.get("modeling") as any;
    eventBus.on("formField.add", (event: { formField: any }) => {
      const field = event.formField;

      // Imports also emit formField.add; keep the keys of imported fields.
      // Skip group components as they don't use keys
      if (
        !importingDraft &&
        field &&
        field.id &&
        field.type !== NEXT_STEPS_TYPE &&
        field.type !== "group" &&
        field.type !== "default"
      ) {
        // Use setTimeout to ensure the field is fully added before modifying
        setTimeout(() => {
          if (!disposed) {
            modeling.editFormField(field, "key", field.id);
          }
        }, 0);
      }
    });
    setEditorReady(true);
  });

  createEffect(() => {
    const loaded = benefitSources();
    if (!editorReady() || benefitSources.loading || !loaded) return;
    const registry = formEditor.get("formFieldRegistry") as any;
    const modeling = formEditor.get("modeling") as any;
    for (const id of Array.from(fieldsAwaitingSources)) {
      fieldsAwaitingSources.delete(id);
      const field = registry.get(id);
      if (!field) continue;
      const content = field.benefitContent ?? {};
      const missing = loaded.benefits.filter(
        (benefit) => !Object.hasOwn(content, benefit.id),
      );
      if (missing.length)
        modeling.editFormField(field, "benefitContent", {
          ...content,
          ...copyBenefitContent(missing),
        });
    }
  });

  onCleanup(() => {
    disposed = true;
    if (formEditor) {
      formEditor.destroy();
      formEditor = null;
      clearTimeout(timeoutId);
    }
  });

  // Update path options when form paths load from backend
  createEffect(() => {
    if (!editorReady() || formPaths.loading) return;

    const currentFormPaths: FormPath[] = formPaths() || [];
    const pathOptionsService = formEditor.get(
      "pathOptionsService",
    ) as PathOptionsService;
    pathOptionsService.setOptions(
      currentFormPaths.map((formPath: FormPath) => ({
        value: formPath.path,
        label: formPath.path,
        type: formPath.type,
      })),
    );
  });

  const handleSave = async () => {
    const screenerId = params.screenerId;
    const schema = formSchema();
    setIsSaving(true);
    clearTimeout(timeoutId);

    try {
      await saveFormSchema(screenerId, schema);
      setIsUnsaved(false);
    } catch (error) {
      // Keep the form marked as unsaved so a failed request is not presented
      // to the user as a successful save.
      setIsUnsaved(true);
      console.error("Failed to save form schema", error);
    } finally {
      setIsSaving(false);
    }
  };

  const isBlank = () => !formSchema()?.components?.length;
  const assertDraftBindings = (schema: any) => {
    const required = new Map(
      (formPaths() || []).map(({ path, type }) => [path, type]),
    );
    const components = schema?.components;
    if (
      !required.size ||
      !Array.isArray(components) ||
      components.length !== required.size ||
      !components.every(
        (field) =>
          required.has(field.key) &&
          isTypeCompatible(required.get(field.key), field.type),
      ) ||
      extractFormPaths(schema).length !== required.size
    ) {
      throw new Error(
        "The AI draft is incomplete: every question must be connected to a required input. Please try again.",
      );
    }
  };
  const handleDraft = async () => {
    if (isDrafting() || !isBlank()) return;
    const screenerId = params.screenerId;
    setIsDrafting(true);
    setDraftError("");
    try {
      const schema = await draftFormSchema(screenerId);
      if (disposed || params.screenerId !== screenerId) return;
      // The user can keep editing while generation runs. Preserve anything added meanwhile.
      if (!isBlank()) {
        setDraftError(
          "The form changed while AI was drafting. Clear the form to try again.",
        );
        return;
      }
      assertDraftBindings(schema);
      const previousSchema = formSchema() || emptySchema;
      const previouslyUnsaved = isUnsaved();
      importingDraft = true;
      try {
        await formEditor.importSchema(schema);
        if (disposed) return;
        const importedSchema = formEditor.saveSchema();
        assertDraftBindings(importedSchema);
        setFormSchema(importedSchema);
        setIsUnsaved(true);
      } catch (error) {
        if (!disposed) {
          await formEditor.importSchema(previousSchema);
          setFormSchema(previousSchema);
          setIsUnsaved(previouslyUnsaved);
        }
        throw error;
      } finally {
        importingDraft = false;
      }
    } catch (error) {
      if (!disposed)
        setDraftError(
          error instanceof Error
            ? error.message
            : "Could not draft the form with AI",
        );
    } finally {
      if (!disposed) setIsDrafting(false);
    }
  };

  return (
    <>
      <Show when={benefitContentError()}>
        <p role="alert" class="m-4">
          Some benefit content could not be loaded. Existing component content
          is preserved.{" "}
          <button
            type="button"
            class="underline"
            onClick={() => reloadBenefitSources()}
          >
            Try again
          </button>
        </p>
      </Show>
      <Show when={contentField()} keyed>
        {(field) => (
          <NextStepsContentModal
            benefits={benefits}
            sources={() => benefitSources()?.benefits ?? []}
            field={field}
            save={(benefitContent) =>
              (formEditor.get("modeling") as any).editFormField(
                field,
                "benefitContent",
                benefitContent,
              )
            }
            close={() => setContentField()}
          />
        )}
      </Show>
      <Show when={isBlank() || isDrafting() || draftError()}>
        <div class="m-4 flex flex-col gap-2 items-start">
          <Show when={isBlank()}>
            <button
              type="button"
              class="btn-default btn-blue disabled:opacity-50"
              disabled={
                !editorReady() ||
                isDrafting() ||
                formPaths.loading ||
                !formPaths()?.length
              }
              onClick={handleDraft}
            >
              {isDrafting() ? "Drafting…" : "Draft with AI"}
            </button>
            <p class="text-sm text-gray-600">
              {formPaths()?.length
                ? "Draft questions from your benefits and checks, then review and save the form."
                : "Add benefits with configured checks to draft a form."}
            </p>
          </Show>
          <Show when={draftError()}>
            <p role="alert" class="text-red-800">
              {draftError()}
            </p>
          </Show>
        </div>
      </Show>
      <Show when={formPaths.loading}>
        <Loading />
      </Show>
      <div class="flex flex-row">
        <div class="flex-8 overflow-auto">
          <div class="h-full" ref={(el) => (container = el)} />
        </div>
        <FormValidationDrawer
          formSchema={formSchema}
          expectedInputPaths={formPaths}
        />
      </div>
      <div id="form-editor-save_container" class="fixed bottom-20 right-5 z-40">
        <Switch>
          <Match when={isUnsaved()}>
            <button
              data-testid="form-editor-save-button"
              disabled={!editorReady()}
              onClick={handleSave}
              class="btn-default btn-yellow shadow-[0_0_10px_rgba(0,0,0,0.4)]"
            >
              Save
            </button>
          </Match>
          <Match when={isSaving()}>
            <button
              data-testid="form-editor-save-button"
              disabled={true}
              onClick={handleSave}
              class="btn-default btn-gray cursor-not-allowed shadow-[0_0_10px_rgba(0,0,0,0.4)]"
            >
              Saving...
            </button>
          </Match>
          <Match when={!isUnsaved() && !isSaving()}>
            <button
              data-testid="form-editor-save-button"
              disabled={!editorReady()}
              onClick={handleSave}
              class="btn-default btn-blue shadow-[0_0_10px_rgba(0,0,0,0.4)]"
            >
              Save
            </button>
          </Match>
        </Switch>
      </div>
    </>
  );
}

const FormValidationDrawer = ({
  formSchema,
  expectedInputPaths,
}: {
  formSchema: any;
  expectedInputPaths: Accessor<FormPath[]>;
}) => {
  const formOutputs = () =>
    formSchema() ? extractFormPaths(formSchema()) : [];

  // Expected inputs come directly from backend API
  const expectedInputs = () => expectedInputPaths() || [];

  // Compute which expected inputs are satisfied vs missing
  const formOutputSet = () => new Set(formOutputs());

  const satisfiedInputs = () =>
    expectedInputs().filter((formPath) => formOutputSet().has(formPath.path));

  const missingInputs = () =>
    expectedInputs().filter((formPath) => !formOutputSet().has(formPath.path));

  return (
    <Drawer side="right">
      {(props) => (
        <>
          <Drawer.Trigger
            class="
              fixed bottom-5 right-5
              my-auto rounded-lg
              text-lg font-medium transition-all duration-100 "
          >
            <button class="btn-default btn-gray shadow-[0_0_10px_rgba(0,0,0,0.4)]">
              Validate Form Outputs
            </button>
          </Drawer.Trigger>
          <Drawer.Portal>
            <Drawer.Overlay
              class="
                fixed inset-0 z-50
                data-transitioning:transition-colors data-transitioning:duration-500
                data-transitioning:ease-[cubic-bezier(0.32,0.72,0,1)]"
              style={{
                "background-color": `rgb(0 0 0 / ${
                  0.5 * props.openPercentage
                })`,
              }}
            />
            <Drawer.Content
              class="
                fixed flex flex-col md:select-none
                -right-10 bottom-0 z-50 px-5 h-full max-w-[500px] min-w-[500px]
                bg-gray-100 border-l-4 border-gray-400 rounded-l-lg
                data-transitioning:transition-transform data-transitioning:duration-500
                data-transitioning:ease-[cubic-bezier(0.32,0.72,0,1)] overflow-y-scroll"
            >
              <Drawer.Label class="pt-5 mr-10 text-center text-xl font-bold">
                Form Validation
              </Drawer.Label>

              {/* Form Outputs Section */}
              <div class="mt-4 mr-10 px-4 pb-10">
                <h3 class="text-lg font-semibold text-gray-700 mb-2">
                  Form Outputs
                </h3>
                <For
                  each={formOutputs()}
                  fallback={
                    <p class="text-gray-500 italic text-sm">
                      No form fields defined yet.
                    </p>
                  }
                >
                  {(path) => (
                    <div class="py-2 px-3 mb-2 bg-white rounded border border-gray-300 font-mono text-sm">
                      {path}
                    </div>
                  )}
                </For>
              </div>

              {/* Missing Inputs Section */}
              <div class="mt-4 mr-10 px-4">
                <h3 class="text-lg font-semibold text-red-900 mb-2">
                  Missing Inputs
                </h3>
                <For
                  each={missingInputs()}
                  fallback={
                    <p class="text-gray-500 italic text-sm">
                      All required inputs are satisfied!
                    </p>
                  }
                >
                  {(formPath) => (
                    <div class="py-2 px-3 mb-2 bg-red-50 rounded border border-red-300 font-mono text-sm text-red-800">
                      {formPath.path} ({formPath.type})
                    </div>
                  )}
                </For>
              </div>

              {/* Satisfied Inputs Section */}
              <div class="mt-4 mr-10 px-4">
                <h3 class="text-lg font-semibold text-green-900 mb-2">
                  Satisfied Inputs
                </h3>
                <For
                  each={satisfiedInputs()}
                  fallback={
                    <p class="text-gray-500 italic text-sm">
                      No inputs satisfied yet.
                    </p>
                  }
                >
                  {(formPath) => (
                    <div class="py-2 px-3 mb-2 bg-green-50 rounded border border-green-300 font-mono text-sm text-green-800">
                      {formPath.path} ({formPath.type})
                    </div>
                  )}
                </For>
              </div>
            </Drawer.Content>
          </Drawer.Portal>
        </>
      )}
    </Drawer>
  );
};

export default FormEditorView;
