# Browser CRM integration example

This standalone host demonstrates prefill, iframe and popup screening, and an
explicit **Save results** action. Its **Demo client record** panel shows readable
benefit statuses and the saved and evaluated timestamps. The fictional record is
stored in this browser's local storage, and reloading restores its saved snapshot.
It is an example, and is not included in BDT frontend deployments.

Client information, its mapping preview, and saved screening results share one
**Demo client record** section. **Run a screening** occupies its own full-width
section below the record. The embedded screener has a minimum viewport width of
1100 pixels so live results appear to the right of the questions. Smaller host
windows scroll the screener horizontally. **View client record** returns to the
combined record section after reviewing or saving results.

From the repository root, run:

```sh
node examples/crm/browser/serve.mjs
```

Open <http://127.0.0.1:4174/demo.html>. Enter a published screener URL from a BDT
frontend that includes the CRM bridge, then edit the individual synthetic client
fields. Use **Screen here** or **Screen in new window**, review or edit the
answers, and choose **Save results to demo record** after results arrive. Inspect
the **Demo client record** panel, or reload the page, to see the saved snapshot.
Later screening changes leave that snapshot unchanged until you save again.
Use **Clear saved results** to reset the demo record.

Only the result snapshot is stored, not the prefilled answers. Storage is local to
this browser and example origin; there is no CRM backend or server persistence.

## Map client fields to screener answers

The demo CRM uses a flat record with its own field names, such as `birth_date`,
`home_city`, and `monthly_income`. `clientMapping.mjs` explicitly maps these
fields into the Philadelphia example's nested screener hierarchy:

| CRM field                | Screener path                        | Conversion                                                |
| ------------------------ | ------------------------------------ | --------------------------------------------------------- |
| `birth_date`             | `people.client.dateOfBirth`          | ISO date; blank becomes null                              |
| `home_city`              | `simpleChecks.livesInPhiladelphiaPa` | Philadelphia becomes true, another city false, blank null |
| `monthly_income`         | `custom.householdIncome`             | Monthly dollars × 12, rounded to cents; blank stays null  |
| `cash_support_requested` | `custom.wantsExtraCash`              | requested/declined become true/false; unknown stays null  |

**Inspect field mapping** shows this table and read-only previews of both shapes.
The previews update as you edit client fields. Client ID and name stay in the CRM;
only mapped screening answers are sent to BDT. Income of zero remains zero.
A launch takes the current mapped answers; editing client fields does not change
an already-open screening or the saved result snapshot.

The mapper is specific to the Philadelphia example. Adapt it for the configured
keys in another published screener's form. Run its mapping checks from the repo root:

```sh
node examples/crm/browser/tests/clientMapping.test.mjs
```

## Frontend and local server

The URL defaults to the local frontend at `http://localhost:5173`; start it
separately with `npm run dev` in `builder-frontend/`, or enter your deployed BDT
screener URL. The published screener must exist and its evaluation API must be
available. Popup mode requires popups to be allowed.

The local server binds to loopback on port 4174 and serves only `demo.html`,
`demo.js`, `clientMapping.mjs`, and the canonical adapter from
`builder-frontend/public/integrations/host.js`. It makes the adapter available as
`/host.js` for the example's relative import, without maintaining a duplicate.
The production frontend continues to serve the adapter at `/integrations/host.js`.

With the local frontend running, run the browser checks from `e2e/`:

```sh
npx playwright test --project=crm-integrations
```

Playwright starts this example server automatically, or reuses it locally if
already running. Restart a manually running example server after adding assets.
The tests verify that flat client fields reach the screener as mapped nested data,
including monthly-to-annual income conversion. They intercept published API responses, so they require no
Firebase emulators, login, published screener, or CRM account.
