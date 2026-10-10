---
title: CRM integrations
description: Prefill a published screener from a CRM and capture its evaluated results.
---

Published screeners support CRM integrations through HTTP endpoints and a
versioned browser message protocol. A CRM can embed a screener in an iframe or
open it in a separate window, prefill known answers, and receive evaluated results
to save to the client's record. BDT does not store CRM credentials or write CRM
records; the host application owns its input mapping and result persistence.

## HTTP endpoints

These existing public endpoints operate on a **published screener ID**, not the
editable screener's ID. No builder login is required.

| Method | Path                                            | Purpose                                            |
| ------ | ----------------------------------------------- | -------------------------------------------------- |
| GET    | `/api/published/screener/{publishedScreenerId}` | Published screener metadata including `formSchema` |
| POST   | `/api/published/{publishedScreenerId}/evaluate` | Evaluate a JSON object of form answers             |

```sh
curl --fail-with-body https://YOUR-API-HOST/api/published/PUBLISHED-ID/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"people":{"client":{"dateOfBirth":"1950-01-01"}},"simpleChecks":{"livesInPhiladelphiaPa":true}}'
```

The response is keyed by benefit ID:

```json
{
  "benefit-id": {
    "name": "Example benefit",
    "result": "UNABLE_TO_DETERMINE",
    "check_results": {
      "check-id0": {
        "name": "ExampleCheck",
        "aliasName": null,
        "result": "UNABLE_TO_DETERMINE",
        "module": "ExampleModule",
        "version": "1.0.0",
        "parameters": {},
        "effectiveParameters": {},
        "defaultedParameters": [],
        "inputPaths": ["people.client.dateOfBirth"]
      }
    }
  }
}
```

Results are `TRUE`, `FALSE`, or `UNABLE_TO_DETERMINE`. They represent eligibility
checks, not a submitted application or an approval. An unknown screener returns
404; evaluation failures return 500. The evaluation endpoint also returns 404
when the screener has no benefits. The API host can differ from the frontend
host; use the API URL configured for your deployment.

Use the form's configured keys to map CRM fields. A dotted form key such as
`people.client.dateOfBirth` needs a nested object, not a literal dotted JSON key.
Dates use `YYYY-MM-DD`, booleans use JSON booleans, and numbers use JSON numbers.
Leave unknown answers omitted or `null`. Do not turn missing answers into `false`
or zero. The API transforms the form's keyed `people` object into library input
arrays internally. For checklist inputs, `null` is unknown and `[]` is an explicit
empty selection; the browser form normalizes its checklist answers before evaluation.

## Browser host adapter

The reusable ES module is served by the BDT frontend at `/integrations/host.js`.
Copy it into your own application when your CRM requires locally bundled modules.
Create the adapter **before** navigating the iframe or opening the window so it
can receive the screener's ready message.
The adapter adds an opaque `integrationLaunch` marker to reload an existing
iframe for each new session; this marker contains no client information.

```js
import { createCrmIntegration } from "https://YOUR-BDT-HOST/integrations/host.js";

const frame = document.querySelector("iframe");
const clientId = currentClient.id; // Pin this record for the lifetime of the session.
const connection = createCrmIntegration({
  screenerUrl: "https://YOUR-BDT-HOST/screener/PUBLISHED-ID",
  getTargetWindow: () => frame.contentWindow,
  initialData: {
    people: { client: { dateOfBirth: currentClient.birthDate ?? null } },
  },
  onResult: (message) => {
    // Display/store this snapshot locally until the user chooses to save.
    showScreeningResults(clientId, {
      requestId: message.requestId,
      screenerId: message.screenerId,
      evaluatedAt: message.evaluatedAt,
      results: message.results,
    });
  },
  onError: () => showEvaluationError(),
});
frame.src = connection.url;
// Call connection.dispose() when the host view is closed or its client changes.
```

The host helper functions in this snippet (`showScreeningResults` and
`showEvaluationError`) belong to your CRM UI. Downloading and bundling the adapter
also avoids a dependency on cross-origin module fetching permissions.

For a separate tab/window, keep a reference to the opened window:

```js
let popup;
const connection = createCrmIntegration({
  screenerUrl: "https://YOUR-BDT-HOST/screener/PUBLISHED-ID",
  getTargetWindow: () => popup,
  initialData: {},
  onResult: (message) => showScreeningResults(clientId, message),
});
popup = window.open(connection.url, "_blank"); // Run from a user click.
if (!popup) connection.dispose(); // Show a popup-blocked message in your CRM.
```

The separate-window workflow needs `window.opener`: don't use `noopener`,
`noreferrer`, or a link opened manually in another tab. Deployment headers such
as Cross-Origin-Opener-Policy can sever this relationship. Use the iframe workflow
if your environment requires opener isolation. An iframe needs a hosting policy
that permits the CRM origin in CSP `frame-ancestors`, and a CRM policy permitting
the BDT origin in `frame-src`. CORS alone does not permit framing.

## Message protocol (version 1)

For custom integrations, navigate to:

```text
https://YOUR-BDT-HOST/screener/PUBLISHED-ID?integrationOrigin=https%3A%2F%2FYOUR-CRM-HOST
```

`integrationOrigin` must be an exact HTTP(S) origin, including the port when
needed, with no trailing slash or path. Use HTTPS in production. It opts the
page into communication with its direct parent (iframe) or opener (window).
Both sides must check `event.origin` and `event.source`. Send to an exact
`targetOrigin`; never use `"*"`. No applicant data or CRM credentials belong in
the URL. The configured origin is a messaging destination, not CRM authentication;
your host/backend still authorizes reads and writes to client records.

