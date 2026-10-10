import { expect, test } from "@playwright/test";

const demoUrl = "http://127.0.0.1:4174/demo.html";

// Public browser integration tests use intercepted API responses, not Firebase.
test.beforeEach(async ({ context }) => {
  await context.route("**/api/published/**", async (route) => {
    const request = route.request();
    const headers = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    };
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers });
    } else if (request.method() === "GET") {
      await route.fulfill({
        headers,
        json: {
          screenerName: "CRM test screener",
          formSchema: {
            type: "default",
            id: "crm-form",
            schemaVersion: 18,
            components: [
              {
                type: "textfield",
                id: "birth-date",
                key: "people.client.dateOfBirth",
                label: "Birthdate",
              },
              {
                type: "number",
                id: "income",
                key: "custom.householdIncome",
                label: "Household income",
              },
              {
                type: "checkbox",
                id: "resident",
                key: "simpleChecks.livesInPhiladelphiaPa",
                label: "Philadelphia resident",
              },
              {
                type: "checkbox",
                id: "cash-interest",
                key: "custom.wantsExtraCash",
                label: "Interested in cash assistance",
              },
            ],
          },
        },
      });
    } else {
      const input = request.postDataJSON();
      await route.fulfill({
        headers,
        json: {
          benefit: {
            name: "Example benefit",
            result: input.custom.householdIncome <= 40000 ? "TRUE" : "FALSE",
            check_results: {},
          },
        },
      });
    }
  });
});

