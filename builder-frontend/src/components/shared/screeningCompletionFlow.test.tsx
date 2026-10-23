// @vitest-environment jsdom

import { render } from "solid-js/web";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ScreenerResult } from "@/types";

const renderer = vi.hoisted(() => ({ props: undefined as any }));
const previewResults = vi.hoisted(() => ({ props: undefined as any }));
vi.mock("@solidjs/router", () => ({
  useParams: () => ({ publishedScreenerId: "published" }),
}));
vi.mock("@/api/screener", () => ({ evaluateScreener: vi.fn() }));
vi.mock("@/api/publishedScreener", () => ({
  fetchPublishedScreener: vi.fn(),
  evaluatePublishedScreener: vi.fn(),
}));
vi.mock("@/components/screenerEditor/preview/FormRenderer", () => ({
  default: (props: any) => {
    renderer.props = props;
    return <div />;
  },
}));
vi.mock("@/components/screener/FormRenderer", () => ({
  default: (props: any) => {
    renderer.props = props;
    return <div />;
  },
}));
vi.mock("@/components/screenerEditor/preview/Results", () => ({
  default: (props: any) => {
    previewResults.props = props;
    return <div />;
  },
}));
vi.mock("@/components/screener/EligibilityResults", () => ({
  default: (props: any) => (
    <div data-testid="eligibility-results">
      {Object.keys(props.screenerResult() ?? {}).join(",")}
    </div>
  ),
}));
vi.mock("@/components/shared/Tooltip", () => ({ default: () => <div /> }));

import { evaluateScreener } from "@/api/screener";
import {
  evaluatePublishedScreener,
  fetchPublishedScreener,
} from "@/api/publishedScreener";
import Preview from "@/components/screenerEditor/preview/Preview";
import Screener from "@/components/screener/Screener";

describe.each(["preview", "published"])("completion in %s", (mode) => {
  let dispose: (() => void) | undefined;
  const api = () =>
    mode === "preview"
      ? vi.mocked(evaluateScreener)
      : vi.mocked(evaluatePublishedScreener);
  const decided: ScreenerResult = {
    benefit: { name: "Benefit", result: "TRUE", check_results: {} },
  };
  beforeEach(() => {
    vi.clearAllMocks();
    renderer.props = undefined;
    vi.mocked(fetchPublishedScreener).mockResolvedValue({
      id: "published",
      screenerName: "Test",
      formSchema: { components: [] },
    } as any);
  });
  afterEach(() => {
    dispose?.();
    document.body.replaceChildren();
  });

  async function mount(schema = { components: [] } as any) {
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () =>
        mode === "preview" ? (
          <Preview
            screener={() => ({ id: "working" })}
            formSchema={() => schema}
          />
        ) : (
          <Screener />
        ),
      container,
    );
    await vi.waitFor(() => expect(renderer.props).toBeDefined());
    return container;
  }

  it("uses a rendered component's heading instead of a second completion message", async () => {
    const schema = {
      components: [{ type: "group", components: [{ type: "next_steps" }] }],
    };
    vi.mocked(fetchPublishedScreener).mockResolvedValueOnce({
      screenerName: "Test",
      formSchema: schema,
    });
    api().mockResolvedValueOnce(decided);
    const container = await mount(schema);
    await renderer.props.submitForm({ answer: true });
    // Until the component renders (for example, while a condition hides it).
    expect(container.textContent).toContain("Screening complete");
    renderer.props.onNextStepsShownChange(true);
    expect(container.textContent).not.toContain("Screening complete");
    renderer.props.onNextStepsShownChange(false);
    expect(container.textContent).toContain("Screening complete");
  });

  it("ignores an older decided result while the latest answers remain undecided", async () => {
    let finishOlder!: (value: ScreenerResult) => void;
    let finishLatest!: (value: ScreenerResult) => void;
    api()
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finishOlder = resolve;
        }),
      )
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finishLatest = resolve;
        }),
      );
    const container = await mount();
    renderer.props.onDataChange();
    const older = renderer.props.submitForm({ answer: true });
    renderer.props.onDataChange();
    const latest = renderer.props.submitForm({ answer: false });
    finishOlder(decided);
    await older;
    expect(renderer.props.pending()).toBe(true);
    expect(renderer.props.results()).toBeUndefined();
    expect(container.querySelector('[role="status"]')).toBeNull();
    finishLatest({
      benefit: { ...decided.benefit, result: "UNABLE_TO_DETERMINE" },
    });
    await latest;
    expect(renderer.props.pending()).toBe(false);
    expect(renderer.props.results().benefit.result).toBe("UNABLE_TO_DETERMINE");
    expect(container.querySelector('[role="status"]')).toBeNull();
  });

  it("hides completion immediately on edit and does not restore it after an evaluation error", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(console, "log").mockImplementation(() => {});
    try {
      api()
        .mockResolvedValueOnce(decided)
        .mockRejectedValueOnce(new Error("Unavailable"));
      const container = await mount();
      await renderer.props.submitForm({ answer: true });
      expect(renderer.props.failed()).toBe(false);
      expect(container.querySelector('[role="status"]')?.textContent).toContain(
        "Screening complete",
      );
      renderer.props.onDataChange();
      expect(renderer.props.pending()).toBe(true);
      expect(container.querySelector('[role="status"]')).toBeNull();
      await renderer.props.submitForm({ answer: false });
      expect(renderer.props.pending()).toBe(true);
      expect(renderer.props.failed()).toBe(true);
      expect(renderer.props.results()).toEqual(decided);
      expect(container.querySelector('[role="status"]')).toBeNull();
    } finally {
      vi.restoreAllMocks();
    }
  });

  it("keeps the last results and reports a failed evaluation", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(console, "log").mockImplementation(() => {});
    try {
      api()
        .mockResolvedValueOnce(decided)
        .mockRejectedValueOnce(new Error("Unavailable"))
        .mockResolvedValueOnce(decided);
      const container = await mount();
      await renderer.props.submitForm({ answer: true });
      renderer.props.onDataChange();
      await renderer.props.submitForm({ answer: false });
      expect(container.querySelector('[role="alert"]')).not.toBeNull();
      if (mode === "preview") {
        expect(previewResults.props.results()).toEqual(decided);
      } else {
        expect(
          container.querySelector('[data-testid="eligibility-results"]')
            ?.textContent,
        ).toBe("benefit");
      }
      renderer.props.onDataChange();
      await renderer.props.submitForm({ answer: true });
      expect(container.querySelector('[role="alert"]')).toBeNull();
      expect(container.querySelector('[role="status"]')).not.toBeNull();
    } finally {
      vi.restoreAllMocks();
    }
  });
});

describe("preview results while typing", () => {
  let dispose: (() => void) | undefined;
  afterEach(() => {
    dispose?.();
    document.body.replaceChildren();
  });

  it("keeps showing results until the debounced evaluation starts", async () => {
    vi.clearAllMocks();
    renderer.props = undefined;
    vi.mocked(evaluateScreener).mockResolvedValue({
      benefit: { name: "Benefit", result: "TRUE", check_results: {} },
    });
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () => (
        <Preview
          screener={() => ({ id: "working" })}
          formSchema={() => ({ components: [] })}
        />
      ),
      container,
    );
    await vi.waitFor(() => expect(renderer.props).toBeDefined());
    await renderer.props.submitForm({ answer: true });
    renderer.props.onDataChange();
    expect(previewResults.props.resultsLoading()).toBe(false);
    expect(container.querySelector('[role="status"]')).toBeNull();
  });
});
