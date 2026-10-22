// @vitest-environment jsdom

import { MetaProvider } from "@solidjs/meta";
import { createMemoryHistory, MemoryRouter, Route } from "@solidjs/router";
import { render } from "solid-js/web";
import { afterEach, describe, expect, it, vi } from "vitest";

import ScreenerEditor from "./ScreenerEditor";
import { fetchScreener } from "@/api/screener";

vi.mock("@/api/screener", () => ({
  fetchScreener: vi.fn(async () => ({
    screenerName: "My screener",
    formSchema: {},
  })),
}));
vi.mock("./manageBenefits/benefitList/BenefitList", () => ({
  default: (props: {
    setBenefitIdToConfigure: (id: string) => void;
    onBenefitsChange: (benefits: { id: string; name: string }[]) => void;
  }) => (
    <>
      <button
        type="button"
        onClick={() => props.setBenefitIdToConfigure("benefit-1")}
      >
        Configure benefit-1
      </button>
      <button
        type="button"
        onClick={() =>
          props.onBenefitsChange([
            { id: "housing", name: "New housing benefit" },
          ])
        }
      >
        Add benefit
      </button>
    </>
  ),
}));
vi.mock("./manageBenefits/configureBenefit/ConfigureBenefit", () => ({
  default: (props: { benefitId: () => string }) => (
    <div>Configuring {props.benefitId()}</div>
  ),
}));
vi.mock("./FormEditorView", () => ({
  default: (props: {
    formSchema: () => unknown;
    setFormSchema: (schema: unknown) => void;
    benefits: () => { id: string; name: string }[];
  }) => (
    <div>
      <div data-testid="form-schema">{JSON.stringify(props.formSchema())}</div>
      <div data-testid="editor-benefits">
        {props
          .benefits()
          .map((benefit) => benefit.name)
          .join(", ")}
      </div>
      <button
        type="button"
        onClick={() => props.setFormSchema({ edited: true })}
      >
        Edit form
      </button>
    </div>
  ),
}));
vi.mock("./preview/Preview", () => ({ default: () => <div>Preview</div> }));
vi.mock("./Publish", () => ({
  default: (props: { refetchScreener: () => void }) => (
    <button type="button" onClick={() => props.refetchScreener()}>
      Refetch screener
    </button>
  ),
}));

