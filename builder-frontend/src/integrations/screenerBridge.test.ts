import { describe, expect, it, vi } from "vitest";
import { createScreeningBridge } from "./screenerBridge";
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
  const bridge = createScreeningBridge(
    "published-id",
    initialize,
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

describe("published screener CRM bridge", () => {
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
      createScreeningBridge("id", vi.fn(), s.child as any),
    ).toBeUndefined();
  });

  it("does not enable integration without a parent or opener", () => {
    const s = setup(true);
    s.child.opener = null as any;
    expect(
      createScreeningBridge("id", vi.fn(), s.child as any),
    ).toBeUndefined();
  });
});

describe("CRM host adapter", () => {
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
      s.bridge.start();
      const ready = s.hostPost.mock.calls[0][0];
      s.receive(ready);
      expect(onError).toHaveBeenCalledWith({ code: "INITIALIZATION_FAILED" });
      expect(onInitialized).not.toHaveBeenCalled();
      s.receive(ready);
      s.send(s.childPost.mock.calls[1][0]);
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
      expect(onError).toHaveBeenCalledOnce();
      host.dispose();
      s.receive(result);
      expect(onResult).toHaveBeenCalledOnce();
      s.bridge.dispose();
    },
  );
});
