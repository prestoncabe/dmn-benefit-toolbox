import {
  Accessor,
  createEffect,
  createMemo,
  createSignal,
  For,
  on,
  Show,
} from "solid-js";
import { createStore } from "solid-js/store";
import { Button } from "@/components/shared/Button";
import type { Benefit, NextSteps } from "@/types";

const contacts = [
  { key: "applyUrl", label: "Application URL", type: "url" },
  { key: "helpUrl", label: "Help URL", type: "url" },
  { key: "helpPhone", label: "Help phone number", type: "tel" },
] as const;

export default function NextStepsEditor(props: {
  benefit: Accessor<Benefit>;
  save: (nextSteps: NextSteps) => Promise<void>;
}) {
  const [draft, setDraft] = createStore({
    applyUrl: "",
    helpUrl: "",
    helpPhone: "",
    body: "",
    note: "",
  });
  const [saving, setSaving] = createSignal(false);
  const [status, setStatus] = createSignal<{ error: boolean; text: string }>();
  const benefitId = createMemo(() => props.benefit().id);

  // Refetching checks must not overwrite unsaved instructions for the same benefit.
  createEffect(
    on(benefitId, () => {
      const content = props.benefit().nextSteps;
      setDraft({
        applyUrl: content?.eligible?.applyUrl ?? "",
        helpUrl: content?.eligible?.helpUrl ?? "",
        helpPhone: content?.eligible?.helpPhone ?? "",
        body: content?.eligible?.body ?? "",
        note: content?.ineligible?.note ?? "",
      });
      setStatus();
    }),
  );

  const optional = (value: string) => (value.trim() ? value : null);
  const save = async (event: SubmitEvent) => {
    event.preventDefault();
    if (saving()) return;
    setStatus();
    for (const { key, label } of contacts) {
      if (key === "helpPhone" || !draft[key].trim()) continue;
      try {
        const url = new URL(draft[key].trim());
        if (!["https:", "http:"].includes(url.protocol) || !url.hostname)
          throw new Error();
      } catch {
        setStatus({
          error: true,
          text: `${label}: use a full http:// or https:// URL.`,
        });
        return;
      }
    }
    setSaving(true);
    try {
      await props.save({
        eligible: {
          applyUrl: optional(draft.applyUrl.trim()),
          helpUrl: optional(draft.helpUrl.trim()),
          helpPhone: optional(draft.helpPhone.trim()),
          body: optional(draft.body),
        },
        ineligible: { note: optional(draft.note) },
      });
      setStatus({ error: false, text: "Next steps saved." });
    } catch {
      setStatus({
        error: true,
        text: "Could not save next steps. Your edits are still here. Please try again.",
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <form class="mt-8 border-t border-gray-200 pt-6" onSubmit={save} noValidate>
      <h2 class="text-2xl font-bold mb-2">Next steps</h2>
      <p class="text-gray-600 mb-4">
        Add application instructions and contacts for people who qualify, and an
        optional note for people who do not. All fields are optional.
      </p>
      <fieldset disabled={saving()} class="flex flex-col gap-4">
        <legend class="text-lg font-bold mb-3">When eligible</legend>
        <div class="grid gap-4 lg:grid-cols-3">
          <For each={contacts}>
            {({ key, label, type }) => (
              <div class="flex flex-col gap-1">
                <label for={`next-steps-${key}`} class="font-semibold">
                  {label}
                </label>
                <input
                  id={`next-steps-${key}`}
                  type={type}
                  class="w-full border-2 border-gray-400 rounded px-3 py-2"
                  value={draft[key]}
                  onInput={(event) => setDraft(key, event.currentTarget.value)}
                />
              </div>
            )}
          </For>
        </div>
        <div class="flex flex-col gap-1">
          <label for="next-steps-body" class="font-semibold">
            Application instructions
          </label>
          <p id="next-steps-markdown-help" class="text-sm text-gray-600">
            You can use Markdown for paragraphs, lists, and links.
          </p>
          <textarea
            id="next-steps-body"
            aria-describedby="next-steps-markdown-help"
            class="w-full border-2 border-gray-400 rounded px-3 py-2"
            rows={6}
            value={draft.body}
            onInput={(event) => setDraft("body", event.currentTarget.value)}
          />
        </div>
        <div class="flex flex-col gap-1">
          <label for="next-steps-note" class="text-lg font-bold">
            When ineligible
          </label>
          <p id="next-steps-note-help" class="text-sm text-gray-600">
            An additional note to show with the unmet requirements. Markdown is
            supported.
          </p>
          <textarea
            id="next-steps-note"
            aria-describedby="next-steps-note-help"
            class="w-full border-2 border-gray-400 rounded px-3 py-2"
            rows={3}
            value={draft.note}
            onInput={(event) => setDraft("note", event.currentTarget.value)}
          />
        </div>
        <div class="flex items-center gap-3">
          <Button type="submit" variant="secondary" disabled={saving()}>
            {saving() ? "Saving next steps…" : "Save next steps"}
          </Button>
          <Show when={status()}>
            {(current) => (
              <p
                role={current().error ? "alert" : "status"}
                class={current().error ? "text-red-700" : ""}
              >
                {current().text}
              </p>
            )}
          </Show>
        </div>
      </fieldset>
    </form>
  );
}