describe("ScreenerEditor", () => {
  let dispose: (() => void) | undefined;

  afterEach(() => {
    dispose?.();
    document.body.replaceChildren();
  });

  function mount() {
    const history = createMemoryHistory();
    history.set({ value: "/screeners/example", replace: true });
    const container = document.body.appendChild(document.createElement("div"));
    dispose = render(
      () => (
        <MetaProvider>
          <MemoryRouter history={history}>
            <Route
              path="/screeners"
              component={() => <div>Screeners list</div>}
            />
            <Route path="/screeners/:screenerId" component={ScreenerEditor} />
          </MemoryRouter>
        </MetaProvider>
      ),
      container,
    );
    return container;
  }

  const currentCrumb = (container: HTMLElement) =>
    container.querySelector(".breadcrumb-current")?.textContent;

  const button = (container: HTMLElement, text: string) =>
    [...container.querySelectorAll("button")].find(
      (b) => b.textContent?.trim() === text,
    ) as HTMLButtonElement;

  it("shows the loading label only while the screener loads", async () => {
    let resolveScreener!: (screener: unknown) => void;
    vi.mocked(fetchScreener).mockReturnValueOnce(
      new Promise((resolve) => (resolveScreener = resolve)),
    );
    const container = mount();
    await vi.waitFor(() =>
      expect(currentCrumb(container)).toBe("Loading screener…"),
    );
    resolveScreener({ screenerName: "", formSchema: {} });
    await vi.waitFor(() => expect(currentCrumb(container)).toBe(""));
  });

  it("returns from Configure Benefit when Manage Benefits is selected", async () => {
    const container = mount();
    const button = (text: string) =>
      [...container.querySelectorAll("button")].find(
        (b) => b.textContent === text,
      ) as HTMLButtonElement;

    await vi.waitFor(() => expect(button("Configure benefit-1")).toBeDefined());
    button("Configure benefit-1").click();
    expect(container.textContent).toContain("Configuring benefit-1");

    button("Manage Benefits").click();
    expect(container.textContent).not.toContain("Configuring benefit-1");
    expect(button("Configure benefit-1")).toBeDefined();
  });

  it("can leave the editor from the breadcrumb", async () => {
    const errors: unknown[] = [];
    const onError = (event: ErrorEvent) => errors.push(event.error);
    window.addEventListener("error", onError);
    const container = mount();
    await vi.waitFor(() => expect(currentCrumb(container)).toBe("My screener"));

    const screenersLink = [...container.querySelectorAll("a")].find(
      (link) => link.textContent === "Screeners",
    )!;
    screenersLink.click();

    await vi.waitFor(() =>
      expect(container.textContent).toContain("Screeners list"),
    );
    window.removeEventListener("error", onError);
    expect(errors).toEqual([]);
    expect(fetchScreener).not.toHaveBeenCalledWith(undefined);
  });

  it("shows a recovery link when loading fails and can return to the list", async () => {
    vi.mocked(fetchScreener).mockRejectedValueOnce(new Error("Fetch failed"));
    const container = mount();

    await vi.waitFor(() => {
      expect(currentCrumb(container)).toBe("Screener unavailable");
      expect(container.querySelector('[role="alert"]')?.textContent).toContain(
        "Unable to load this screener",
      );
    });
    expect(container.textContent).not.toContain("Loading ...");
    const back = [...container.querySelectorAll("a")].find(
      (link) => link.textContent === "Back to screeners",
    )!;
    back.click();
    await vi.waitFor(() =>
      expect(container.textContent).toContain("Screeners list"),
    );
  });

  it("can retry a failed load", async () => {
    vi.mocked(fetchScreener).mockRejectedValueOnce(new Error("Fetch failed"));
    const container = mount();
    await vi.waitFor(() =>
      expect(container.querySelector('[role="alert"]')).not.toBeNull(),
    );
    const retry = [...container.querySelectorAll("button")].find(
      (button) => button.textContent === "Try again",
    )!;
    retry.click();

    await vi.waitFor(() => expect(currentCrumb(container)).toBe("My screener"));
    expect(container.querySelector('[role="alert"]')).toBeNull();
    expect(container.textContent).toContain("Configure benefit-1");
  });

  it("shows loading while retrying a failed load", async () => {
    vi.mocked(fetchScreener).mockRejectedValueOnce(new Error("Fetch failed"));
    const container = mount();
    await vi.waitFor(() =>
      expect(container.querySelector('[role="alert"]')).not.toBeNull(),
    );

    let resolveScreener!: (screener: unknown) => void;
    vi.mocked(fetchScreener).mockReturnValueOnce(
      new Promise((resolve) => (resolveScreener = resolve)),
    );
    button(container, "Try again").click();

    await vi.waitFor(() => {
      expect(container.textContent).toContain("Loading ...");
      expect(container.querySelector('[role="alert"]')).toBeNull();
    });
    resolveScreener({ screenerName: "My screener", formSchema: {} });
    await vi.waitFor(() => expect(currentCrumb(container)).toBe("My screener"));
  });

  it("keeps the editor and unsaved form edits across refetches", async () => {
    const container = mount();
    await vi.waitFor(() =>
      expect(button(container, "Form Editor")).toBeDefined(),
    );
    button(container, "Form Editor").click();
    button(container, "Edit form").click();
    const formSchema = () =>
      container.querySelector('[data-testid="form-schema"]')?.textContent;
    expect(formSchema()).toBe('{"edited":true}');

    button(container, "Publish").click();
    vi.mocked(fetchScreener).mockRejectedValueOnce(new Error("Fetch failed"));
    button(container, "Refetch screener").click();
    await vi.waitFor(() =>
      expect(button(container, "Refetch screener")).toBeDefined(),
    );
    expect(container.querySelector('[role="alert"]')).toBeNull();
    expect(currentCrumb(container)).toBe("My screener");

    vi.mocked(fetchScreener).mockResolvedValueOnce({
      screenerName: "Renamed screener",
      formSchema: { fromServer: true },
    });
    button(container, "Refetch screener").click();
    await vi.waitFor(() =>
      expect(currentCrumb(container)).toBe("Renamed screener"),
    );
    button(container, "Form Editor").click();
    expect(formSchema()).toBe('{"edited":true}');
  });

  it("gives the form editor the benefits changed in Manage Benefits", async () => {
    const container = mount();
    await vi.waitFor(() =>
      expect(button(container, "Add benefit")).toBeDefined(),
    );
    const fetches = vi.mocked(fetchScreener).mock.calls.length;
    button(container, "Add benefit").click();
    button(container, "Form Editor").click();
    expect(
      container.querySelector('[data-testid="editor-benefits"]')?.textContent,
    ).toBe("New housing benefit");
    expect(fetchScreener).toHaveBeenCalledTimes(fetches);
  });
});
