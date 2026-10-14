// @ts-check
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

// https://astro.build/config
export default defineConfig({
  integrations: [
    starlight({
      title: "BDT Docs",
      description:
        "User guide for the Benefit Decision Toolkit screener builder app",
      sidebar: [
        {
          label: "User",
          items: [
            { label: "Introduction", slug: "user/intro" },
            { label: "User Guide", slug: "user/user-guide" },
            { label: "Custom Checks", slug: "user/custom-checks" },
          ],
        },
        {
          label: "Developer",
          items: [
            {
              label: "CRM Integrations",
              slug: "dev/crm-integrations",
            },
            {
              label: "Testing PRs with Codespaces",
              slug: "dev/testing-prs-with-codespaces",
            },
            {
              label: "Input Definition Transformation",
              slug: "dev/input-definition-transformation",
            },
            {
              label: "Editing the Example Screener",
              slug: "dev/editing-example-screener",
            },
          ],
        },
      ],
    }),
  ],
});
