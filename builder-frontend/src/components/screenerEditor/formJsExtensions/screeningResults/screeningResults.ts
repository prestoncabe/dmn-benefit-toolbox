import { Accessor, createEffect } from "solid-js";
import type {
  BenefitDetail,
  BenefitResult,
  OptionalBoolean,
  ScreenerResult,
} from "@/types";

export const SCREENING_RESULTS_CHANGED = "screeningResults.changed";

export interface ScreeningResultsSnapshot {
  readonly results: ScreenerResult | undefined;
  /** True from an answer edit until results for the latest answers arrive, including failures. */
  readonly pending: boolean;
  readonly mode: "live" | "editor";
}

interface EventBus {
  fire(event: string, payload: { snapshot: ScreeningResultsSnapshot }): void;
  on(event: string, listener: () => void): void;
  off(event: string, listener: () => void): void;
}

/** One instance per form; results never enter answer data or the saved schema. */
export class ScreeningResultsService {
  static $inject = ["eventBus"];

  private snapshot: ScreeningResultsSnapshot = Object.freeze({
    results: undefined,
    pending: false,
    mode: "live",
  });

  constructor(private eventBus: EventBus) {}

  getSnapshot = (): ScreeningResultsSnapshot => this.snapshot;

  subscribe = (listener: () => void): (() => void) => {
    this.eventBus.on(SCREENING_RESULTS_CHANGED, listener);
    return () => this.eventBus.off(SCREENING_RESULTS_CHANGED, listener);
  };

  update(results: ScreenerResult | undefined, pending: boolean): void {
    this.publish({ results, pending, mode: "live" });
  }

  showEditorSamples(benefits: BenefitDetail[]): void {
    this.publish({
      results: createEditorResults(benefits),
      pending: false,
      mode: "editor",
    });
  }

  private publish(next: ScreeningResultsSnapshot): void {
    if (
      next.results === this.snapshot.results &&
      next.pending === this.snapshot.pending &&
      next.mode === this.snapshot.mode
    )
      return;
    this.snapshot = Object.freeze(next);
    this.eventBus.fire(SCREENING_RESULTS_CHANGED, { snapshot: this.snapshot });
  }
}

/** Use real benefit IDs so fields can match their content; no contacts or instructions are invented. */
export function createEditorResults(benefits: BenefitDetail[]): ScreenerResult {
  const examples = benefits.length
    ? benefits
    : [
        {
          id: "example-eligible",
          name: "Example eligible benefit",
          description: "",
        },
        {
          id: "example-ineligible",
          name: "Example ineligible benefit",
          description: "",
        },
        {
          id: "example-undecided",
          name: "Example undecided benefit",
          description: "",
        },
      ];
  const statuses: OptionalBoolean[] = ["TRUE", "FALSE", "UNABLE_TO_DETERMINE"];
  return Object.fromEntries(
    examples.map((benefit, index): [string, BenefitResult] => {
      const result = statuses[index % statuses.length];
      return [
        benefit.id,
        {
          name: benefit.name,
          result,
          check_results:
            result === "FALSE"
              ? {
                  "example-requirement": {
                    name: "Example requirement",
                    result: "FALSE",
                    module: "",
                    version: "",
                    parameters: {},
                    inputPaths: [],
                  },
                }
              : {},
        },
      ];
    }),
  );
}

/** Keeps a live form's service in step with its parent's results; call from the owning component. */
export function connectScreeningResults(
  form: { get<T>(name: string): T },
  live: {
    results: Accessor<ScreenerResult | undefined>;
    pending: Accessor<boolean>;
  },
): void {
  const service = form.get<ScreeningResultsService>("screeningResults");
  createEffect(() => service.update(live.results(), live.pending()));
}

export const screeningResultsModule = {
  __init__: ["screeningResults"],
  screeningResults: ["type", ScreeningResultsService],
};
