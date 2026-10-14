import { createSignal } from "solid-js";
import { useParams } from "@solidjs/router";
import { publishScreener } from "../../api/screener";
import Tooltip from "../shared/Tooltip";
import { Button } from "@/components/shared/Button";
import IntegrationOrigins from "./IntegrationOrigins";

export default function Publish({
  screener,
  refetchScreener,
  setIntegrationOrigins,
}) {
  const [isLoading, setIsLoading] = createSignal(false);

  const screenerName = () => {
    return screener()?.screenerName;
  };
  const isPublished = () => {
    return screener()?.publishedScreenerId !== null;
  };
  const lastPublishDate = () => {
    return screener()?.lastPublishDate;
  };
  const screenerUrl = () => {
    return (
      window.location.protocol +
      "//" +
      window.location.host +
      "/screener/" +
      screener()?.publishedScreenerId
    );
  };

  const { screenerId } = useParams();

  const handlePublish = async () => {
    try {
      setIsLoading(true);
      await publishScreener(screenerId);
      refetchScreener();
      setIsLoading(false);
    } catch (e) {
      setIsLoading(false);
    }
  };

  const formattedDate = (isoString) => {
    const trimmed = isoString.replace(/\.\d{3,}Z$/, "Z");
    const date = new Date(trimmed);

    return new Intl.DateTimeFormat("en-US", {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(date);
  };

  return (
    <div class="py-8 flex justify-center">
      <div class="px-8 py-4 w-xl border-1 shadow-sm border-gray-200">
        <div class="text-xl">{screenerName()}</div>
        <div class="mt-4 flex flex-col gap-2">
          <div id="screener-url-info" class="flex gap-4">
            <div class="text-sm font-bold">Screener URL:</div>
            {isPublished() ? (
              <a href={screenerUrl()} target="_blank" rel="noopener noreferrer">
                {screenerUrl()}
              </a>
            ) : (
              <a>Publish screener to create public url.</a>
            )}
          </div>
          <div id="screener-last-published-info" class="flex gap-4">
            <div class="text-sm font-bold">Last Published Date:</div>
            {lastPublishDate() ? (
              <div>{formattedDate(lastPublishDate())}</div>
            ) : (
              <div>Not yet published</div>
            )}
          </div>
        </div>
        <div class="mt-4 flex flex-col gap-2">
          <div class="flex flex-row gap-2 items-center">
            <Button
              variant="secondary"
              id="publish-screener-button"
              data-testid="publish-screener-button"
              onClick={handlePublish}
              disabled={isLoading()}
            >
              Publish Screener
            </Button>
            <Tooltip>
              <p>
                The Publish tab is where you deploy your screener to a publicly
                accessible URL that you can share with end users.
              </p>
              <p>
                <a
                  href="https://bdt-docs.web.app/user/user-guide/#7-publishing-your-screener"
                  target="_blank"
                >
                  Read about publishing your screener in the docs
                </a>
              </p>
            </Tooltip>
          </div>
          {lastPublishDate() ? (
            <div>Publish current working version to your public screener</div>
          ) : (
            <div>
              Click to make your screener available through a public URL
            </div>
          )}
        </div>
        <IntegrationOrigins
          screenerId={screenerId}
          origins={() => screener()?.integrationOrigins}
          onSaved={setIntegrationOrigins}
        />
      </div>
    </div>
  );
}
