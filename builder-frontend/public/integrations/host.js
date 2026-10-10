// CRMs copy this file as-is, so it stays self-contained instead of importing
// the app's helpers.
function randomId() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

/**
 * BDT CRM host adapter. Install the listener before navigating the iframe/popup.
 * Copy this module into your CRM's source tree if remote modules are restricted.
 * @param {{
 *   screenerUrl: string,
 *   getTargetWindow: () => Window | null | undefined,
 *   initialData: Record<string, unknown>,
 *   requestId?: string,
 *   serializeMessages?: boolean,
 *   onResult: (message: any) => void,
 *   onError?: (message: any) => void,
 *   onInitialized?: () => void,
 *   hostWindow?: Window
 * }} options
 */
export function createCrmIntegration({
  screenerUrl,
  getTargetWindow,
  initialData,
  requestId = randomId(),
  serializeMessages = false,
  onResult,
  onError = () => {},
  onInitialized = () => {},
  hostWindow = window,
}) {
  const url = new URL(screenerUrl);
  // Allow a base path or proxy prefix before /screener/{id}.
  const match = url.pathname.match(/\/screener\/([^/]+)\/?$/);
  if (!match || !["http:", "https:"].includes(url.protocol)) {
    throw new Error("Expected an absolute published BDT screener URL");
  }
  if (typeof requestId !== "string" || !requestId || requestId.length > 200) {
    throw new Error("Expected a nonempty requestId of at most 200 characters");
  }
  if (
    !initialData ||
    typeof initialData !== "object" ||
    Array.isArray(initialData)
  ) {
    throw new Error("Expected initialData to be a JSON object");
  }
  const screenerId = decodeURIComponent(match[1]);
  url.searchParams.set("integrationOrigin", hostWindow.location.origin);
  // A new launch must reload an existing iframe even when its screener is unchanged.
  url.searchParams.set("integrationLaunch", randomId());
  let sessionId;
  let initialized = false;
  const receive = (event) => {
    const target = getTargetWindow();
    if (!target || event.origin !== url.origin || event.source !== target)
      return;
    let message = event.data;
    if (typeof message === "string") {
      try {
        message = JSON.parse(message);
      } catch {
        return;
      }
    }
    if (
      !message ||
      message.channel !== "bdt.crm" ||
      message.version !== 1 ||
      message.screenerId !== screenerId ||
      typeof message.sessionId !== "string" ||
      !message.sessionId
    )
      return;
    if (message.type === "ready") {
      sessionId = message.sessionId;
      initialized = false;
      const initialization = {
        channel: "bdt.crm",
        version: 1,
        type: "initialize",
        screenerId,
        sessionId,
        requestId,
        inputData: initialData,
      };
      try {
        target.postMessage(
          serializeMessages ? JSON.stringify(initialization) : initialization,
          url.origin,
        );
      } catch (error) {
        console.warn("BDT initialization could not be sent", error);
        onError({ code: "INITIALIZATION_FAILED" });
      }
      return;
    }
    if (message.sessionId !== sessionId || message.requestId !== requestId)
      return;
    if (message.type === "initialized") {
      initialized = true;
      onInitialized();
    } else if (
      initialized &&
      message.type === "result" &&
      message.results &&
      typeof message.results === "object" &&
      !Array.isArray(message.results) &&
      message.inputData &&
      typeof message.inputData === "object" &&
      !Array.isArray(message.inputData) &&
      typeof message.evaluatedAt === "string"
    ) {
      onResult(message);
    } else if (
      initialized &&
      message.type === "error" &&
      message.code === "EVALUATION_FAILED"
    ) {
      onError(message);
    }
  };
  hostWindow.addEventListener("message", receive);
  return {
    url: url.href,
    dispose() {
      hostWindow.removeEventListener("message", receive);
    },
  };
}