Every message is a plain object with these envelope fields:

| Field        | Value                                                                                 |
| ------------ | ------------------------------------------------------------------------------------- |
| `channel`    | `"bdt.crm"`                                                                           |
| `version`    | `1`                                                                                   |
| `type`       | One of the types below                                                                |
| `screenerId` | Published screener ID                                                                 |
| `sessionId`  | Random ID generated by the screener for this page load                                |
| `requestId`  | Host's nonempty opaque correlation string (maximum 200 characters), except on `ready` |

| Direction       | Type          | Additional fields / behavior                                  |
| --------------- | ------------- | ------------------------------------------------------------- |
| Screener → host | `ready`       | Host can now send prefill using this `sessionId`              |
| Host → screener | `initialize`  | `inputData`: a JSON form-data object; use `{}` for no prefill |
| Screener → host | `initialized` | Prefill accepted; form loads and evaluates                    |
| Screener → host | `result`      | `inputData`, `results`, `evaluatedAt` (ISO 8601 timestamp)    |
| Screener → host | `error`       | `code: "EVALUATION_FAILED"`                                   |

The screener waits for initialization before displaying the connected form. It
accepts one initialization per page load and rejects malformed data, unsafe
prototype keys, mismatched origins/windows, and mismatched sessions. To start
another client's screening, create a fresh connection and navigate the frame or
window again. Without a valid origin and parent/opener, it runs as an ordinary
standalone screener and sends no integration messages.

A checkbox or text field can't display an unknown answer, so it appears
unchecked or empty. The connected screener still evaluates and returns a `null`
or omitted prefill answer as `null` until the user changes that field.

`result` is sent after initial evaluation and after evaluated edits (normally
debounced by one second). Multiple results per session are expected. Older
in-flight responses are discarded once a newer evaluation has started. A
result is a snapshot, not a completion event. Offer a host-side **Save results**
action or implement your application's explicit autosave policy. Do not treat
the first result as final. A successful new evaluation clears the host's error
state; after an error, don't save the old snapshot as the latest result.

Pin the CRM record in the host's session rather than trusting a record ID in a
result payload. On save, authorize that record through your CRM backend or its
native authenticated API, and validate whichever result fields you persist.
Use the opaque request ID to correlate a session, not as an authentication token.
For audit history, save separate screening records with timestamps and the
published screener ID. Browser messages are client-provided data; integrations
requiring trusted decisions should re-evaluate the answers server-side.

## Examples and adapter extension points

- **Browser demo:** run `node examples/crm/browser/serve.mjs` from the repository
  root, then open `http://127.0.0.1:4174/demo.html`. Enter a published BDT screener
  URL and test both iframe and separate-window modes. Individual client fields
  use a flat CRM schema; an explicit mapper converts them to nested screener data,
  including monthly-to-annual income conversion. **Inspect field mapping** shows
  the source record and generated prefill side by side. It prefills synthetic data
  and lets you save results to a browser-local demo record. Its record panel shows
  benefit statuses and timestamps, and restores saved results after a reload.
  Client fields and saved results share one record section; the embedded screener
  uses a full-width area with questions and live results side by side.
  The demo lives under `examples/crm/browser/` and is not included in frontend deployments; its local
  server serves the same canonical adapter used by the frontend.
- **Salesforce:** `examples/crm/salesforce/` contains a community nonprofit client
  app with editable client profiles, screening intake, full Philadelphia example
  prefill, explicit result saving, and formatted saved-results cards. Intake
  includes income, housing, property taxes, age, family, and program enrollment;
  unanswered values remain unknown. Sales fields and actions are excluded from
  the custom client page. Follow its README to configure a development org,
  permissions, and your published screener. The adapter supports both iframe
  and separate-window screening. Verify your own screener's mapping.

Salesforce supports cross-origin iframe messaging under
[Lightning Web Security](https://developer.salesforce.com/docs/platform/lightning-components-security/guide/lws-iframes.html).
The example uses native
[getRecord](https://developer.salesforce.com/docs/platform/lwc/guide/reference-wire-adapters-record.html)
and [updateRecord](https://developer.salesforce.com/docs/platform/lwc/guide/reference-update-record.html)
for authenticated record access. Its adapter is copied into a local LWC module
because Salesforce restricts
[third-party script loading](https://developer.salesforce.com/docs/platform/lwc/guide/js-api-calls).

Other CRMs can reuse the same adapter by providing their own input mapper and
`onResult`/`onError` callbacks. Those callbacks are the extension points for CRM
UI and storage; there is no provider registry or server-side CRM plugin runtime.

## Local verification

With the frontend dev server running and its example environment configured,
run `npx playwright test --project=crm-integrations` from `e2e/`. These Chromium
tests intercept the published APIs and verify prefill, edited results, explicit
saving, failure recovery, and standalone behavior. The spec starts its own
browser example server on a free port, so it doesn't conflict with a demo server
you already have running. They require no emulators or CRM account. Frontend unit tests also cover
origin/source validation, correlation, one-time initialization, outdated responses,
and listener cleanup.

Salesforce enables the host adapter's `serializeMessages: true` option to send
JSON envelopes across Lightning Web Security sandbox boundaries. The screener
parses that initialization and returns JSON envelopes for the session. Object
messages remain the default for other hosts; both formats use the same exact
origin, source, session, and request checks.
