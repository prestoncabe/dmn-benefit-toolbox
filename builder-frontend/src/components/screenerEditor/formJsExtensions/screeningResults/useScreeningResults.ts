import { FormContext } from "@bpmn-io/form-js-viewer";
import { useContext } from "preact/hooks";
import { useSyncExternalStore } from "preact/compat";
import {
  EMPTY_SNAPSHOT,
  type ScreeningResultsService,
} from "./screeningResults";

const unavailable = {
  subscribe: () => () => {},
  getSnapshot: () => EMPTY_SNAPSHOT,
};

/** For Preact custom fields, in both the viewer and editor canvas. */
export function useScreeningResults() {
  // form-js 1.15 exports an editor-only useService. Field rendering shares this viewer context.
  const { getService } = useContext(FormContext);
  // Forms built without screeningResultsModule render fields with no results instead of failing.
  const service: Pick<ScreeningResultsService, "subscribe" | "getSnapshot"> =
    getService("screeningResults", false) ?? unavailable;
  return useSyncExternalStore(service.subscribe, service.getSnapshot);
}
