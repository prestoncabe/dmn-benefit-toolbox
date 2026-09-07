import { createResource, createSignal, For, Show } from "solid-js";

import { fetchLibraryBenefits } from "@/api/benefit";
import type { Benefit } from "@/types";

const SelectExistingBenefitModal = (props: {
  importBenefit: (benefitId: string) => Promise<void>;
  closeModal: () => void;
}) => {
  const [availableBenefits] = createResource<Benefit[]>(fetchLibraryBenefits);
  const [importingId, setImportingId] = createSignal<string | null>(null);
  const [error, setError] = createSignal<string | null>(null);

  const importBenefit = async (benefitId: string) => {
    setImportingId(benefitId);
    setError(null);
    try {
      await props.importBenefit(benefitId);
      props.closeModal();
    } catch (cause) {
      setError(
        cause instanceof Error ? cause.message : "Could not import benefit",
      );
      setImportingId(null);
    }
  };

  return (
    <div class="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div class="bg-white px-8 py-6 rounded-xl max-w-180 w-1/2 min-w-80">
        <div class="text-2xl font-bold mb-2">Add a Library Benefit</div>
        <p class="mb-4 text-gray-700">
          Choose a pre-built benefit. After importing it, you can edit its name,
          description, and eligibility checks.
        </p>

        <Show when={availableBenefits.loading}>
          <div>Loading library benefits...</div>
        </Show>
        <Show when={availableBenefits.error}>
          <div class="text-red-600" role="alert">
            Error loading benefits: {availableBenefits.error.message}
          </div>
        </Show>
        <Show when={error()}>
          <div class="text-red-600 mb-3" role="alert">
            {error()}
          </div>
        </Show>
        <Show when={availableBenefits()?.length === 0}>
          <div>No library benefits are currently available.</div>
        </Show>

        <div class="max-h-96 space-y-3 overflow-y-auto">
          <For each={availableBenefits()}>
            {(benefit) => (
              <div class="border-2 border-gray-200 rounded p-4">
                <div class="font-bold text-lg">{benefit.name}</div>
                <div class="mb-2">{benefit.description}</div>
                <div class="text-sm text-gray-600 mb-3">
                  {benefit.checks.length} eligibility checks
                </div>
                <button
                  type="button"
                  class="btn-default btn-blue"
                  disabled={importingId() !== null}
                  onClick={() => importBenefit(benefit.id)}
                >
                  {importingId() === benefit.id
                    ? "Adding..."
                    : "Add to screener"}
                </button>
              </div>
            )}
          </For>
        </div>

        <div class="flex justify-end mt-4">
          <button
            type="button"
            class="btn-default hover:bg-gray-200"
            disabled={importingId() !== null}
            onClick={props.closeModal}
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};

export default SelectExistingBenefitModal;
