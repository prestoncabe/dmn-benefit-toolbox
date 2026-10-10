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
 *   connectTimeoutMs?: number,
 *   hostWindow?: Window
 * }} options
 *
 * onError receives `{ code }`: INITIALIZATION_FAILED, CONNECTION_TIMEOUT (no
 * screener initialized within connectTimeoutMs), ORIGIN_MISMATCH (the screener
 * answered from another origin, such as after a redirect; includes `origin`),
 * SCREENER_UNAVAILABLE (the published screener couldn't be loaded), or
 * EVALUATION_FAILED.
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
  connectTimeoutMs = 15_000,
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
  let failedSessionId;
  let reportedOriginMismatch = false;
  const connectTimer = setTimeout(() => {
    if (!initialized) onError({ code: "CONNECTION_TIMEOUT" });
  }, connectTimeoutMs);
  const send = (target, type, payload = {}) => {
    const message = {
      channel: "bdt.crm",
      version: 1,
      type,
      screenerId,
      sessionId,
      requestId,
      ...payload,
    };
    target.postMessage(
      serializeMessages ? JSON.stringify(message) : message,
      url.origin,
    );
  };
  const receive = (event) => {
    const target = getTargetWindow();
    if (!target || event.source !== target) return;
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
    if (event.origin !== url.origin) {
      // Never send prefill to an unexpected origin, but say why nothing happens.
      if (message.type === "ready" && !reportedOriginMismatch) {
        reportedOriginMismatch = true;
        onError({ code: "ORIGIN_MISMATCH", origin: event.origin });
      }
      return;
    }
    if (message.type === "ready") {
      // The screener repeats ready until initialized; a new session is a reload.
      if (message.sessionId === sessionId && initialized) return;
      if (message.sessionId !== sessionId) {
        sessionId = message.sessionId;
        initialized = false;
      }
      try {
        send(target, "initialize", { inputData: initialData });
      } catch (error) {
        if (failedSessionId === sessionId) return;
        failedSessionId = sessionId;
        console.warn("BDT initialization could not be sent", error);
        onError({ code: "INITIALIZATION_FAILED" });
      }
      return;
    }
    if (message.sessionId !== sessionId || message.requestId !== requestId)
      return;
    if (message.type === "initialized") {
      initialized = true;
      clearTimeout(connectTimer);
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
      message.type === "error" &&
      message.code === "SCREENER_UNAVAILABLE"
    ) {
      clearTimeout(connectTimer);
      onError(message);
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
      clearTimeout(connectTimer);
      hostWindow.removeEventListener("message", receive);
      // Tell an open screener that its answers no longer reach this host.
      const target = getTargetWindow();
      if (!sessionId || !target) return;
      try {
        send(target, "disconnect");
      } catch {
        // The screener window may already be closed or unreachable.
      }
    },
  };
}
