import type { ScreenerResult } from "@/types";
import { isUnsafeObjectKey } from "@/utils/unsafeObjectKeys";

const CHANNEL = "bdt.crm";
const VERSION = 1;

// Prefill is JSON form data, never executable content or prototype properties.
function isJsonData(value: unknown, depth = 0): boolean {
  if (depth > 30) return false;
  if (value === null || typeof value === "string" || typeof value === "boolean")
    return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value))
    return value.every((item) => isJsonData(item, depth + 1));
  if (!value || typeof value !== "object") return false;
  if (Object.getPrototypeOf(value) !== Object.prototype) return false;
  return Object.entries(value).every(
    ([key, item]) => !isUnsafeObjectKey(key) && isJsonData(item, depth + 1),
  );
}

export interface ScreeningBridge {
  start(): void;
  dispose(): void;
  result(inputData: Record<string, unknown>, results: ScreenerResult): void;
  error(): void;
}

/** Opt in using an exact host origin. Only the parent/opener can initialize. */
export function createScreeningBridge(
  screenerId: string,
  initialize: (data: Record<string, unknown>) => void,
  browser: Window = window,
): ScreeningBridge | undefined {
  const originParam = new URL(browser.location.href).searchParams.get(
    "integrationOrigin",
  );
  if (!originParam) return;

  let origin: string;
  try {
    const url = new URL(originParam);
    if (
      url.origin !== originParam ||
      !["https:", "http:"].includes(url.protocol)
    )
      return;
    origin = url.origin;
  } catch {
    return;
  }

  const host = browser.parent !== browser ? browser.parent : browser.opener;
  if (!host) return;

  const sessionId = Array.from(
    browser.crypto.getRandomValues(new Uint8Array(16)),
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
  let requestId: string | undefined;
  let active = false;
  let jsonMessages = false;
  const send = (type: string, payload: Record<string, unknown> = {}) => {
    if (!active) return;
    const message = {
      channel: CHANNEL,
      version: VERSION,
      type,
      screenerId,
      sessionId,
      requestId,
      ...payload,
    };
    host.postMessage(jsonMessages ? JSON.stringify(message) : message, origin);
  };

  const receive = (event: MessageEvent) => {
    if (
      !active ||
      requestId ||
      event.origin !== origin ||
      event.source !== host
    )
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
      message.channel !== CHANNEL ||
      message.version !== VERSION ||
      message.type !== "initialize" ||
      message.screenerId !== screenerId ||
      message.sessionId !== sessionId ||
      typeof message.requestId !== "string" ||
      !message.requestId ||
      message.requestId.length > 200 ||
      !message.inputData ||
      Array.isArray(message.inputData) ||
      !isJsonData(message.inputData)
    )
      return;

    requestId = message.requestId;
    jsonMessages = typeof event.data === "string";
    initialize(structuredClone(message.inputData));
    send("initialized");
  };

  return {
    start() {
      if (active) return;
      active = true;
      browser.addEventListener("message", receive);
      send("ready");
    },
    dispose() {
      active = false;
      browser.removeEventListener("message", receive);
    },
    result(inputData, results) {
      if (requestId)
        send("result", {
          inputData,
          results,
          evaluatedAt: new Date().toISOString(),
        });
    },
    error() {
      if (requestId) send("error", { code: "EVALUATION_FAILED" });
    },
  };
}
