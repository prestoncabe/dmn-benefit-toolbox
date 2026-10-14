import { defineConfig } from '@playwright/test';

/**
 * See https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: './tests',
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Opt out of parallel tests on CI. */
  workers: 1,
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  /* The HTML reporter's default `open: 'on-failure'` starts a report server and
     blocks on "Press Ctrl+C to quit" whenever a test fails, which hangs any
     script that wraps this suite (bin/run-e2e-tests, bin/run-all-tests). Keep
     the report on disk and let developers open it with
     `npx playwright show-report`, and use the list reporter for terminal
     output. */
  reporter: [['list'], ['html', { open: 'never' }]],
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    /* Base URL to use in actions like `await page.goto('')`. */
    baseURL: 'http://localhost:5173',

    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: 'on-first-retry',

    screenshot: "only-on-failure",
  },

  /* Configure projects for major browsers */
  projects: [
    {
      name: 'setup',
      testMatch: /global\.setup\.ts/,
    },

    // Main test project - depends on setup for authentication
    {
      name: 'chromium',
      testIgnore: /crmIntegration\.spec\.ts/,
      use: {
        browserName: 'chromium',
      },
      dependencies: ['setup'],
    },

    // Uses public screeners with intercepted APIs; no emulator/login setup.
    {
      name: 'crm-integrations',
      testMatch: /crmIntegration\.spec\.ts/,
      use: { browserName: 'chromium' },
    },

    // TODO: Update config to use other non-chromium browsers

    // {
    //   name: 'firefox',
    //   use: { browserName: 'firefox' },
    // },

    // {
    //   name: 'webkit',
    //   use: { ...devices['Desktop Safari'] },
    // },
  ],
});
