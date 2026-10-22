import { FormContext } from "@bpmn-io/form-js-viewer";
import { useContext } from "preact/hooks";
import { useSyncExternalStore } from "preact/compat";
import type { ScreeningResultsService } from "./screeningResults";

/** For Preact custom fields, in both the viewer and editor canvas. */
export function useScreeningResults() {
  // form-js 1.15 exports an editor-only useService. Field rendering shares this viewer context.
  const { getService } = useContext(FormContext);
  const service = getService("screeningResults") as ScreeningResultsService;
  return useSyncExternalStore(service.subscribe, service.getSnapshot);
}
