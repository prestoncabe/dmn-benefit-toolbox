import { Accessor, createSignal, For, Setter, Show } from "solid-js";

import AddNewBenefitModal from "./modals/AddNewBenefitModal";
import ConfirmationModal from "../../../shared/ConfirmationModal";
import SelectExistingBenefitModal from "./modals/SelectExistingBenefitModal";

import screenerBenefitResource from "./screenerBenefitsResource";
import Loading from "../../../Loading";

import type { BenefitDetail } from "@/types";
import Tooltip from "@/components/shared/Tooltip";

const BenefitList = ({
  screenerId,
  setBenefitIdToConfigure,
}: {
  screenerId: Accessor<string>;
  setBenefitIdToConfigure: Setter<null | string>;
}) => {
  const { screenerBenefits, actions, actionInProgress, initialLoadStatus } =
    screenerBenefitResource(screenerId);

  const [addingNewBenefit, setAddingNewBenefit] = createSignal<boolean>(false);
  const [selectExistingBenefitModal, setSelectExistingBenefitModal] =
    createSignal<boolean>(false);
  const [benefitIdToRemove, setBenefitIdToRemove] = createSignal<null | string>(
    null,
  );

  return (
    <div class="p-5">
      <div class="flex flex-row gap-2 items-baseline">
        <div
          id="manage-benefits-title"
          class="text-3xl font-bold mb-2 tracking-wide"
        >
          Manage Benefits
        </div>
        <Tooltip>
          <p>
            The Manage Benefits tab is where you define the eligibility logic
            used by your screener.
          </p>
          <p>
            <a
              href="https://bdt-docs.web.app/user-guide/#3-defining-eligibility-logic-manage-benefits"
              target="_blank"
            >
              Read about defining elegibility logic in the docs
            </a>
          </p>
        </Tooltip>
      </div>
      <div class="text-lg mb-3">
        Define and organize the benefits available in your screener. Each
        benefit can have associated eligibility checks.
      </div>
      <div
        data-testid="create-new-benefit-button"
        class="btn-default btn-blue mb-3 mr-1"
        onClick={() => {
          setAddingNewBenefit(true);
        }}
      >
        Create custom benefit
      </div>
      <div
        data-testid="add-library-benefit-button"
        class="btn-default btn-blue mb-3"
        onClick={() => setSelectExistingBenefitModal(true)}
      >
        Add library benefit
      </div>
      <div
        class="
          grid gap-4 justify-items-center
          grid-cols-1 md:grid-cols-2 xl:grid-cols-3"
      >
        <Show when={initialLoadStatus.loading() || actionInProgress()}>
          <Loading />
        </Show>
        <Show
          when={
            !initialLoadStatus.loading() &&
            (screenerBenefits() === null || screenerBenefits().length === 0)
          }
        >
          <div class="w-full flex text-gray-600 font-bold">
            No benefits found. Add a library benefit or create a custom one.
          </div>
        </Show>
        <For each={screenerBenefits()}>
          {(benefit) => {
            return (
              <BenefitCard
                benefit={benefit}
                setBenefitIdToConfigure={setBenefitIdToConfigure}
                setBenefitIdToRemove={setBenefitIdToRemove}
              />
            );
          }}
        </For>
      </div>
      {addingNewBenefit() && (
        <AddNewBenefitModal
          closeModal={() => setAddingNewBenefit(false)}
          addNewBenefit={actions.addNewBenefit}
        />
      )}
      {selectExistingBenefitModal() && (
        <SelectExistingBenefitModal
          closeModal={() => setSelectExistingBenefitModal(false)}
          importBenefit={actions.importBenefit}
        />
      )}
      {benefitIdToRemove() !== null && (
        <ConfirmationModal
          confirmationTitle="Remove Benefit"
          confirmationText="Are you sure you want to remove this benefit? This action cannot be undone."
          callback={() => actions.removeBenefit(benefitIdToRemove())}
          closeModal={() => setBenefitIdToRemove(null)}
        />
      )}
    </div>
  );
};

const BenefitCard = ({
  benefit,
  setBenefitIdToConfigure,
  setBenefitIdToRemove,
}: {
  benefit: BenefitDetail;
  setBenefitIdToConfigure: Setter<string>;
  setBenefitIdToRemove: Setter<string>;
}) => {
  return (
    <div class="w-full flex">
      <div
        class="
          max-w-lg flex-1 flex flex-col
          border-1 border-gray-300 rounded-lg"
      >
        <div
          id={"benefit-card-details-" + benefit.id}
          class="p-4 border-bottom border-gray-300 flex-1"
        >
          <div class="text-2xl mb-2 font-bold">{benefit.name}</div>
          <div>
            <span class="font-bold">Description:</span> {benefit.description}
          </div>
        </div>
        <div
          id={"benefit-card-actions-" + benefit.id}
          class="p-4 flex justify-end space-x-2"
        >
          <div
            data-testid={`edit-benefit-${benefit.id}`}
            class="btn-default btn-gray"
            onClick={() => {
              setBenefitIdToConfigure(benefit.id);
            }}
          >
            Edit
          </div>
          <div
            class="btn-default btn-red"
            onClick={() => {
              setBenefitIdToRemove(benefit.id);
            }}
          >
            Remove
          </div>
        </div>
      </div>
    </div>
  );
};

export default BenefitList;