for (const serializeMessages of [false, true]) {
  for (const mode of ["iframe", "popup"] as const) {
    test(`CRM prefill, edited results and explicit record save through ${mode} with ${serializeMessages ? "JSON" : "object"} messages`, async ({
      page,
      baseURL,
      context,
    }) => {
      if (serializeMessages) {
        await context.route(
          new URL("/demo.js", demoUrl).href,
          async (route) => {
            const response = await route.fetch();
            const body = (await response.text()).replace(
              "createCrmIntegration({",
              "createCrmIntegration({ serializeMessages: true,",
            );
            await route.fulfill({ response, body });
          },
        );
      }
      await page.goto(demoUrl);
      const record = page.getByRole("region", { name: "Demo client record" });
      // The host and frontend use different ports, exercising cross-origin messaging.
      const screenerUrl = new URL("/screener/crm-test", baseURL);
      await page.locator("#screener-url").fill(screenerUrl.href);
      await record
        .getByLabel("Date of birth", { exact: true })
        .fill("1960-02-03");
      await record.getByLabel("Monthly household income ($)").fill("2750.25");
      await record.getByLabel("City of residence").fill("Philadelphia");
      await record
        .getByLabel("Cash assistance interest")
        .selectOption("requested");
      await page.getByText("Inspect field mapping", { exact: true }).click();
      await expect(page.locator("#client-json")).toContainText(
        '"monthly_income": 2750.25',
      );
      await expect(page.locator("#prefill-json")).toContainText(
        '"householdIncome": 33003',
      );
      await page.getByText("Inspect field mapping", { exact: true }).click();
      const evaluationRequest = context.waitForEvent(
        "request",
        (request) =>
          request.method() === "POST" &&
          new URL(request.url()).pathname ===
            "/api/published/crm-test/evaluate",
      );
      const popupPromise =
        mode === "popup" ? page.waitForEvent("popup") : undefined;
      await page.locator(mode === "popup" ? "#popup" : "#embed").click();
      const screener = popupPromise
        ? await popupPromise
        : page.frameLocator("#screener");
      const income = screener.getByLabel("Household income");
      await expect(income).toHaveValue("33003", { timeout: 15000 });
      await expect(
        screener.getByLabel("Birthdate", { exact: true }),
      ).toHaveValue("1960-02-03");
      await expect(screener.getByLabel("Philadelphia resident")).toBeChecked();
      await expect(
        screener.getByLabel("Interested in cash assistance"),
      ).toBeChecked();
      const input = (await evaluationRequest).postDataJSON();
      expect(input).toMatchObject({
        people: { client: { dateOfBirth: "1960-02-03" } },
        custom: { householdIncome: 33003, wantsExtraCash: true },
        simpleChecks: { livesInPhiladelphiaPa: true },
      });
      expect(input).not.toHaveProperty("monthly_income");
      expect(input).not.toHaveProperty("full_name");
      await expect(page.locator("#status")).toContainText(
        "Unsaved results: 1 eligible, 0 ineligible",
      );
      if (mode === "iframe") {
        // A narrow host scrolls the iframe instead of stacking live results.
        await page.setViewportSize({ width: 900, height: 800 });
        const frameBox = await page.locator("#screener").boundingBox();
        const inputBox = await income.boundingBox();
        const resultsBox = await screener
          .getByRole("heading", { name: "Eligibility Results", exact: true })
          .boundingBox();
        expect(frameBox!.width).toBeGreaterThanOrEqual(1100);
        expect(resultsBox!.x).toBeGreaterThanOrEqual(
          inputBox!.x + inputBox!.width,
        );
        expect(resultsBox!.y).toBeLessThan(inputBox!.y + inputBox!.height);
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth),
        ).toBeLessThanOrEqual(900);
        await page.setViewportSize({ width: 1280, height: 720 });
      }
      await expect(record).toContainText("No screening saved");
      await income.fill("50000");
      await expect(page.locator("#status")).toContainText(
        "Unsaved results: 0 eligible, 1 ineligible",
      );
      await expect(record).toContainText("No screening saved");
      await page.locator("#save").click();
      await expect(record).toContainText("Screening saved");
      await expect(record).toContainText("Example benefit");
      await expect(
        record.getByText("Ineligible", { exact: true }),
      ).toBeVisible();
      await expect(record.locator("#saved-screener")).toHaveText("crm-test");
      await expect(record.locator("#saved-at")).toHaveAttribute(
        "datetime",
        /.+/,
      );
      await context.route(
        "**/api/published/crm-test/evaluate",
        async (route) => {
          if (
            route.request().method() === "POST" &&
            route.request().postDataJSON().custom.householdIncome === 60000
          ) {
            await route.fulfill({
              status: 500,
              headers: { "Access-Control-Allow-Origin": "*" },
            });
          } else {
            await route.fallback();
          }
        },
      );
      await income.fill("60000");
      await expect(page.locator("#status")).toContainText("Evaluation failed");
      await expect(page.locator("#save")).toBeDisabled();
      await expect(
        record.getByText("Ineligible", { exact: true }),
      ).toBeVisible();
      await income.fill("35000");
      await expect(page.locator("#status")).toContainText(
        "Unsaved results: 1 eligible, 0 ineligible",
      );
      await expect(page.locator("#save")).toBeEnabled();
      // New evaluations do not overwrite the saved record until explicitly saved.
      await expect(
        record.getByText("Ineligible", { exact: true }),
      ).toBeVisible();
      await page.locator("#save").click();
      await expect(record.getByText("Eligible", { exact: true })).toBeVisible();
      const savedAt = await record
        .locator("#saved-at")
        .getAttribute("datetime");
      await page.reload();
      await expect(record.getByText("Eligible", { exact: true })).toBeVisible();
      await expect(record.locator("#saved-at")).toHaveAttribute(
        "datetime",
        savedAt!,
      );
      await expect(page.locator("#save")).toBeDisabled();
      await record.getByRole("button", { name: "Clear saved results" }).click();
      await page.reload();
      await expect(record).toContainText("No screening saved");
    });
  }
}

test("standalone screener still evaluates edited answers", async ({ page }) => {
  await page.goto("/screener/crm-test");
  await expect(
    page.getByText("Waiting for information from the connected application"),
  ).toBeHidden();
  await page.getByLabel("Household income").fill("30000");
  await expect(page.getByText("Eligible", { exact: true })).toBeVisible();
});
