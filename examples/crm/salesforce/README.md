# Salesforce community client services demo

This example uses Salesforce Contacts as nonprofit **client records**. Its client
page replaces sales highlights and Record Detail with a client header, an editable
client profile, screening intake, and formatted saved screening results. The
**Community Client Services** app gives staff a focused place to open clients.
Its **Community clients** directory shows records with a client service status,
with intake dates, household size, and preferred contact method.
Title, Department, Lead Source, Reports To, Assistant, Fax, and sales actions are
excluded from the client page.

The client profile tracks contact information, mailing address, preferred contact
method, intake date, service status, household size, and client goals and follow-up
notes. Contact ownership identifies the assigned staff member. The screening
intake matches the published **Philadelphia Benefits Example**:

| Client intake                            | Screener data                                       |
| ---------------------------------------- | --------------------------------------------------- |
| Birthdate                                | `people.client.dateOfBirth`                         |
| Annual household income                  | `custom.householdIncome`                            |
| Interested in cash assistance            | `custom.wantsExtraCash`                             |
| Lives in Philadelphia                    | `simpleChecks.livesInPhiladelphiaPa`                |
| Owns and occupies home                   | `simpleChecks.ownerOccupant`                        |
| Delinquent property taxes                | `simpleChecks.taxDelinquent`                        |
| Ten-year property tax abatement          | `simpleChecks.tenYearTaxAbatement`                  |
| Currently has a spouse; spouse birthdate | `people.spouse.exists`, `people.spouse.dateOfBirth` |
| Late spouse was at least 65              | `simpleChecks.lateSpouseWasAtLeast65`               |
| Confirmed program enrollments            | `people.client.enrollments`                         |

Yes/No questions use picklists with **Unknown** so missing answers never imply No.
Income of zero remains zero. Enrollment information must be **Confirmed** before
program selections are prefilled. Confirmed with no programs selected becomes the
form's explicit **None of these** answer; unreviewed enrollment stays unknown.
Only screening answers are sent to BDT. Names, contact information, client notes,
service status, and Salesforce credentials are not included in the prefill.

## Install in a development org

In a Salesforce DX project:

1. Copy `bdtClientHeader/`, `bdtClientIntake/`, `bdtScreening/`,
   `bdtScreeningResults/`, and `bdtCrmHost/` into `force-app/main/default/lwc/`.
   Copy `builder-frontend/public/integrations/host.js` from this repository to
   `force-app/main/default/lwc/bdtCrmHost/bdtCrmHost.js`.
2. Copy `BDT_Screening__c.field-meta.xml` and every field under `client-fields/`
   into `force-app/main/default/objects/Contact/fields/`. Copy
   `BDT_Screening.permissionset-meta.xml` into `force-app/main/default/permissionsets/`.
3. Copy `BDT_Client_Record.flexipage-meta.xml` into `force-app/main/default/flexipages/`.
   Replace its example screener URL with your published screener URL. Copy
   `Contact-BDT Client Layout.layout-meta.xml` into `force-app/main/default/layouts/`
   and `Community_Client_Services.app-meta.xml` into `force-app/main/default/applications/`.
   Copy `Community_Clients.listView-meta.xml` into
   `force-app/main/default/objects/Contact/listViews/`.
4. Deploy to a **Developer Edition or sandbox org**. Assign **BDT Screening** to
   your test user. It grants Contact read/edit and access to the listed client
   intake fields and screening result field, without record creation, deletion,
   View All, or Modify All access. Make the app visible to the user's profile and
   assign the **BDT Client Layout** Contact layout.
5. In Setup → Trusted URLs, allow **frame-src** for your BDT frontend origin.
   Use HTTPS and Lightning Web Security. If the BDT host sets CSP
   `frame-ancestors`, it must allow your Salesforce origin.
6. In the BDT screener editor's **Publish** tab, add your Lightning origin, such
   as `https://YOUR-DOMAIN.lightning.force.com`, to **Allowed CRM origins**.
   Save it, then publish the screener.
7. Open **BDT Client Record** in Lightning App Builder, verify the screener URL,
   and activate it as the **App Default** for **Community Client Services**. Its main column has
   **BDT Client Intake** (`screening`), **BDT Screening**, and **BDT Saved Screening
   Results**. Its sidebar uses **BDT Client Intake** (`profile`). The header uses
   **BDT Client Header**. Salesforce requires Account on the standard Contact
   layout; the custom client page does not display that field.

The BDT frontend must include this branch's CRM messaging hooks. An older
frontend can render while the integration keeps waiting for results. If a
metadata page change does not appear immediately, open **Edit Page**, check the
components and URL, and click **Save** in Lightning App Builder.

## Client workflow

1. Open a client and use **Edit client profile** for contact details and service
   coordination. Use **Edit screening intake** for the client's screening answers.
2. Save intake, then click **Screen here** or **Screen in new window**. A newly
   started screening reads the latest saved intake. Saving intake does not alter
   an already-open screening.
3. Review or edit the prefilled screening answers and results. Click **Save
   results to client** to store the latest evaluated snapshot in
   `Contact.BDT_Screening__c`.
4. **Saved screening results** shows benefit status cards, the evaluation time,
   and expandable checks. It refreshes after saving and remains available when
   the client record is reopened.

Edits made inside the screener do not write intake fields back to Salesforce.
The results field stores only the latest snapshot, replacing previous results.
Use a related Screening object if you need history. Screening results are
preliminary and do not submit an application or establish program approval.
The Philadelphia example includes fictional Philly Cash.

During Developer Edition validation on 2026-10-06, the connected live BDT backend
returned unknown enrollment checks for explicit empty enrollment lists. The
client mapper and form preserve the explicit None answer; verify this case
against your backend version. The synthetic Jordan scenario records an existing
Homestead enrollment, while Casey illustrates pending intake.

Salesforce enables the adapter's `serializeMessages` option: initialization and
subsequent messages use JSON text to avoid Lightning Web Security proxy issues
with popup windows. The origin, source, session, and request checks still apply.

The adapter pins the client record in callbacks and discards results after
navigation to another record. Adapt `clientPrefill.js` to other screener schemas.
Run the mapping checks with:

```sh
node examples/crm/salesforce/tests/clientPrefill.test.mjs
```

Salesforce references:

- [Record edit forms](https://developer.salesforce.com/docs/platform/lwc/guide/data-edit-record.html)
- [getRecord](https://developer.salesforce.com/docs/platform/lwc/guide/reference-wire-adapters-record.html)
- [updateRecord](https://developer.salesforce.com/docs/platform/lwc/guide/reference-update-record.html)
- [Objects proxied by Lightning Web Security](https://developer.salesforce.com/docs/platform/lightning-components-security/guide/lws-proxies.html)
- [Iframe access with Lightning Web Security](https://developer.salesforce.com/docs/platform/lightning-components-security/guide/lws-iframes.html)
- [Trusted URLs and third-party JavaScript](https://developer.salesforce.com/docs/platform/lwc/guide/js-api-calls)
