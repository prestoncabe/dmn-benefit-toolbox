import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CONNECT_TIMEOUT_MS, createScreeningBridge } from "./screenerBridge";
import { createCrmIntegration } from "../../public/integrations/host.js";

const results = {
  benefit: { name: "Benefit", result: "TRUE" as const, check_results: {} },
};

function setup(popup = false) {
  const hostPost = vi.fn();
  const childPost = vi.fn();
  const host = Object.assign(new EventTarget(), {
    location: { origin: "https://crm.example" },
    postMessage: hostPost,
  });
  const child = Object.assign(new EventTarget(), {
    location: {
      href: "https://bdt.example/screener/published-id?integrationOrigin=https%3A%2F%2Fcrm.example",
    },
    crypto: { getRandomValues: (bytes: Uint8Array) => bytes.fill(1) },
    parent: popup ? undefined : host,
    opener: popup ? host : null,
    postMessage: childPost,
  });
  if (popup) child.parent = child as any;
  const initialize = vi.fn();
  const onTimeout = vi.fn();
  const bridge = createScreeningBridge(
    "published-id",
    { initialize, onTimeout },
    child as any,
  )!;
  const dispatch = (
    target: EventTarget,
    source: unknown,
    origin: string,
    data: unknown,
  ) => {
    const event = new Event("message");
    Object.assign(event, { source, origin, data });
    target.dispatchEvent(event);
  };
  const init = {
    channel: "bdt.crm",
    version: 1,
    type: "initialize",
    screenerId: "published-id",
    sessionId: "01".repeat(16),
    requestId: "request-1",
    inputData: { people: { client: { age: 65 } } },
  };
  return {
    bridge,
    initialize,
    onTimeout,
    child,
    host,
    init,
    hostPost,
    childPost,
    send: (
      data = init,
      origin = host.location.origin,
      source: unknown = host,
    ) => dispatch(child, source, origin, data),
    receive: (
      data: unknown,
      origin = "https://bdt.example",
      source: unknown = child,
    ) => dispatch(host, source, origin, data),
  };
}

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("published screener CRM bridge", () => {
  it("repeats ready until a host initializes it", () => {
    const s = setup();
    s.bridge.start();
    vi.advanceTimersByTime(1000);
    expect(s.hostPost).toHaveBeenCalledTimes(3);
    s.send();
    expect(s.hostPost).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "initialized" }),
      "https://crm.example",
    );
    vi.advanceTimersByTime(CONNECT_TIMEOUT_MS);
    expect(s.hostPost).toHaveBeenCalledTimes(4);
    expect(s.onTimeout).not.toHaveBeenCalled();
    s.bridge.dispose();
  });

  it("reports an unavailable screener in place of initialized, or after it", () => {
    const before = setup();
    before.bridge.start();
    before.bridge.unavailable();
    before.send();
    expect(before.initialize).not.toHaveBeenCalled();
    expect(before.hostPost).toHaveBeenLastCalledWith(
      expect.objectContaining({
        type: "error",
        code: "SCREENER_UNAVAILABLE",
        requestId: "request-1",
      }),
      "https://crm.example",
    );
    expect(before.hostPost).not.toHaveBeenCalledWith(
      expect.objectContaining({ type: "initialized" }),
      expect.anything(),
    );
    before.bridge.dispose();

    const after = setup();
    after.bridge.start();
    after.send();
    after.bridge.unavailable();
    expect(after.hostPost).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: "error", code: "SCREENER_UNAVAILABLE" }),
      "https://crm.example",
    );
    after.bridge.dispose();
  });

  it("stops and reports a timeout when no host initializes it", () => {
    const s = setup(true);
    s.bridge.start();
    vi.advanceTimersByTime(CONNECT_TIMEOUT_MS);
    expect(s.onTimeout).toHaveBeenCalledOnce();
    const readyCount = s.hostPost.mock.calls.length;
    vi.advanceTimersByTime(CONNECT_TIMEOUT_MS);
    s.send();
    expect(s.hostPost).toHaveBeenCalledTimes(readyCount);
    expect(s.initialize).not.toHaveBeenCalled();
  });

  it.each([false, true])(
    "prefills once and correlates results for popup=%s",
    (popup) => {
      const s = setup(popup);
      s.bridge.start();
      expect(s.hostPost).toHaveBeenCalledWith(
        expect.objectContaining({ type: "ready", sessionId: "01".repeat(16) }),
        "https://crm.example",
      );
      s.bridge.result({}, results);
      expect(s.hostPost).toHaveBeenCalledTimes(1);
      s.send();
      expect(s.initialize).toHaveBeenCalledWith(s.init.inputData);
      expect(s.initialize.mock.calls[0][0]).not.toBe(s.init.inputData);
      s.send({ ...s.init, inputData: { age: 20 } } as any);
      expect(s.initialize).toHaveBeenCalledTimes(1);
      s.bridge.result(s.init.inputData, results);
      expect(s.hostPost).toHaveBeenLastCalledWith(
        expect.objectContaining({
          type: "result",
          requestId: "request-1",
          screenerId: "published-id",
          results,
          inputData: s.init.inputData,
          evaluatedAt: expect.any(String),
        }),
        "https://crm.example",
      );
      s.bridge.error();
      expect(s.hostPost).toHaveBeenLastCalledWith(
        expect.objectContaining({ type: "error", code: "EVALUATION_FAILED" }),
        "https://crm.example",
      );
      s.bridge.dispose();
      s.bridge.result({}, results);
      expect(s.hostPost).toHaveBeenCalledTimes(4);
    },
  );

  it("rejects wrong origins, sources, versions, sessions, screeners and unsafe payloads", () => {
    const s = setup();
    s.bridge.start();
    s.send(s.init, "https://untrusted.example");
    s.send(s.init, "https://crm.example", {});
    for (const change of [
      { channel: "other" },
      { version: 2 },
      { type: "result" },
      { sessionId: "old" },
      { screenerId: "other" },
      { requestId: "" },
      { requestId: "x".repeat(201) },
      { inputData: [] },
      { inputData: null },
      { inputData: { age: Infinity } },
      { inputData: JSON.parse('{"custom":{"__proto__":{"polluted":true}}}') },
    ])
      s.send({ ...s.init, ...change } as any);
    expect(s.initialize).not.toHaveBeenCalled();
    s.send();
    expect(s.initialize).toHaveBeenCalledTimes(1);
  });

  it("removes listeners on disposal", () => {
    const s = setup();
    s.bridge.start();
    s.bridge.dispose();
    s.send();
    expect(s.initialize).not.toHaveBeenCalled();
  });

  it.each([
    "",
    "*",
    "null",
    "https://crm.example/path",
    "https://crm.example/",
    "javascript:alert(1)",
  ])("does not enable integration with origin %s", (origin) => {
    const s = setup();
    s.child.location.href = `https://bdt.example/screener/id?integrationOrigin=${encodeURIComponent(origin)}`;
    expect(
      createScreeningBridge(
        "id",
        { initialize: vi.fn(), onTimeout: vi.fn() },
        s.child as any,
      ),
    ).toBeUndefined();
  });

  it("does not enable integration without a parent or opener", () => {
    const s = setup(true);
    s.child.opener = null as any;
    expect(
      createScreeningBridge(
        "id",
        { initialize: vi.fn(), onTimeout: vi.fn() },
        s.child as any,
      ),
    ).toBeUndefined();
  });
});

