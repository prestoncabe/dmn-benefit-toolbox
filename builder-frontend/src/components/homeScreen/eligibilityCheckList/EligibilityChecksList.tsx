import { Accessor, createSignal, For, Setter, Show } from "solid-js";
import { useNavigate } from "@solidjs/router";

import Loading from "@/components/Loading";
import CheckModal from "./modals/CheckModal";
import eligibilityCheckResource from "./eligibilityCheckResource";

import type { EligibilityCheck } from "@/types";
import Tooltip from "@/components/shared/Tooltip";
import { Title } from "@solidjs/meta";
import { Modal } from "@/components/shared/Modal";
import { ArchiveCheck } from "@/components/homeScreen/eligibilityCheckList/modals/ArchiveCheck";
import { Button } from "@/components/shared/Button";

const EligibilityChecksList = () => {
  const {
    checks,
    archivedChecks,
    actions,
    actionInProgress,
    initialLoadStatus,
  } = eligibilityCheckResource();
  const navigate = useNavigate();

  const [addingNewCheck, setAddingNewCheck] = createSignal<boolean>(false);
  const [showArchivedChecks, setShowArchivedChecks] =
    createSignal<boolean>(false);

  const [checkIdToRemove, setCheckIdToRemove] = createSignal<null | string>(
    null,
  );

  const navigateToCheck = (check: EligibilityCheck) => {
    navigate("/check/" + check.id);
  };

  return (
    <div>
      <Title>BDT - Custom Checks</Title>
      <Show when={initialLoadStatus.loading() || actionInProgress()}>
        <Loading />
      </Show>
      <div class="flex flex-row gap-2 items-baseline">
        <div class="text-xl font-bold mb-2">Eligibility Checks</div>
        <Tooltip>
          <p>
            If the public checks do not cover a requirement specific to your use
            case, BDT allows you to build your own reusable custom eligibility
            checks.
          </p>
          <p>
            <a href="https://bdt-docs.web.app/custom-checks/" target="_blank">
              Read about custom eligibility checks in the docs
            </a>
          </p>
        </Tooltip>
      </div>
      <div class="text-md mb-3">
        Manage your custom eligibility checks here. Click on a check to view or
        edit its details.
      </div>
      <button
        class="px-4 py-2 w-fit cursor-pointer bg-blue-500
                rounded-lg shadow-md hover:shadow-lg hover:bg-blue-600
                font-bold text-sm text-white"
        onClick={() => setAddingNewCheck(true)}
      >
        Create New Check
      </button>
      <Modal show={addingNewCheck()} onClose={() => setAddingNewCheck(false)}>
        <CheckModal
          onAddCheck={actions.addNewCheck}
          onClose={() => setAddingNewCheck(false)}
        />
      </Modal>
      <div class="mt-4 grid gap-4 justify-items-center grid-cols-1 md:grid-cols-2 xl:grid-cols-3">
        <For each={checks()}>
          {(check) => (
            <CheckCard
              eligibilityCheck={check}
              navigateToCheck={navigateToCheck}
              setCheckIdToRemove={setCheckIdToRemove}
            />
          )}
        </For>
      </div>
      <Show when={archivedChecks().length > 0}>
        <div class="mt-8 border-t border-gray-300 pt-4">
          <Button
            variant="outline-secondary"
            aria-expanded={showArchivedChecks()}
            aria-controls="archived-checks"
            onClick={() => setShowArchivedChecks((shown) => !shown)}
          >
            {showArchivedChecks() ? "Hide" : "Show"} archived checks (
            {archivedChecks().length})
          </Button>
          <Show when={showArchivedChecks()}>
            <section id="archived-checks" class="mt-4">
              <h2 class="text-xl font-bold mb-1">Archived checks</h2>
              <p class="mb-4 text-gray-700">
                Restore a check to edit it or use its name again.
              </p>
              <div class="grid gap-4 justify-items-center grid-cols-1 md:grid-cols-2 xl:grid-cols-3">
                <For each={archivedChecks()}>
                  {(check) => (
                    <CheckCard
                      eligibilityCheck={check}
                      navigateToCheck={navigateToCheck}
                      setCheckIdToRemove={setCheckIdToRemove}
                      archived
                      onRestore={() => actions.restoreCheck(check.id)}
                      actionInProgress={actionInProgress}
                    />
                  )}
                </For>
              </div>
            </section>
          </Show>
        </div>
      </Show>
      <Modal
        show={checkIdToRemove() !== null}
        onClose={() => setCheckIdToRemove(null)}
      >
        <Show when={checkIdToRemove()}>
          {(checkId) => (
            <ArchiveCheck
              onArchive={() => actions.removeCheck(checkId())}
              onCancel={() => setCheckIdToRemove(null)}
            />
          )}
        </Show>
      </Modal>
    </div>
  );
};

const CheckCard = ({
  eligibilityCheck,
  navigateToCheck,
  setCheckIdToRemove,
  archived = false,
  onRestore,
  actionInProgress,
}: {
  eligibilityCheck: EligibilityCheck;
  navigateToCheck: (check: EligibilityCheck) => void;
  setCheckIdToRemove: Setter<string>;
  archived?: boolean;
  onRestore?: () => Promise<void>;
  actionInProgress?: Accessor<boolean>;
}) => {
  return (
    <div class="w-full flex">
      <div
        class="
          max-w-lg flex-1 flex flex-col
          border-1 border-gray-300 rounded-lg"
      >
        <div
          id={"check-card-details-" + eligibilityCheck.id}
          class="p-4 border-bottom border-gray-300 flex-1"
        >
          <div class="text-2xl mb-2 font-bold">{eligibilityCheck.name}</div>
          <div>
            <span class="font-bold">Description:</span>{" "}
            {eligibilityCheck.description}
          </div>
        </div>
        <div
          id={"benefit-card-actions-" + eligibilityCheck.id}
          class="p-4 flex justify-end space-x-2"
        >
          <Show
            when={archived}
            fallback={
              <>
                <Button
                  variant="outline-secondary"
                  onClick={() => {
                    navigateToCheck(eligibilityCheck);
                  }}
                >
                  Edit
                </Button>
                <Button
                  variant="outline-danger"
                  onClick={() => {
                    setCheckIdToRemove(eligibilityCheck.id);
                  }}
                >
                  Archive
                </Button>
              </>
            }
          >
            <Button
              disabled={actionInProgress?.()}
              onClick={() => void onRestore?.()}
            >
              Restore
            </Button>
          </Show>
        </div>
      </div>
    </div>
  );
};

export default EligibilityChecksList;
