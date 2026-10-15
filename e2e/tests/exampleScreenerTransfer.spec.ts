import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

// Provision our own local demo account instead of clearing an existing developer's data.
// This also exercises the exact bundled example received by a new analyst.
test("the bundled example can be chosen, exported, and imported with its internal library checks and reused custom checks", async ({
  page,
  request,
}) => {
  const email = `import-regression-${randomUUID()}@example.com`;
  const password = "local-import-regression-account";
  const signUp = await request.post(
    "http://localhost:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=local-demo",
    {
      data: { email, password, returnSecureToken: true },
    },
  );
  expect(signUp.ok()).toBe(true);
  const account = await signUp.json();
  const provision = await request.post(
    "http://localhost:8081/api/account/hooks",
    {
      headers: { Authorization: `Bearer ${account.idToken}` },
      data: { hooks: ["add example screener"] },
    },
  );
  expect(provision.ok()).toBe(true);
  expect((await provision.json()).success).toBe(true);

  await page.goto("/login");
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  await expect(page).toHaveURL("/screeners");
  const card = page
    .locator("article")
    .filter({ hasText: "Philadelphia Benefits Example" });
  await expect(card).toHaveCount(1);
  await card.getByRole("button").click();
  const downloadPromise = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Export screener", exact: true })
    .click();
  const download = await downloadPromise;
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
  const buffer = Buffer.concat(chunks);
  const exported = JSON.parse(buffer.toString());
  const libraryResponse = await request.get(
    "http://localhost:8083/q/openapi.json",
  );
  expect(libraryResponse.ok()).toBe(true);
  const library = await libraryResponse.json();
  // The bundled example must stay in sync when the library release changes.
  for (const check of exported.benefits.flatMap((benefit: any) => benefit.checks)) {
    if (!check.sourceCheckId.startsWith("L-")) continue;
    expect(check.checkVersion, check.sourceCheckId).toBe(library.info.version);
    expect(check.sourceCheckId).toBe(
      `L-${check.checkModule}-${check.checkName}-${library.info.version}`,
    );
    expect(
      library.paths[check.evaluationUrl]?.post,
      check.sourceCheckId,
    ).toBeDefined();
  }
  expect(exported.benefits).toHaveLength(4);
  expect(exported.benefits.flatMap((benefit: any) => benefit.checks)).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        sourceCheckId: `L-internal-sctf-age-requirement-${library.info.version}`,
      }),
    ]),
  );

  await page.goto("/screeners");
  await page
    .getByRole("button", { name: "Import screener", exact: true })
    .click();
  const chooseFile = page.getByRole("button", {
    name: "Choose file",
    exact: true,
  });
  await expect(chooseFile).toBeVisible();
  expect(
    await chooseFile.evaluate((element) =>
      Number.parseFloat(getComputedStyle(element).borderTopWidth),
    ),
  ).toBeGreaterThan(0);
  const chooserPromise = page.waitForEvent("filechooser");
  await chooseFile.click();
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name: "example.bdt.json",
    mimeType: "application/json",
    buffer,
  });
  await expect(
    page.getByText("example.bdt.json", { exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Screener name", { exact: true })).toHaveValue(
    exported.screenerName,
  );
  await expect(
    page.getByText(
      "You already have a screener with this name. Choose a different name.",
    ),
  ).toBeVisible();
  await page
    .getByLabel("Screener name", { exact: true })
    .fill(`${exported.screenerName} - Copy`);
  const importPromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/screener/import") &&
      response.request().method() === "POST",
  );
  await page
    .locator("form")
    .getByRole("button", { name: "Import screener", exact: true })
    .click();
  const response = await importPromise;
  expect(response.status(), await response.text()).toBe(201);
  const imported = await response.json();
  expect(imported.benefits).toHaveLength(4);
  expect(imported.screenerName).toBe(`${exported.screenerName} - Copy`);
  const headers = { Authorization: `Bearer ${account.idToken}` };
  const duplicateCreate = await request.post(
    "http://localhost:8081/api/screener",
    {
      headers,
      data: { screenerName: ` ${exported.screenerName.toUpperCase()} ` },
    },
  );
  expect(duplicateCreate.status(), await duplicateCreate.text()).toBe(409);
  const duplicateRename = await request.patch(
    `http://localhost:8081/api/screener/${imported.id}`,
    {
      headers,
      data: { screenerName: exported.screenerName },
    },
  );
  expect(duplicateRename.status(), await duplicateRename.text()).toBe(409);
  const unchangedRename = await request.patch(
    `http://localhost:8081/api/screener/${imported.id}`,
    {
      headers,
      data: { screenerName: imported.screenerName },
    },
  );
  expect(unchangedRename.status(), await unchangedRename.text()).toBe(200);
  const competingCreates = await Promise.all(
    ["Race name", " race NAME "].map((screenerName) =>
      request.post("http://localhost:8081/api/screener", {
        headers,
        data: { screenerName },
      }),
    ),
  );
  expect(competingCreates.map((result) => result.status()).sort()).toEqual([
    200, 409,
  ]);
  await expect(page).toHaveURL(`/screeners/${imported.id}`);
  await expect(page.locator("#manage-benefits-title")).toBeVisible();
  await expect(
    page.getByText("Senior Citizen Tax Freeze (SCTF)", { exact: true }),
  ).toBeVisible();
  expect(imported.benefits.map((benefit: any) => benefit.name)).toEqual(
    exported.benefits.map((benefit: any) => benefit.name),
  );
  for (const benefit of imported.benefits) {
    await expect(page.getByText(benefit.name, { exact: true })).toBeVisible();
  }

  const readWorking = async (token: string) => {
    const response = await request.get(
      "http://localhost:8081/api/custom-checks?working=true&includeArchived=true",
      {
        headers: { Authorization: `Bearer ${token}` },
      },
    );
    expect(response.ok(), await response.text()).toBe(true);
    return response.json();
  };
  // Only the versions the screener uses are shared, never the author's drafts.
  expect(
    exported.customChecks.every((check: any) => check.id.startsWith("P-")),
  ).toBe(true);
  const originalIds = [
    ...new Set(
      exported.customChecks.map(
        (check: any) =>
          `W-${check.id.slice(2, -(check.version.length + 1))}`,
      ),
    ),
  ].sort();
  expect(originalIds).toHaveLength(2);
  expect(
    (await readWorking(account.idToken)).map((check: any) => check.id).sort(),
  ).toEqual(originalIds);
  expect(
    exported.customChecks.every(
      (check: any) => typeof check.originCheckId === "string",
    ),
  ).toBe(true);

  // Two simultaneous transfers to a fresh recipient must create just one family per origin.
  const recipientSignUp = await request.post(
    "http://localhost:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=local-demo",
    {
      data: {
        email: `import-recipient-${randomUUID()}@example.com`,
        password,
        returnSecureToken: true,
      },
    },
  );
  expect(recipientSignUp.ok()).toBe(true);
  const recipient = await recipientSignUp.json();
  const recipientHeaders = { Authorization: `Bearer ${recipient.idToken}` };
  const importForRecipient = (name: string) =>
    request.post("http://localhost:8081/api/screener/import", {
      headers: recipientHeaders,
      data: { ...exported, screenerName: name },
    });
  const concurrent = await Promise.all([
    importForRecipient("Recipient one"),
    importForRecipient("Recipient two"),
  ]);
  expect(concurrent.some((response) => response.status() === 201)).toBe(true);
  for (const [index, response] of concurrent.entries()) {
    expect([201, 409], await response.text()).toContain(response.status());
    if (response.status() === 409) {
      const retry = await importForRecipient(
        index === 0 ? "Recipient one" : "Recipient two",
      );
      expect(retry.status(), await retry.text()).toBe(201);
    }
  }
  const recipientDrafts = await readWorking(recipient.idToken);
  expect(recipientDrafts).toHaveLength(2);
  expect(
    recipientDrafts.every((check: any) => !originalIds.includes(check.id)),
  ).toBe(true);
  const editedDraft = recipientDrafts[0];
  const draftResponse = await request.get(
    `http://localhost:8081/api/custom-checks/${editedDraft.id}`,
    { headers: recipientHeaders },
  );
  expect(draftResponse.ok()).toBe(true);
  const draft = await draftResponse.json();
  const editedXml = draft.dmnModel.replace(
    /<dmn:text>[\s\S]*?<\/dmn:text>/,
    "<dmn:text>false</dmn:text>",
  );
  expect(editedXml).not.toBe(draft.dmnModel);
  const edit = await request.put(
    `http://localhost:8081/api/custom-checks/${editedDraft.id}/dmn`,
    {
      headers: recipientHeaders,
      data: { dmnModel: editedXml },
    },
  );
  expect(edit.ok(), await edit.text()).toBe(true);
  const repeat = await importForRecipient("Recipient three");
  expect(repeat.status(), await repeat.text()).toBe(201);
  const repeatScreener = await repeat.json();
  expect(
    (await readWorking(recipient.idToken)).map((check: any) => check.id).sort(),
  ).toEqual(recipientDrafts.map((check: any) => check.id).sort());
  const retained = await request.get(
    `http://localhost:8081/api/custom-checks/${editedDraft.id}`,
    { headers: recipientHeaders },
  );
  expect((await retained.json()).dmnModel).toBe(editedXml);
  const reexportResponse = await request.get(
    `http://localhost:8081/api/screener/${repeatScreener.id}/export`,
    { headers: recipientHeaders },
  );
  expect(reexportResponse.ok()).toBe(true);
  const reexported = await reexportResponse.json();
  expect(
    reexported.customChecks.map((check: any) => check.originCheckId).sort(),
  ).toEqual(
    exported.customChecks.map((check: any) => check.originCheckId).sort(),
  );
  const sharedBack = await request.post(
    "http://localhost:8081/api/screener/import",
    {
      headers,
      data: { ...reexported, screenerName: "Shared back" },
    },
  );
  expect(sharedBack.status(), await sharedBack.text()).toBe(201);
  expect(
    (await readWorking(account.idToken)).map((check: any) => check.id).sort(),
  ).toEqual(originalIds);

  // A file that claims the same published identity/version with changed rules gets a readable UI error.
  const conflicting = structuredClone(exported);
  const conflictingVersion = conflicting.customChecks.find((check: any) =>
    check.id.startsWith("P-"),
  );
  conflictingVersion.dmnModel = conflictingVersion.dmnModel.replace(
    /<dmn:text>[\s\S]*?<\/dmn:text>/,
    "<dmn:text>false</dmn:text>",
  );
  conflicting.screenerName = "Conflicting version";
  await page.goto("/screeners");
  await page
    .getByRole("button", { name: "Import screener", exact: true })
    .click();
  await page.getByLabel("Screener file").setInputFiles({
    name: "conflicting.bdt.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(conflicting)),
  });
  await expect(page.getByLabel("Screener name", { exact: true })).toHaveValue(
    "Conflicting version",
  );
  const conflictPromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/screener/import") &&
      response.request().method() === "POST",
  );
  await page
    .locator("form")
    .getByRole("button", { name: "Import screener", exact: true })
    .click();
  expect((await conflictPromise).status()).toBe(409);
  await expect(page.getByRole("alert")).toContainText(
    "different rules or parameter definitions",
  );
  await expect(
    page
      .locator("form")
      .getByRole("button", { name: "Import screener", exact: true }),
  ).toBeEnabled();
  expect(
    (await readWorking(account.idToken)).map((check: any) => check.id).sort(),
  ).toEqual(originalIds);
});
