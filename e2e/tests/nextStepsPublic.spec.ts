import { test, expect } from "@playwright/test";

for (const showWhen of ["whenDecided", "whenComplete"]) {
  test(`published next steps show safely with ${showWhen} and suppress stale decisions`, async ({
    page,
  }) => {
    let fail = false;
    await page.route("**/api/published/**", async (route) => {
      const headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Content-Type",
        "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
      };
      if (route.request().method() === "OPTIONS")
        return route.fulfill({ status: 204, headers });
      if (route.request().method() === "GET")
        return route.fulfill({
          headers,
          json: {
            screenerName: "Next steps test",
            formSchema: {
              id: "form",
              type: "default",
              schemaVersion: 18,
              components: [
                {
                  id: "income",
                  type: "number",
                  key: "income",
                  label: "Household income",
                },
                {
                  id: "done",
                  type: "checkbox",
                  key: "done",
                  label: "Answer remaining question",
                },
                {
                  id: "next",
                  type: "next_steps",
                  showWhen,
                  benefitContent: {
                    housing: {
                      eligible: {
                        applyUrl: "https://example.org/apply",
                        helpPhone: "215-555-0100",
                        body: "Bring **photo ID**. [Help](https://example.org/help)<script>window.bad=true</script>",
                      },
                      ineligible: { note: "Ask about other assistance." },
                    },
                  },
                },
              ],
            },
          },
        });
      if (fail) return route.fulfill({ headers, status: 503, json: {} });
      const answers = route.request().postDataJSON();
      await new Promise((resolve) => setTimeout(resolve, 150));
      return route.fulfill({
        headers,
        json: {
          housing: {
            name: "Housing assistance",
            result: answers.income < 1000 ? "TRUE" : "FALSE",
            check_results: {
              income: {
                name: "Income",
                aliasName: "Income must be below $1,000",
                result: answers.income < 1000 ? "TRUE" : "FALSE",
                inputPaths: [],
                parameters: {},
              },
            },
          },
          food: {
            name: "Food assistance",
            result: answers.done ? "TRUE" : "UNABLE_TO_DETERMINE",
            check_results: {},
          },
        },
      });
    });
    await page.goto("/screener/next-steps-test");
    const income = page.getByLabel("Household income", { exact: true });
    await income.fill("500");
    if (showWhen === "whenDecided") {
      await expect(
        page.getByRole("heading", { name: "Your next steps so far" }),
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Apply for this benefit" }),
      ).toBeVisible();
      await expect(
        page.getByText("Still needs more information.", { exact: true }),
      ).toBeVisible();
    } else {
      await expect(
        page.getByText(
          "Answer the remaining questions to see your next steps.",
        ),
      ).toBeVisible();
      await expect(
        page.getByRole("link", { name: "Apply for this benefit" }),
      ).toHaveCount(0);
    }
    await page.getByLabel("Answer remaining question", { exact: true }).check();
    await expect(
      page.getByRole("heading", {
        name: "Screening complete — your next steps",
        exact: true,
      }),
    ).toBeVisible();
    const next = page.getByRole("region", {
      name: "Your next steps",
      exact: true,
    });
    await expect(next.locator("strong")).toHaveText("photo ID");
    await expect(
      next.getByRole("link", { name: "Help", exact: true }),
    ).toHaveAttribute("target", "_blank");
    await expect(
      page.getByText("Screening complete. All benefits", { exact: false }),
    ).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).bad)).toBeUndefined();
    await income.fill("2000");
    await expect(
      page.getByText("Updating your next steps…", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Apply for this benefit" }),
    ).toHaveCount(0);
    await expect(
      next.getByText("Requirements not met", { exact: true }),
    ).toBeVisible();
    await expect(next.getByRole("listitem")).toHaveText(
      "Income must be below $1,000",
    );
    await expect(next.getByText("Ask about other assistance.")).toBeVisible();
    fail = true;
    await income.fill("600");
    await expect(
      page.getByText(
        "We couldn't update your next steps. Change an answer to try again.",
      ),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", {
        name: "Screening complete — your next steps",
        exact: true,
      }),
    ).toHaveCount(0);
  });
}
