import { Accessor, createSignal, Show } from "solid-js";

import { updateScreener } from "@/api/screener";
import { Button } from "@/components/shared/Button";

/** CRM origins allowed to prefill the published screener and receive its results. */
export default function IntegrationOrigins(props: {
  screenerId: string;
  origins: Accessor<string[] | undefined>;
  onSaved: (origins: string[]) => void;
}) {
  const [text, setText] = createSignal((props.origins() ?? []).join("\n"));
  const [saving, setSaving] = createSignal(false);
  const [status, setStatus] = createSignal<{ error: boolean; text: string }>();

  const save = async () => {
    setSaving(true);
    setStatus();
    try {
      const saved = await updateScreener(props.screenerId, {
        integrationOrigins: text().split("\n"),
      });
      const origins = saved.integrationOrigins ?? [];
      setText(origins.join("\n"));
      setStatus({
        error: false,
        text: "Saved. Publish the screener to apply these origins.",
      });
      props.onSaved(origins);
    } catch (error) {
      setStatus({
        error: true,
        text:
          error instanceof Error
            ? error.message
            : "Could not save the CRM origins",
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div class="mt-6 flex flex-col gap-2">
      <label for="integration-origins" class="text-sm font-bold">
        Allowed CRM origins
      </label>
      <p id="integration-origins-help" class="text-sm text-gray-600">
        CRMs at these origins can prefill this screener and receive its results.
        Enter one per line, such as https://crm.example.org. Leave it empty to
        turn off CRM integrations.
      </p>
      <textarea
        id="integration-origins"
        aria-describedby="integration-origins-help"
        class="w-full border-2 border-gray-400 rounded px-3 py-2 font-mono text-sm"
        rows={3}
        value={text()}
        onInput={(event) => setText(event.currentTarget.value)}
      />
      <div class="flex flex-row gap-2 items-center">
        <Button variant="secondary" onClick={save} disabled={saving()}>
          Save CRM origins
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
    </div>
  );
}