describe("CRM host adapter", () => {
  function connect(s: ReturnType<typeof setup>, options = {}) {
    const callbacks = {
      onResult: vi.fn(),
      onError: vi.fn(),
      onInitialized: vi.fn(),
    };
    const host = createCrmIntegration({
      screenerUrl: "https://bdt.example/screener/published-id",
      getTargetWindow: () => s.child as any,
      initialData: s.init.inputData,
      requestId: "request-1",
      hostWindow: s.host as any,
      ...callbacks,
      ...options,
    });
    return { host, ...callbacks };
  }

  it("keeps a session initialized when the screener repeats ready", () => {
    const s = setup();
    const c = connect(s);
    s.bridge.start();
    const ready = s.hostPost.mock.calls[0][0];
    s.receive(ready);
    s.receive(ready);
    expect(s.childPost).toHaveBeenCalledTimes(2);
    s.send(s.childPost.mock.calls[0][0]);
    s.receive(s.hostPost.mock.calls[1][0]);
    s.receive(ready);
    expect(s.childPost).toHaveBeenCalledTimes(2);
    s.bridge.result({}, results);
    s.receive(s.hostPost.mock.calls[2][0]);
    expect(c.onResult).toHaveBeenCalledOnce();
    c.host.dispose();
    s.bridge.dispose();
  });

  it("reports a screener answering from another origin once, without sending prefill", () => {
    const s = setup();
    const c = connect(s);
    s.bridge.start();
    const ready = s.hostPost.mock.calls[0][0];
    s.receive(ready, "https://www.bdt.example");
    s.receive(ready, "https://www.bdt.example");
    expect(c.onError).toHaveBeenCalledExactlyOnceWith({
      code: "ORIGIN_MISMATCH",
      origin: "https://www.bdt.example",
    });
    expect(s.childPost).not.toHaveBeenCalled();
    c.host.dispose();
    s.bridge.dispose();
  });

  it("reports an unavailable screener before initialization", () => {
    const s = setup();
    const c = connect(s, { connectTimeoutMs: 1000 });
    s.bridge.start();
    s.bridge.unavailable();
    s.receive(s.hostPost.mock.calls[0][0]);
    s.send(s.childPost.mock.calls[0][0]);
    s.receive(s.hostPost.mock.calls[1][0]);
    vi.advanceTimersByTime(1000);
    expect(c.onError).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ code: "SCREENER_UNAVAILABLE" }),
    );
    expect(c.onInitialized).not.toHaveBeenCalled();
    c.host.dispose();
    s.bridge.dispose();
  });

  it("reports a connection timeout unless the screener initializes", () => {
    const waiting = setup();
    const timedOut = connect(waiting, { connectTimeoutMs: 1000 });
    vi.advanceTimersByTime(1000);
    expect(timedOut.onError).toHaveBeenCalledExactlyOnceWith({
      code: "CONNECTION_TIMEOUT",
    });
    timedOut.host.dispose();

    const s = setup();
    const c = connect(s, { connectTimeoutMs: 1000 });
    s.bridge.start();
    s.receive(s.hostPost.mock.calls[0][0]);
    s.send(s.childPost.mock.calls[0][0]);
    s.receive(s.hostPost.mock.calls[1][0]);
    vi.advanceTimersByTime(1000);
    expect(c.onError).not.toHaveBeenCalled();
    c.host.dispose();
    s.bridge.dispose();

    const disposed = connect(setup(), { connectTimeoutMs: 1000 });
    disposed.host.dispose();
    vi.advanceTimersByTime(1000);
    expect(disposed.onError).not.toHaveBeenCalled();
  });

  it("accepts screener URLs under a base path and rejects other paths", () => {
    const options = {
      getTargetWindow: () => undefined,
      initialData: {},
      onResult: vi.fn(),
      hostWindow: setup().host as any,
    };
    const host = createCrmIntegration({
      ...options,
      screenerUrl: "https://org.example/bdt/screener/published-id",
    });
    expect(new URL(host.url).pathname).toBe("/bdt/screener/published-id");
    host.dispose();
    for (const screenerUrl of [
      "https://org.example/bdt/published-id",
      "https://org.example/screener/published-id/extra",
      "ftp://org.example/screener/published-id",
    ])
      expect(() => createCrmIntegration({ ...options, screenerUrl })).toThrow(
        "Expected an absolute published BDT screener URL",
      );
  });
  it("reports a blocked initialization and allows a later retry", () => {
    const s = setup(true);
    const onError = vi.fn();
    const onInitialized = vi.fn();
    const warning = vi.spyOn(console, "warn").mockImplementation(() => {});
    const host = createCrmIntegration({
      screenerUrl: "https://bdt.example/screener/published-id",
      getTargetWindow: () => s.child as any,
      initialData: s.init.inputData,
      requestId: "request-1",
      onResult: vi.fn(),
      onError,
      onInitialized,
      hostWindow: s.host as any,
    });
    try {
      s.childPost.mockImplementationOnce(() => {
        throw new Error("Blocked by host");
      });
      s.childPost.mockImplementationOnce(() => {
        throw new Error("Blocked by host");
      });
      s.bridge.start();
      const ready = s.hostPost.mock.calls[0][0];
      s.receive(ready);
      s.receive(ready);
      expect(onError).toHaveBeenCalledExactlyOnceWith({
        code: "INITIALIZATION_FAILED",
      });
      expect(onInitialized).not.toHaveBeenCalled();
      s.receive(ready);
      s.send(s.childPost.mock.calls[2][0]);
      s.receive(s.hostPost.mock.calls[1][0]);
      expect(onInitialized).toHaveBeenCalledOnce();
    } finally {
      warning.mockRestore();
      host.dispose();
      s.bridge.dispose();
    }
  });
  it.each([false, true])(
    "uses JSON envelopes for sandboxed hosts for popup=%s",
    (popup) => {
      const s = setup(popup);
      const onResult = vi.fn();
      const host = createCrmIntegration({
        screenerUrl: "https://bdt.example/screener/published-id",
        getTargetWindow: () => s.child as any,
        initialData: {
          people: { client: { enrollments: null } },
          custom: { householdIncome: 0 },
        },
        requestId: "request-1",
        serializeMessages: true,
        onResult,
        hostWindow: s.host as any,
      });
      s.bridge.start();
      s.receive(s.hostPost.mock.calls[0][0]);
      const initialization = s.childPost.mock.calls[0][0];
      expect(typeof initialization).toBe("string");
      s.send(initialization, "https://untrusted.example");
      s.send(initialization, "https://crm.example", {});
      s.send("invalid JSON" as any);
      s.send(
        JSON.stringify({
          ...s.init,
          inputData: JSON.parse('{"__proto__":{}}'),
        }) as any,
      );
      expect(s.initialize).not.toHaveBeenCalled();
      s.send(initialization);
      expect(s.initialize).toHaveBeenCalledWith(
        JSON.parse(initialization).inputData,
      );
      expect(typeof s.hostPost.mock.calls[1][0]).toBe("string");
      s.receive(s.hostPost.mock.calls[1][0]);
      s.bridge.result({}, results);
      const serialized = s.hostPost.mock.calls[2][0];
      s.receive("invalid JSON");
      s.receive(
        JSON.stringify({ ...JSON.parse(serialized), requestId: "wrong" }),
      );
      expect(onResult).not.toHaveBeenCalled();
      s.receive(serialized);
      expect(onResult).toHaveBeenCalledWith(
        expect.objectContaining({ results }),
      );
      host.dispose();
      s.bridge.dispose();
    },
  );
  it.each([false, true])(
    "completes the handshake and delivers live results for popup=%s",
    (popup) => {
      const s = setup(popup);
      const onResult = vi.fn();
      const onError = vi.fn();
      const onInitialized = vi.fn();
      const host = createCrmIntegration({
        screenerUrl: "https://bdt.example/screener/published-id",
        getTargetWindow: () => s.child as any,
        initialData: s.init.inputData,
        requestId: "request-1",
        onResult,
        onError,
        onInitialized,
        hostWindow: s.host as any,
      });
      expect(new URL(host.url).searchParams.get("integrationOrigin")).toBe(
        "https://crm.example",
      );
      s.bridge.start();
      const ready = s.hostPost.mock.calls[0][0];
      s.receive(ready, "https://untrusted.example");
      s.receive(ready, "https://bdt.example", {});
      s.receive({ ...ready, version: 2 });
      expect(s.childPost).not.toHaveBeenCalled();
      expect(onError).toHaveBeenCalledExactlyOnceWith({
        code: "ORIGIN_MISMATCH",
        origin: "https://untrusted.example",
      });
      s.receive(ready);
      expect(s.childPost).toHaveBeenCalledWith(s.init, "https://bdt.example");
      s.send(s.childPost.mock.calls[0][0]);
      s.receive(s.hostPost.mock.calls[1][0]);
      expect(onInitialized).toHaveBeenCalledOnce();
      s.bridge.result(s.init.inputData, results);
      const result = s.hostPost.mock.calls[2][0];
      s.receive({ ...result, requestId: "different" });
      s.receive({ ...result, sessionId: "old" });
      s.receive({ ...result, results: [] });
      expect(onResult).not.toHaveBeenCalled();
      s.receive(result);
      expect(onResult).toHaveBeenCalledWith(result);
      s.bridge.error();
      s.receive(s.hostPost.mock.calls[3][0]);
      expect(onError).toHaveBeenCalledTimes(2);
      expect(onError).toHaveBeenLastCalledWith(
        expect.objectContaining({ code: "EVALUATION_FAILED" }),
      );
      host.dispose();
      s.receive(result);
      expect(onResult).toHaveBeenCalledOnce();
      s.bridge.dispose();
    },
  );
});
