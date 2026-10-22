import { describe, expect, it, vi } from "vitest";
import type { ScreenerResult } from "@/types";
import {
  createEditorResults,
  SCREENING_RESULTS_CHANGED,
  ScreeningResultsService,
} from "./screeningResults";

function setup() {
  const listeners = new Set<() => void>();
  const bus = {
    fire: vi.fn(() => listeners.forEach((listener) => listener())),
    on: vi.fn((_event: string, listener: () => void) => {
      listeners.add(listener);
    }),
    off: vi.fn((_event: string, listener: () => void) => {
      listeners.delete(listener);
    }),
  };
  return { bus, service: new ScreeningResultsService(bus) };
}

const decided: ScreenerResult = {
  benefit: { name: "Benefit", result: "TRUE", check_results: {} },
};

describe("screening results service", () => {
  it("retains previous results while the latest answers are pending", () => {
    const { bus, service } = setup();
    const listener = vi.fn();
    const stop = service.subscribe(listener);
    service.update(decided, false);
    const settled = service.getSnapshot();
    expect(settled).toEqual({
      results: decided,
      pending: false,
      failed: false,
      mode: "live",
    });
    service.update(decided, true);
    expect(service.getSnapshot()).toEqual({
      results: decided,
      pending: true,
      failed: false,
      mode: "live",
    });
    expect(settled.pending).toBe(false);
    // Sending the same state is a no-op.
    service.update(decided, true);
    expect(listener).toHaveBeenCalledTimes(2);
    // A failed request leaves the caller pending but says so.
    service.update(decided, true, true);
    expect(service.getSnapshot()).toEqual({
      results: decided,
      pending: true,
      failed: true,
      mode: "live",
    });
    expect(listener).toHaveBeenCalledTimes(3);
    const latest = {
      benefit: { ...decided.benefit, result: "UNABLE_TO_DETERMINE" as const },
    };
    service.update(latest, false);
    expect(service.getSnapshot()).toEqual({
      results: latest,
      pending: false,
      failed: false,
      mode: "live",
    });
    expect(bus.fire).toHaveBeenLastCalledWith(SCREENING_RESULTS_CHANGED, {
      snapshot: service.getSnapshot(),
    });
    stop();
    service.update(latest, true);
    expect(listener).toHaveBeenCalledTimes(4);
    expect(bus.off).toHaveBeenCalledWith(SCREENING_RESULTS_CHANGED, listener);
  });

  it("gives fields a frozen copy that can't change the parent's results", () => {
    const { service } = setup();
    const parent: ScreenerResult = {
      benefit: { name: "Benefit", result: "TRUE", check_results: {} },
    };
    service.update(parent, false);
    const { results } = service.getSnapshot();
    expect(results).toEqual(parent);
    expect(() => {
      results!.benefit.result = "FALSE";
    }).toThrow(TypeError);
    expect(parent.benefit.result).toBe("TRUE");
    // The same parent results keep the same copy, so pending changes don't recopy.
    service.update(parent, true);
    expect(service.getSnapshot().results).toBe(results);
    expect(Object.isFrozen(parent)).toBe(false);
  });

  it("keeps live forms separate from each other and editor samples", () => {
    const live = setup().service;
    const editor = setup().service;
    editor.showEditorSamples([]);
    expect(editor.getSnapshot().mode).toBe("editor");
    expect(live.getSnapshot()).toEqual({
      results: undefined,
      pending: false,
      failed: false,
      mode: "live",
    });
    live.update(decided, true);
    expect(editor.getSnapshot().pending).toBe(false);
    expect(editor.getSnapshot().results).not.toEqual(decided);
  });
});

describe("editor sample results", () => {
  it("uses configured benefit IDs and names and covers all three outcomes", () => {
    const benefits = ["Housing", "Food", "Utilities"].map((name, i) => ({
      id: `benefit-${i}`,
      name,
      description: "",
    }));
    const results = createEditorResults(benefits);
    expect(Object.keys(results)).toEqual(benefits.map((benefit) => benefit.id));
    expect(Object.values(results).map((benefit) => benefit.name)).toEqual([
      "Housing",
      "Food",
      "Utilities",
    ]);
    expect(Object.values(results).map((benefit) => benefit.result)).toEqual([
      "TRUE",
      "FALSE",
      "UNABLE_TO_DETERMINE",
    ]);
    expect(Object.values(results["benefit-1"].check_results)[0].result).toBe(
      "FALSE",
    );
    expect(benefits[0]).toEqual({
      id: "benefit-0",
      name: "Housing",
      description: "",
    });
  });

  it("adds clearly named examples for outcomes the configured benefits don't cover", () => {
    const results = createEditorResults([
      { id: "housing", name: "Housing", description: "" },
    ]);
    expect(
      Object.entries(results).map(([id, benefit]) => [id, benefit.result]),
    ).toEqual([
      ["housing", "TRUE"],
      ["example-ineligible", "FALSE"],
      ["example-undecided", "UNABLE_TO_DETERMINE"],
    ]);
    expect(results["example-ineligible"].name).toBe(
      "Example ineligible benefit",
    );
  });

  it("provides clearly named examples before benefits are configured", () => {
    const samples = Object.values(createEditorResults([]));
    expect(samples).toHaveLength(3);
    expect(
      samples.every((benefit) => benefit.name.startsWith("Example ")),
    ).toBe(true);
  });
});
