import { Accessor, createSignal, For, Show, onMount } from "solid-js";
import { Portal } from "solid-js/web";
import cloneDeep from "lodash/cloneDeep";
import type { Benefit, BenefitDetail, NextSteps } from "@/types";
import type { BenefitContent } from "./formJsExtensions/nextSteps/content";
import NextStepsEditor from "./manageBenefits/configureBenefit/NextStepsEditor";

export default function NextStepsContentModal(props: {
  benefits: Accessor<BenefitDetail[]>;
  sources: Accessor<Benefit[]>;
  field: any;
  save: (content: BenefitContent) => void;
  close: () => void;
}) {
  const [content, setContent] = createSignal<BenefitContent>(
    cloneDeep(props.field.benefitContent ?? {}),
  );
  const [selected, setSelected] = createSignal(props.benefits()[0]?.id ?? "");
  const [updated, setUpdated] = createSignal(false);
  // The editor reads its content once. Remount it for another benefit or a reset,
  // but not for its own saves, which would drop its status and focus.
  const [revision, setRevision] = createSignal(0);
  const [dirty, setDirty] = createSignal(false);
  const discardEdits = () =>
    !dirty() ||
    window.confirm("Discard the next steps changes you haven't saved?");
  const missing = () =>
    props.benefits().filter((benefit) => !Object.hasOwn(content(), benefit.id));
  const current = () =>
    props.benefits().find((benefit) => benefit.id === selected());
  const update = (nextSteps: NextSteps) => {
    const next = { ...content(), [selected()]: cloneDeep(nextSteps) };
    props.save(next);
    setContent(next);
    setUpdated(true);
  };
  let dialog!: HTMLDialogElement;
  const previousFocus = document.activeElement as HTMLElement | null;
  const close = () => {
    if (!discardEdits()) return;
    props.close();
    previousFocus?.focus();
  };
  // A native modal supplies focus trapping and Escape dismissal.
  onMount(() => dialog.showModal());
  return (
    <Portal>
      <dialog
        ref={dialog}
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
        class="m-auto rounded-lg p-6 w-[min(900px,95vw)] max-h-[90vh] overflow-auto backdrop:bg-black/40"
        aria-label="Edit next steps content"
      >
        <div class="flex justify-between gap-4 items-center">
          <h2 class="text-2xl font-bold">Edit next steps content</h2>
          <button type="button" class="btn-default" onClick={close}>
            Close
          </button>
        </div>
        <p class="my-4">
          Changes apply to this component in this screener. Save the form to
          keep them.
        </p>
        <Show when={updated()}>
          <p role="status" class="my-4">
            Component content updated. Save the form to keep your changes.
          </p>
        </Show>
        <Show when={missing().length}>
          <p role="alert" class="my-4 text-amber-900">
            No content copied for:{" "}
            {missing()
              .map((benefit) => benefit.name)
              .join(", ")}
            . Edit their content or reset from the benefit.
          </p>
        </Show>
        <Show
          when={props.benefits().length}
          fallback={
            <p>Add benefits to this screener to configure their next steps.</p>
          }
        >
          <label for="next-steps-benefit" class="font-semibold">
            Benefit
          </label>
          <select
            id="next-steps-benefit"
            class="border rounded mx-3 p-2"
            value={selected()}
            onChange={(event) => {
              if (discardEdits()) setSelected(event.currentTarget.value);
              else event.currentTarget.value = selected();
            }}
          >
            <For each={props.benefits()}>
              {(benefit) => <option value={benefit.id}>{benefit.name}</option>}
            </For>
          </select>
          <button
            type="button"
            class="btn-default"
            disabled={
              !props.sources().some((benefit) => benefit.id === selected())
            }
            onClick={() => {
              if (!discardEdits()) return;
              update(
                props.sources().find((benefit) => benefit.id === selected())!
                  .nextSteps ?? {},
              );
              setRevision((value) => value + 1);
            }}
          >
            Reset from benefit
          </button>
          <Show when={current() && `${current()!.id}:${revision()}`} keyed>
            {(_key) => {
              const benefit = current()!;
              const draft = {
                ...benefit,
                checks: [],
                nextSteps: content()[benefit.id],
              } as Benefit;
              return (
                <NextStepsEditor
                  benefit={() => draft}
                  save={async (nextSteps) => update(nextSteps)}
                  onDirtyChange={setDirty}
                  showSavedStatus={false}
                />
              );
            }}
          </Show>
        </Show>
      </dialog>
    </Portal>
  );
}
