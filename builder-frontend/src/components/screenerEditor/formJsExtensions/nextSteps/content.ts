import cloneDeep from "lodash/cloneDeep";
import type { Benefit, NextSteps } from "@/types";

export const NEXT_STEPS_TYPE = "next_steps";
export type BenefitContent = Record<string, NextSteps>;

export function copyBenefitContent(benefits: Benefit[]): BenefitContent {
  return Object.fromEntries(
    benefits.map((benefit) => [benefit.id, cloneDeep(benefit.nextSteps ?? {})]),
  );
}

/** Accept only public web links, including when a schema was imported by hand. */
export function webLink(value?: string | null): string | undefined {
  if (!value) return;
  try {
    const url = new URL(value);
    if (
      ["https:", "http:"].includes(url.protocol) &&
      !url.username &&
      !url.password
    )
      return url.href;
  } catch {
    /* An invalid contact is omitted. */
  }
}
