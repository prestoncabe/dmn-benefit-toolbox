import { Accessor, createMemo, createSignal, Show } from "solid-js";

import FormRenderer from "./FormRenderer";
import Results from "./Results";

import { evaluateScreener } from "../../../api/screener";

import { PreviewFormData, ScreenerResult } from "./types";
import Tooltip from "@/components/shared/Tooltip";
import HiddenQuestionsNotice from "@/components/shared/HiddenQuestionsNotice";
import ScreeningComplete from "@/components/shared/ScreeningComplete";
import { hasNextSteps } from "../formJsExtensions/nextSteps/content";
import {
  getUnneededQuestionPaths,
  haveSameQuestionPaths,
} from "@/utils/questionVotes";

const Preview = ({ screener, formSchema }) => {
  const [lastInputDataSent, setLastInputDataSent] =
    createSignal<PreviewFormData>({});
  const [results, setResults] = createSignal<ScreenerResult>();
  const [resultsLoading, setResultsLoading] = createSignal(false);
  // True from an edit until results for the latest answers arrive. Hides the completion
  // message without blanking the Results panel on every keystroke.
  const [resultsStale, setResultsStale] = createSignal(false);
  const [evaluationFailed, setEvaluationFailed] = createSignal(false);
  let evaluationVersion = 0;
  const [showAllQuestions, setShowAllQuestions] = createSignal(false);
  const unneededQuestionPaths = createMemo(
    () => getUnneededQuestionPaths(results()),
    undefined,
    { equals: haveSameQuestionPaths },
  );
  const hiddenQuestionPaths = createMemo(
    () => (showAllQuestions() ? [] : unneededQuestionPaths()),
    undefined,
    { equals: haveSameQuestionPaths },
  );

  let schema: Accessor<any> = () => {
    if (formSchema()) {
      return formSchema();
    }
    return {
      components: [],
      exporter: { name: "form-js (https://demo.bpmn.io)", version: "1.15.0" },
      id: "Form_1sgem74",
      schemaVersion: 18,
      type: "default",
    };
  };

  const handleSubmitForm = async (data: PreviewFormData) => {
    const version = ++evaluationVersion;
    setLastInputDataSent(data);
    setResultsStale(true);
    setResultsLoading(true);
    try {
      const apiResult = await evaluateScreener(screener().id, data);
      if (version === evaluationVersion) {
        setResults(apiResult);
        setResultsStale(false);
        setEvaluationFailed(false);
      }
    } catch (error) {
      if (version === evaluationVersion) setEvaluationFailed(true);
      console.error("Could not evaluate the screener", error);
    } finally {
      if (version === evaluationVersion) setResultsLoading(false);
    }
  };

  return (
    <div>
      <div class="m-4 p-4 border-2 border-gray-200 rounded">
        <div class="text-lg text-gray-800 text-md font-bold">Form</div>
        <FormRenderer
          schema={schema}
          formData={lastInputDataSent}
          hiddenQuestionPaths={hiddenQuestionPaths}
          results={results}
          pending={resultsStale}
          failed={evaluationFailed}
          submitForm={handleSubmitForm}
          onDataChange={() => {
            evaluationVersion++;
            setResultsStale(true);
          }}
        />
        <Show when={evaluationFailed()}>
          <p role="alert" class="my-4 text-red-800">
            Could not evaluate the latest answers. Results below are from the
            last successful evaluation.
          </p>
        </Show>
        <Show when={!hasNextSteps(schema())}>
          <ScreeningComplete results={results} pending={resultsStale} />
        </Show>
        <HiddenQuestionsNotice
          unneededQuestionCount={() => unneededQuestionPaths().length}
          showAllQuestions={showAllQuestions}
          onToggleShowAllQuestions={() =>
            setShowAllQuestions((current) => !current)
          }
        />
      </div>
      <div class="m-4 p-4 border-2 border-gray-200 rounded">
        <div class="flex flex-row gap-2 items-baseline">
          <div class="text-lg text-gray-800 text-md font-bold">Results</div>
          <Tooltip>
            <p>
              The Preview tab provides a live test environment where you can
              interact with your screener exactly as an end user would, and
              verify that the eligibility logic produces the correct results.
            </p>
            <p>
              <a
                href="https://bdt-docs.web.app/user/user-guide/#6-previewing-your-screener"
                target="_blank"
              >
                Read about previewing your screener in the docs
              </a>
            </p>
          </Tooltip>
        </div>
        <Results
          inputData={lastInputDataSent}
          results={results}
          resultsLoading={resultsLoading}
        />
      </div>
    </div>
  );
};
export default Preview;
