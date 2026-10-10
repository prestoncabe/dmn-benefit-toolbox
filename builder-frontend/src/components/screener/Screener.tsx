import {
  createSignal,
  createResource,
  createMemo,
  createEffect,
  Show,
  onMount,
  onCleanup,
} from "solid-js";
import { useParams } from "@solidjs/router";

import FormRenderer from "./FormRenderer";
import Loading from "@/components/Loading";
import EligibilityResults from "./EligibilityResults";

import {
  fetchPublishedScreener,
  evaluatePublishedScreener,
} from "@/api/publishedScreener";

import HiddenQuestionsNotice from "@/components/shared/HiddenQuestionsNotice";
import ScreeningComplete from "@/components/shared/ScreeningComplete";

import type { PublishedScreener, ScreenerResult } from "@/types";
import {
  getUnneededQuestionPaths,
  haveSameQuestionPaths,
} from "@/utils/questionVotes";
import { createScreeningBridge } from "@/integrations/screenerBridge";

export default function Screener() {
  const params = useParams();

  const [screener] = createResource<PublishedScreener>(() =>
    fetchPublishedScreener(params.publishedScreenerId),
  );
  const [screenerResult, setScreenerResult] = createSignal<ScreenerResult>();
  const [formData, setFormData] = createSignal<any>({});
  const [disconnectReason, setDisconnectReason] = createSignal<
    "timeout" | "closed"
  >();
  const bridge = createScreeningBridge(params.publishedScreenerId, {
    initialize: (data) => {
      setFormData(data);
      setIntegrationReady(true);
    },
    // Keep the form usable, but make clear results no longer reach the host.
    onDisconnect: (reason) => {
      setDisconnectReason(reason);
      setIntegrationReady(true);
    },
  });
  const [integrationReady, setIntegrationReady] = createSignal(!bridge);
  let evaluationSequence = 0;
  onMount(() => bridge?.start());
  createEffect(() => {
    if (screener.error) bridge?.unavailable();
  });
  onCleanup(() => {
    evaluationSequence++;
    bridge?.dispose();
  });
  // True from an edit until results for the latest answers arrive, so a failed evaluation
  // keeps the previous results visible without announcing that screening is complete.
  const [resultsStale, setResultsStale] = createSignal(false);
  const [evaluationFailed, setEvaluationFailed] = createSignal(false);
  const [showAllQuestions, setShowAllQuestions] = createSignal(false);
  const unneededQuestionPaths = createMemo(
    () => getUnneededQuestionPaths(screenerResult()),
    undefined,
    { equals: haveSameQuestionPaths },
  );
  const hiddenQuestionPaths = createMemo(
    () => (showAllQuestions() ? [] : unneededQuestionPaths()),
    undefined,
    { equals: haveSameQuestionPaths },
  );

  const submitForm = async (data: any) => {
    const sequence = ++evaluationSequence;
    setResultsStale(true);
    try {
      setFormData(data);
      let evaluationResult: ScreenerResult = await evaluatePublishedScreener(
        params.publishedScreenerId,
        data,
      );
      if (sequence !== evaluationSequence) return;
      setScreenerResult(evaluationResult);
      setResultsStale(false);
      setEvaluationFailed(false);
      bridge?.result(data, evaluationResult);
    } catch (err) {
      if (sequence !== evaluationSequence) return;
      setEvaluationFailed(true);
      bridge?.error();
      console.log(err);
    }
  };

  return (
    <main class="mt-4">
      {screener.loading && <Loading />}
      <Show when={screener.error}>
        <p class="p-4" role="alert">
          This screener couldn’t be loaded. Check the link, or try again later.
        </p>
      </Show>
      <Show when={!integrationReady() && !screener.error}>
        <p class="p-4" role="status">
          Waiting for information from the connected application…
        </p>
      </Show>
      <Show when={!screener.error && disconnectReason()}>
        {(reason) => (
          <p class="p-4" role="alert">
            {reason() === "timeout"
              ? "This screener couldn’t connect to the application that opened it, so results won’t be sent back."
              : "This screening is no longer connected to the application that opened it, so new results won’t be sent back."}{" "}
            To connect, start the screening again from that application.
          </p>
        )}
      </Show>
      {!screener.error && screener() && integrationReady() && (
        <div class="flex flex-col lg:flex-row">
          <section class="flex-1 overflow-y-auto p-4">
            <FormRenderer
              schema={screener()?.formSchema || {}}
              formData={formData}
              hiddenQuestionPaths={hiddenQuestionPaths}
              submitForm={submitForm}
              evaluateInitialData={!!bridge && !disconnectReason()}
              onDataChange={() => {
                evaluationSequence++;
                setResultsStale(true);
              }}
            />
            <Show when={evaluationFailed()}>
              <p role="alert" class="my-4 text-red-800">
                We couldn't update your results. Check your connection, then
                change an answer to try again.
              </p>
            </Show>
            <ScreeningComplete
              results={screenerResult}
              pending={resultsStale}
            />
            <HiddenQuestionsNotice
              unneededQuestionCount={() => unneededQuestionPaths().length}
              showAllQuestions={showAllQuestions}
              onToggleShowAllQuestions={() =>
                setShowAllQuestions((current) => !current)
              }
            />
          </section>
          <Show when={screenerResult()}>
            <section class="flex-1 overflow-y-auto p-4">
              <EligibilityResults screenerResult={screenerResult} />
            </section>
          </Show>
        </div>
      )}
    </main>
  );
}
