// @vitest-environment jsdom
import { onMount } from "solid-js";
import { render } from "solid-js/web";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Screener from "./Screener";
import {
  evaluatePublishedScreener,
  fetchPublishedScreener,
} from "@/api/publishedScreener";
import type { ScreenerResult } from "@/types";

const integration = vi.hoisted(() => ({
  enabled: true,
  initialize: undefined as
    | undefined
    | ((data: Record<string, unknown>) => void),
  disconnect: undefined as undefined | ((reason: string) => void),
  start: vi.fn(),
  dispose: vi.fn(),
  result: vi.fn(),
  error: vi.fn(),
  unavailable: vi.fn(),
}));

vi.mock("@solidjs/router", () => ({
  useParams: () => ({ publishedScreenerId: "published-id" }),
}));
vi.mock("@/api/publishedScreener", () => ({
  fetchPublishedScreener: vi.fn(async () => ({
    screenerName: "Test",
    formSchema: {},
  })),
  evaluatePublishedScreener: vi.fn(),
}));
vi.mock("@/integrations/screenerBridge", () => ({
  createScreeningBridge: (
    _: string,
    callbacks: {
      initialize: () => void;
      onDisconnect: (reason: string) => void;
    },
  ) => {
    integration.initialize = callbacks.initialize;
    integration.disconnect = callbacks.onDisconnect;
    return integration.enabled ? integration : undefined;
  },
}));
vi.mock("./FormRenderer", () => ({
  default: (props: any) => {
    onMount(() => {
      if (props.evaluateInitialData) props.submitForm(props.formData());
    });
    return (
      <button
        onClick={() => props.submitForm({ custom: { householdIncome: 50000 } })}
      >
        Change answer
      </button>
    );
  },
}));

const result = (value: "TRUE" | "FALSE"): ScreenerResult => ({
  benefit: { name: "Benefit", result: value, check_results: {} },
});
const initialData = { custom: { householdIncome: 30000 } };
function deferred() {
  let resolve!: (value: ScreenerResult) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<ScreenerResult>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

describe("published screener integration lifecycle", () => {
  let container: HTMLDivElement;
  let dispose: () => void;
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(evaluatePublishedScreener).mockReset();
    integration.enabled = true;
    container = document.createElement("div");
    document.body.append(container);
  });
  afterEach(() => {
    dispose?.();
    container.remove();
  });

  it("waits for prefill, then ignores an old response when a newer evaluation finishes", async () => {
    const old = deferred();
    vi.mocked(evaluatePublishedScreener)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(result("FALSE"));
    dispose = render(() => <Screener />, container);
    await vi.waitFor(() => expect(integration.start).toHaveBeenCalledOnce());
    expect(container.textContent).toContain("Waiting for information");
    expect(evaluatePublishedScreener).not.toHaveBeenCalled();
    integration.initialize!(initialData);
    await vi.waitFor(() =>
      expect(evaluatePublishedScreener).toHaveBeenCalledWith(
        "published-id",
        initialData,
      ),
    );
    container.querySelector("button")!.click();
    await vi.waitFor(() => expect(integration.result).toHaveBeenCalledOnce());
    expect(integration.result).toHaveBeenLastCalledWith(
      { custom: { householdIncome: 50000 } },
      result("FALSE"),
    );
    old.resolve(result("TRUE"));
    await old.promise;
    expect(integration.result).toHaveBeenCalledOnce();
    expect(container.textContent).toContain("Ineligible");
  });

  it("ignores old failures and pending responses after unmount", async () => {
    const old = deferred();
    const current = deferred();
    vi.mocked(evaluatePublishedScreener)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    dispose = render(() => <Screener />, container);
    integration.initialize!(initialData);
    await vi.waitFor(() =>
      expect(evaluatePublishedScreener).toHaveBeenCalledOnce(),
    );
    container.querySelector("button")!.click();
    old.reject(new Error("Old request failed"));
    await old.promise.catch(() => {});
    expect(integration.error).not.toHaveBeenCalled();
    dispose();
    expect(integration.dispose).toHaveBeenCalledOnce();
    current.resolve(result("TRUE"));
    await current.promise;
    expect(integration.result).not.toHaveBeenCalled();
  });

  it("tells the user and the host when the screener can't be loaded", async () => {
    vi.mocked(fetchPublishedScreener).mockRejectedValueOnce(
      new Error("Fetch failed with status: 404"),
    );
    dispose = render(() => <Screener />, container);
    await vi.waitFor(() =>
      expect(integration.unavailable).toHaveBeenCalledOnce(),
    );
    expect(container.textContent).toContain("couldn’t be loaded");
    expect(container.textContent).not.toContain("Waiting for information");
    integration.initialize!(initialData);
    await Promise.resolve();
    expect(container.querySelector("button")).toBeNull();
  });

  it("offers the form unconnected when no host initializes it", async () => {
    vi.mocked(evaluatePublishedScreener).mockResolvedValue(result("TRUE"));
    dispose = render(() => <Screener />, container);
    await vi.waitFor(() => expect(integration.start).toHaveBeenCalledOnce());
    integration.disconnect!("timeout");
    await vi.waitFor(() =>
      expect(container.querySelector("button")).not.toBeNull(),
    );
    expect(container.textContent).not.toContain("Waiting for information");
    expect(container.textContent).toContain("couldn’t connect");
    expect(evaluatePublishedScreener).not.toHaveBeenCalled();
  });

  it("keeps the form after the host closes the connection and says so", async () => {
    vi.mocked(evaluatePublishedScreener).mockResolvedValue(result("TRUE"));
    dispose = render(() => <Screener />, container);
    integration.initialize!(initialData);
    await vi.waitFor(() =>
      expect(evaluatePublishedScreener).toHaveBeenCalledOnce(),
    );
    integration.disconnect!("closed");
    await vi.waitFor(() =>
      expect(container.textContent).toContain("no longer connected"),
    );
    expect(container.querySelector("button")).not.toBeNull();
    expect(evaluatePublishedScreener).toHaveBeenCalledOnce();
  });

  it("keeps the standalone form available without waiting or initial evaluation", async () => {
    integration.enabled = false;
    vi.mocked(evaluatePublishedScreener).mockResolvedValue(result("TRUE"));
    dispose = render(() => <Screener />, container);
    await vi.waitFor(() =>
      expect(container.querySelector("button")).not.toBeNull(),
    );
    expect(container.textContent).not.toContain("Waiting for information");
    expect(evaluatePublishedScreener).not.toHaveBeenCalled();
    container.querySelector("button")!.click();
    await vi.waitFor(() => expect(container.textContent).toContain("Eligible"));
    expect(integration.result).not.toHaveBeenCalled();
  });
});
