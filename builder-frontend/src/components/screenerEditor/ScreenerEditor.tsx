import EditorNavigation from "@/components/shared/EditorNavigation";
import { createSignal, createResource, Accessor } from "solid-js";
import { A, useParams } from "@solidjs/router";

import FormEditorView from "./FormEditorView";
import Loading from "../Loading";
import ManageBenefits from "./manageBenefits/ManageBenefits";
import Preview from "./preview/Preview";
import Publish from "./Publish";

import { fetchScreener } from "@/api/screener";
import { NavbarProps } from "@/components/shared/BdtNavbar";
import { Title } from "@solidjs/meta";

type TabOption = "manageBenefits" | "formEditor" | "preview" | "publish";

function ScreenerEditor() {
  const params = useParams();

  const [activeTab, setActiveTab] = createSignal<TabOption>("manageBenefits");
  // Kept here rather than in ManageBenefits so that selecting the Manage
  // Benefits section also returns from Configure Benefit to the list.
  const [benefitIdToConfigure, setBenefitIdToConfigure] = createSignal<
    string | null
  >(null);
  const [formSchema, setFormSchema] = createSignal();
  const [forceUpdate, setForceUpdate] = createSignal(0);

  // The last screener loaded for this route. Keeping it lets the editor stay
  // open when a later refetch (such as after publishing) fails.
  const [loaded, setLoaded] = createSignal<{ id: string; screener: any }>();
  const loadedScreener = () =>
    loaded()?.id === params.screenerId ? loaded()?.screener : undefined;

  const fetchAndCacheScreener = async (keys) => {
    const screenerData = await fetchScreener(keys[0]);
    // Only take the form from the server on first load so that refetches
    // don't discard unsaved form edits.
    if (loaded()?.id !== keys[0]) setFormSchema(screenerData.formSchema);
    setLoaded({ id: keys[0], screener: screenerData });
    return screenerData;
  };

  const [screener] = createResource(
    // Using resrouce to more easily track states during refetch
    // However resources only refetch when key has changed.
    // In order to force refetch even thought he screenerId hasn't change,
    // including a dummy signal 'forceUpdate' that can be unique for
    // each call to the refetch. Navigating away clears screenerId before the
    // editor unmounts, so skip fetching without one.
    () => params.screenerId && [params.screenerId, forceUpdate()],
    fetchAndCacheScreener,
  );

  const screenerLabel = () =>
    loadedScreener()?.screenerName ??
    (screener.loading ? "Loading screener…" : "Screener unavailable");

  const navbarDefs: Accessor<NavbarProps> = () => {
    return {
      tabDefs: [
        {
          key: "manageBenefits",
          label: "Manage Benefits",
          onClick: () => {
            setActiveTab("manageBenefits");
            setBenefitIdToConfigure(null);
          },
        },
        {
          key: "formEditor",
          label: "Form Editor",
          onClick: () => setActiveTab("formEditor"),
        },
        {
          key: "preview",
          label: "Preview",
          onClick: () => setActiveTab("preview"),
        },
        {
          key: "publish",
          label: "Publish",
          onClick: () => setActiveTab("publish"),
        },
      ],
      activeTabKey: () => activeTab(),
    };
  };

  return (
    <div class="h-screen flex flex-col">
      <EditorNavigation
        navProps={
          screener.loading || !loadedScreener() ? undefined : navbarDefs
        }
        items={[
          { label: "Screeners", href: "/screeners" },
          { label: screenerLabel() },
        ]}
      />
      {screener.loading ? (
        <Loading />
      ) : loadedScreener() ? (
        <>
          <Title>BDT - {loadedScreener()?.screenerName}</Title>
          {activeTab() == "formEditor" && (
            <FormEditorView
              formSchema={formSchema}
              setFormSchema={setFormSchema}
            />
          )}
          {activeTab() == "manageBenefits" && (
            <ManageBenefits
              benefitIdToConfigure={benefitIdToConfigure}
              setBenefitIdToConfigure={setBenefitIdToConfigure}
            />
          )}
          {activeTab() == "preview" && (
            <Preview screener={loadedScreener} formSchema={formSchema} />
          )}
          {activeTab() == "publish" && (
            <Publish
              screener={loadedScreener}
              refetchScreener={() => setForceUpdate((prev) => prev + 1)}
              // Update the cached screener; a refetch would remount this tab.
              setIntegrationOrigins={(integrationOrigins: string[]) =>
                setLoaded(
                  (current) =>
                    current && {
                      ...current,
                      screener: { ...current.screener, integrationOrigins },
                    },
                )
              }
            />
          )}
        </>
      ) : (
        <div role="alert" class="m-6 rounded-lg border border-gray-300 p-6">
          <h1 class="text-xl font-bold">Unable to load this screener</h1>
          <p class="mt-2">
            The screener may no longer exist, or the service may be unavailable.
          </p>
          <div class="mt-4 flex gap-4">
            <A href="/screeners" class="text-blue-600 underline">
              Back to screeners
            </A>
            <button
              type="button"
              class="text-blue-600 underline"
              onClick={() => setForceUpdate((prev) => prev + 1)}
            >
              Try again
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default ScreenerEditor;
