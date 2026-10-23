import { Accessor, createEffect, createSignal, For, Show } from "solid-js";
import { createStore } from "solid-js/store";
import { Button } from "@/components/shared/Button";
import { ApiError } from "@/api/check";
import type { Benefit, NextSteps } from "@/types";

// Keep the limits in step with NextSteps.java.
const contacts = [
  { key: "applyUrl", label: "Application URL", type: "url", maxLength: 2000 },
  { key: "helpUrl", label: "Help URL", type: "url", maxLength: 2000 },
  { key: "helpPhone", label: "Help phone number", type: "tel", maxLength: 50 },
] as const;

// The server checks links with java.net.URI, which is stricter than the browser's URL parser.
// Send the browser's normalized URL with anything URI rejects percent-encoded, or null if the
// link is not one the server accepts.
const encodeForUri = (part: string) =>
  part
    .replace(/%(?![0-9A-Fa-f]{2})/g, "%25")
    .replace(/[^A-Za-z0-9\-._~:/?@!$&'()*+,;=%]/g, encodeURIComponent);

export function normalizeHttpUrl(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (
    !["https:", "http:"].includes(url.protocol) ||
    !url.hostname ||
    url.hostname.includes("_") ||
    url.username ||
    url.password
  )
    return null;
  return (
    url.origin +
    encodeForUri(url.pathname + url.search) +
    (url.hash && "#" + encodeForUri(url.hash.slice(1)))
  );
}

export default function NextStepsEditor(props: {
  benefit: Accessor<Benefit>;
  save: (nextSteps: NextSteps) => Promise<void>;
  /** Called with whether the draft has edits that have not been saved. */
  onDirtyChange?: (dirty: boolean) => void;
}) {
  // Read once: refetching checks must not overwrite unsaved instructions. The parent
  // remounts the editor for a different benefit.
  const content = props.benefit().nextSteps;
  const initial = {
    applyUrl: content?.eligible?.applyUrl ?? "",
    helpUrl: content?.eligible?.helpUrl ?? "",
    helpPhone: content?.eligible?.helpPhone ?? "",
    body: content?.eligible?.body ?? "",
    note: content?.ineligible?.note ?? "",
  };
  const [draft, setDraft] = createStore({ ...initial });
  const [saved, setSaved] = createSignal(initial);
  createEffect(() =>
    props.onDirtyChange?.(
      (Object.keys(initial) as (keyof typeof initial)[]).some(
        (key) => draft[key] !== saved()[key],
      ),
    ),
  );
  const [saving, setSaving] = createSignal(false);
  const [status, setStatus] = createSignal<{ error: boolean; text: string }>();

  const optional = (value: string) => (value.trim() ? value : null);
  const save = async (event: SubmitEvent) => {
    event.preventDefault();
    if (saving()) return;
    setStatus();
    const urls = { applyUrl: null, helpUrl: null } as Record<
      "applyUrl" | "helpUrl",
      string | null
    >;
    for (const { key, label } of contacts) {
      if (key === "helpPhone" || !draft[key].trim()) continue;
      urls[key] = normalizeHttpUrl(draft[key].trim());
      if (!urls[key]) {
        setStatus({
          error: true,
          text: `${label}: use a full http:// or https:// URL.`,
        });
        return;
      }
    }
    setSaving(true);
    const submitted = { ...draft };
    try {
      await props.save({
        eligible: {
          applyUrl: urls.applyUrl,
          helpUrl: urls.helpUrl,
          helpPhone: optional(draft.helpPhone.trim()),
          body: optional(draft.body),
        },
        ineligible: { note: optional(draft.note) },
      });
      setSaved(submitted);
      setStatus({ error: false, text: "Next steps saved." });
    } catch (error) {
      // A 400 names the field to fix; retrying the same content would fail again.
      setStatus({
        error: true,
        text:
          error instanceof ApiError && error.status === 400
            ? `Could not save next steps. ${error.message}`
            : "Could not save next steps. Your edits are still here. Please try again.",
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
            {({ key, label, type, maxLength }) => (
              <div class="flex flex-col gap-1">
                <label for={`next-steps-${key}`} class="font-semibold">
                  {label}
                </label>
                <input
                  id={`next-steps-${key}`}
                  type={type}
                  maxLength={maxLength}
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
            maxLength={10000}
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
            maxLength={5000}
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
