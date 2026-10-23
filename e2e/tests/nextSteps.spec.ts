import { test, expect } from "@playwright/test";
import { authLogin } from "./authLogin";
import {
  resetEmulator,
  seedScreenerWithForm,
  TEST_SCREENER_ID,
  TEST_BENEFIT_ID,
} from "./firestore";

test.beforeEach(resetEmulator);
test.beforeEach(authLogin);
test.afterEach(resetEmulator);

test("analysts copy, edit, reset, and publish a screener's next steps", async ({
  page,
}) => {
  await seedScreenerWithForm();
  await page.goto(`/screeners/${TEST_SCREENER_ID}`);
  await page.getByTestId(`edit-benefit-${TEST_BENEFIT_ID}`).click();
  await page
    .getByLabel("Application instructions", { exact: true })
    .fill("Original instructions from the benefit.");
  await page
    .getByLabel("Application URL", { exact: true })
    .fill("https://example.org/apply");
  await page
    .getByRole("button", { name: "Save next steps", exact: true })
    .click();
  await expect(
    page.getByText("Next steps saved.", { exact: true }),
  ).toBeVisible();
  await page.getByTestId("editor-section-formEditor").click();
  await page
    .locator('[data-field-type="next_steps"]')
    .dragTo(page.locator('[data-id="BDT_Form"].fjs-drop-container-vertical'));
  const editContent = page.getByRole("button", {
    name: "Edit benefit content",
    exact: true,
  });
  await page.locator('[data-title="Next steps"]').waitFor();
  if (!(await editContent.isVisible()))
    await page.locator('[data-title="Next steps"]').click();
  await editContent.click();
  const modal = page.getByRole("dialog", {
    name: "Edit next steps content",
    exact: true,
  });
  await expect(
    modal.getByLabel("Application instructions", { exact: true }),
  ).toHaveValue("Original instructions from the benefit.");
  await modal
    .getByLabel("Application instructions", { exact: true })
    .fill("Use these screener-specific instructions.");
  await modal
    .getByRole("button", { name: "Save next steps", exact: true })
    .click();
  await expect(modal.getByRole("status")).toContainText(
    "Component content updated",
  );
  await modal
    .getByRole("button", { name: "Reset from benefit", exact: true })
    .click();
  await expect(
    modal.getByLabel("Application instructions", { exact: true }),
  ).toHaveValue("Original instructions from the benefit.");
  await modal
    .getByLabel("Application instructions", { exact: true })
    .fill("Published screener instructions.");
  await modal
    .getByRole("button", { name: "Save next steps", exact: true })
    .click();
  await modal.getByRole("button", { name: "Close", exact: true }).click();
  await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().includes("/save-form-schema") &&
        response.request().method() === "POST" &&
        response.ok(),
    ),
    page.getByTestId("form-editor-save-button").click(),
  ]);
  await page.getByTestId("editor-section-manageBenefits").click();
  await page.getByTestId(`edit-benefit-${TEST_BENEFIT_ID}`).click();
  await expect(
    page.getByLabel("Application instructions", { exact: true }),
  ).toHaveValue("Original instructions from the benefit.");
  await page.getByTestId("editor-section-publish").click();
  await page.getByTestId("publish-screener-button").click();
  const link = page.locator("#screener-url-info").getByRole("link");
  await expect(link).toBeVisible();
  await page.goto((await link.getAttribute("href"))!);
  const evaluation = page.waitForResponse(
    (response) =>
      response.url().includes("/api/published/") &&
      response.url().endsWith("/evaluate") &&
      response.request().method() === "POST",
  );
  await page.getByLabel("Is owner occupant?", { exact: true }).check();
  expect(Object.keys(await (await evaluation).json())).toEqual([
    TEST_BENEFIT_ID,
  ]);
  await expect(
    page.getByText("Published screener instructions.", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Apply for this benefit", exact: true }),
  ).toHaveAttribute("href", "https://example.org/apply");
});
