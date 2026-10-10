import { createCrmIntegration } from "./host.js";
import { clientToScreenerData } from "./clientMapping.mjs";

const element = (id) => document.getElementById(id);
const frame = element("screener");
const frameViewport = element("screener-viewport");
const client = { id: "demo-client-1", name: "Alex Example", screening: null };
const storageKey = "bdt.crm.browser-demo.record.v1";
const connectionErrors = {
  INITIALIZATION_FAILED: () => "Could not connect to the screener. Try again.",
  CONNECTION_TIMEOUT: () =>
    "The screener didn’t connect. Check the screener URL and try again.",
  ORIGIN_MISMATCH: ({ origin }) =>
    `The screener URL redirects to ${origin}. Use that address instead.`,
};
let connection;
let popup;
let latest;
element("screener-url").value = "http://localhost:5173/screener/PUBLISHED-ID";

function readClientFields() {
  const monthlyIncome = element("monthly-income").valueAsNumber;
  return {
    id: client.id,
    full_name: client.name,
    birth_date: element("birth-date").value || null,
    home_city: element("home-city").value.trim() || null,
    monthly_income: Number.isFinite(monthlyIncome) ? monthlyIncome : null,
    cash_support_requested: element("cash-interest").value,
  };
}

function renderMapping() {
  const source = readClientFields();
  element("client-json").textContent = JSON.stringify(source, null, 2);
  element("prefill-json").textContent = JSON.stringify(
    clientToScreenerData(source),
    null,
    2,
  );
}

element("client-fields").addEventListener("input", renderMapping);
renderMapping();

function readRecord() {
  try {
    const stored = JSON.parse(localStorage.getItem(storageKey));
    if (
      stored?.id === client.id &&
      stored.screening?.results &&
      typeof stored.screening.results === "object" &&
      !Array.isArray(stored.screening.results)
    ) {
      return { ...client, screening: stored.screening };
    }
  } catch {
    // Missing or invalid browser storage leaves an empty demo record.
  }
  return client;
}

function renderTime(id, value) {
  const time = element(id);
  const date = new Date(value);
  if (typeof value === "string" && Number.isFinite(date.getTime())) {
    time.dateTime = date.toISOString();
    time.textContent = date.toLocaleString();
  } else {
    time.removeAttribute("datetime");
    time.textContent = "Unavailable";
  }
}

function renderRecord() {
  // Read the stored record, rather than displaying the latest unsaved message.
  const snapshot = readRecord().screening;
  element("record-state").textContent = snapshot
    ? "Screening saved"
    : "No screening saved";
  element("saved-details").hidden = !snapshot;
  element("record-empty").hidden = !!snapshot;
  element("clear-record").disabled = !snapshot;
  element("saved-results").replaceChildren();
  if (!snapshot) return;
  renderTime("saved-at", snapshot.savedAt);
  renderTime("evaluated-at", snapshot.evaluatedAt);
  element("saved-screener").textContent = snapshot.screenerId;
  for (const [id, benefit] of Object.entries(snapshot.results)) {
    if (!benefit || typeof benefit !== "object") continue;
    const item = document.createElement("li");
    item.className = "benefit";
    const name = document.createElement("h3");
    name.textContent = typeof benefit.name === "string" ? benefit.name : id;
    const status = document.createElement("span");
    const outcome =
      benefit.result === "TRUE"
        ? { label: "Eligible", style: "eligible" }
        : benefit.result === "FALSE"
          ? { label: "Ineligible", style: "ineligible" }
          : { label: "Needs information", style: "unknown" };
    status.className = `outcome ${outcome.style}`;
    status.textContent = outcome.label;
    item.append(name, status);
    element("saved-results").append(item);
  }
}

renderRecord();

function start(popupMode) {
  if (!element("monthly-income").reportValidity()) return;
  connection?.dispose();
  frame.hidden = true;
  frameViewport.hidden = true;
  frame.removeAttribute("src");
  latest = undefined;
  element("save").disabled = true;
  try {
    connection = createCrmIntegration({
      screenerUrl: element("screener-url").value,
      getTargetWindow: () => (popupMode ? popup : frame.contentWindow),
      initialData: clientToScreenerData(readClientFields()),
      onResult: (message) => {
        latest = message;
        const counts = { eligible: 0, ineligible: 0, unknown: 0 };
        for (const benefit of Object.values(message.results)) {
          counts[
            benefit?.result === "TRUE"
              ? "eligible"
              : benefit?.result === "FALSE"
                ? "ineligible"
                : "unknown"
          ]++;
        }
        element("save").disabled = false;
        element("status").textContent =
          `Unsaved results: ${counts.eligible} eligible, ${counts.ineligible} ineligible, ${counts.unknown} need information. ` +
          "Review them in the screener, then save to the demo record.";
      },
      onError: (message) => {
        latest = undefined;
        element("save").disabled = true;
        element("status").textContent =
          connectionErrors[message.code]?.(message) ??
          "Evaluation failed. Change an answer to retry.";
      },
    });
    element("status").textContent = "Waiting for screening results…";
    if (popupMode) {
      popup = window.open(connection.url, "_blank");
      if (!popup) {
        connection.dispose();
        element("status").textContent = "Allow popups, then try again.";
      }
    } else {
      frame.hidden = false;
      frameViewport.hidden = false;
      frame.src = connection.url;
    }
  } catch (error) {
    element("status").textContent = error.message;
  }
}

element("embed").addEventListener("click", () => start(false));
element("popup").addEventListener("click", () => start(true));
element("save").addEventListener("click", () => {
  if (!latest) return;
  try {
    localStorage.setItem(
      storageKey,
      JSON.stringify({
        ...client,
        screening: {
          requestId: latest.requestId,
          screenerId: latest.screenerId,
          evaluatedAt: latest.evaluatedAt,
          savedAt: new Date().toISOString(),
          results: latest.results,
        },
      }),
    );
    renderRecord();
    element("save").disabled = true;
    element("status").textContent = "Results saved to the demo client record.";
  } catch {
    element("status").textContent =
      "Could not save the demo record in this browser. Try again.";
  }
});
element("clear-record").addEventListener("click", () => {
  try {
    localStorage.removeItem(storageKey);
    renderRecord();
    element("save").disabled = !latest;
    element("status").textContent =
      "Saved results cleared from the demo record.";
  } catch {
    element("status").textContent =
      "Could not clear the demo record in this browser. Try again.";
  }
});
window.addEventListener("pagehide", () => connection?.dispose());
