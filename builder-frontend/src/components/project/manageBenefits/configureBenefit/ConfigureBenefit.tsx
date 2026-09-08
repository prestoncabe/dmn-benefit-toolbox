import { Accessor, createResource, createSignal, For, Show } from "solid-js";

import SelectedEligibilityCheck from "./SelectedEligibilityCheck";
import EligibilityCheckListView from "./EligibilityCheckListView";
import Loading from "../../../Loading";

import BenefitResource from "./benefitResource";
import { fetchPublicChecks, fetchUserDefinedChecks } from "@/api/check";

import type { EligibilityCheckListMode } from "./EligibilityCheckListView";
import type { EligibilityCheck, ParameterValues } from "@/types";
import Tooltip from "@/components/shared/Tooltip";

const ConfigureBenefit = ({
  screenerId,
  benefitId,
  setBenefitId,
}: {
  screenerId: Accessor<string>;
  benefitId: Accessor<string>;
  setBenefitId: (benefitId: string | null) => void;
}) => {
  const { benefit, actions, actionInProgress, initialLoadStatus } =
    BenefitResource(screenerId, benefitId);

  const [checkListMode, setCheckListMode] =
    createSignal<EligibilityCheckListMode>("public");
  const [publicChecks] = createResource<EligibilityCheck[]>(fetchPublicChecks);
  const [userDefinedChecks] = createResource<EligibilityCheck[]>(() =>
    fetchUserDefinedChecks({ working: false }),
  );

  const onRemoveEligibilityCheck = (checkId: string) => {
    actions.removeCheck(checkId);
  };

  return (
    <>
      <Show when={initialLoadStatus.loading() || actionInProgress()}>
        <Loading />
      </Show>

      <Show when={benefit().id !== undefined && !initialLoadStatus.loading()}>
        <div class="p-5">
          <div class="flex mb-4">
            <div class="flex flex-row gap-2 items-baseline">
              <div class="text-3xl font-bold tracking-wide">
                Configure Benefit:{" "}
                {benefit() ? benefit().name : "No Benefit Found"}
              </div>
              <Tooltip>
                <p>
                  The Configure Benefit page is where you define the rules that
                  determine whether a user qualifies for a specific benefit.
                </p>
                <p>
                  <a
                    href="https://bdt-docs.web.app/user-guide/#4-configuring-a-benefit"
                    target="_blank"
                  >
                    Read about configuring a benefit in the docs
                  </a>
                </p>
              </Tooltip>
            </div>
            <div class="ml-auto">
              <div
                class="btn-default btn-gray"
                onClick={() => {
                  setBenefitId(null);
                }}
              >
                Back
              </div>
            </div>
          </div>
          <div class="flex gap-4 flex-col 2xl:flex-row">
            <div
              id="eligibility-check-list"
              class="flex-3 border-2 border-gray-200 rounded-lg h-min"
            >
              <EligibilityCheckListView
                mode={checkListMode}
                setMode={setCheckListMode}
                publicChecks={publicChecks}
                userDefinedChecks={userDefinedChecks}
                addCheck={actions.addCheck}
              />
            </div>
            <div id="selected-eligibility-checks" class="flex-2">
              <div class="px-4 pb-4 text-2xl font-bold">
                Selected eligibility checks for {benefit().name}
              </div>

              <div class="px-4" id="selected-eligibility-checks_container">
                {benefit() && benefit().checks.length === 0 && (
                  <div class="text-gray-500">
                    No eligibility checks selected. Use the checkboxes to add
                    checks to this benefit.
                  </div>
                )}
                {benefit().checks.length > 0 && (
                  <For each={benefit().checks}>
                    {(checkConfig, checkConfigIndex) => {
                      return (
                        <SelectedEligibilityCheck
                          checkConfig={() => checkConfig}
                          onRemove={() =>
                            onRemoveEligibilityCheck(checkConfig.checkId)
                          }
                          updateCheckConfigParams={(
                            newCheckData: ParameterValues,
                          ) => {
                            actions.updateCheckConfigParams(
                              checkConfig.checkId,
                              newCheckData,
                            );
                          }}
                          updateCheckConfigAlias={(
                            aliasName: string | null,
                          ) => {
                            actions.updateCheckConfigAlias(
                              checkConfig.checkId,
                              aliasName,
                            );
                          }}
                        />
                      );
                    }}
                  </For>
                )}
              </div>
            </div>
          </div>
        </div>
      </Show>
    </>
  );
};
export default ConfigureBenefit;
