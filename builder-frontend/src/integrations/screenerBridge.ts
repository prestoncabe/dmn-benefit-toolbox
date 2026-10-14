import type { ScreenerResult } from "@/types";
import { isUnsafeObjectKey } from "@/utils/unsafeObjectKeys";

const CHANNEL = "bdt.crm";
const VERSION = 1;
const READY_INTERVAL_MS = 500;
export const CONNECT_TIMEOUT_MS = 10_000;

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

export type ConnectionFailure = "SCREENER_UNAVAILABLE" | "ORIGIN_NOT_ALLOWED";

export interface ScreeningBridge {
  /** The host origin requested by the page URL. */
  readonly origin: string;
  start(): void;
  dispose(): void;
  result(inputData: Record<string, unknown>, results: ScreenerResult): void;
  error(): void;
  /** Call before start(): answer the host's initialize with this error, then stop. */
  fail(code: ConnectionFailure): void;
}

/** Opt in using an exact host origin. Only the parent/opener can initialize. */
export function createScreeningBridge(
  screenerId: string,
  {
    initialize,
    onDisconnect,
  }: {
    initialize: (data: Record<string, unknown>) => void;
    /**
     * The bridge has stopped: no host initialized it in time ("timeout"), or
     * the host closed its connection ("closed").
     */
    onDisconnect: (reason: "timeout" | "closed") => void;
  },
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
  let failure: ConnectionFailure | undefined;
  let readyRetry: ReturnType<typeof setInterval> | undefined;
  let connectTimeout: ReturnType<typeof setTimeout> | undefined;
  const stopWaiting = () => {
    clearInterval(readyRetry);
    clearTimeout(connectTimeout);
  };
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
    if (!active || event.origin !== origin || event.source !== host) return;
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
      message.screenerId !== screenerId ||
      message.sessionId !== sessionId
    )
      return;
    if (message.type === "disconnect") {
      if (requestId && message.requestId !== requestId) return;
      dispose();
      onDisconnect("closed");
      return;
    }
    if (
      requestId ||
      message.type !== "initialize" ||
      typeof message.requestId !== "string" ||
      !message.requestId ||
      message.requestId.length > 200 ||
      !message.inputData ||
      Array.isArray(message.inputData) ||
      !isJsonData(message.inputData)
    )
      return;

    stopWaiting();
    requestId = message.requestId;
    jsonMessages = typeof event.data === "string";
    if (failure) {
      send("error", { code: failure });
      dispose();
      return;
    }
    initialize(structuredClone(message.inputData));
    send("initialized");
  };

  const dispose = () => {
    stopWaiting();
    active = false;
    browser.removeEventListener("message", receive);
  };

  return {
    origin,
    start() {
      if (active) return;
      active = true;
      browser.addEventListener("message", receive);
      send("ready");
      // Repeat ready for hosts that start listening late. Give up when no host
      // answers, e.g. after a reload once the host has disposed its connection.
      readyRetry = setInterval(() => send("ready"), READY_INTERVAL_MS);
      connectTimeout = setTimeout(() => {
        dispose();
        onDisconnect("timeout");
      }, CONNECT_TIMEOUT_MS);
    },
    dispose,
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
    fail(code) {
      failure = code;
    },
  };
}
