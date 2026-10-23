import { html } from "htm/preact";
import { useContext, useLayoutEffect, useMemo } from "preact/hooks";
import {
  FormContext,
  iconsByType,
  sanitizeHTML,
  Text,
} from "@bpmn-io/form-js-viewer";
import type { BenefitResult, NextSteps as Content } from "@/types";
import { useScreeningResults } from "../screeningResults/useScreeningResults";
import { NEXT_STEPS_TYPE, webLink, type BenefitContent } from "./content";
import "./nextSteps.css";

/** No template evaluation: analyst Markdown is content, never a FEEL expression. */
function Markdown({ text }: { text?: string | null }) {
  const { getService } = useContext(FormContext);
  const renderer = getService("markdownRenderer");
  const markup = useMemo(() => {
    const container = document.createElement("div");
    container.innerHTML = sanitizeHTML(renderer.render(text ?? ""));
    container.querySelectorAll("a").forEach((link) => {
      const safe = webLink(link.getAttribute("href"));
      if (safe) {
        link.href = safe;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
      } else {
        link.removeAttribute("href");
        link.removeAttribute("target");
      }
    });
    return { __html: container.innerHTML };
  }, [renderer, text]);
  return html`<div
    class="bdt-next-steps-markdown"
    dangerouslySetInnerHTML=${markup}
  />`;
}

function Eligible({ content }: { content?: Content }) {
  const apply = webLink(content?.eligible?.applyUrl);
  const help = webLink(content?.eligible?.helpUrl);
  const phone = content?.eligible?.helpPhone?.trim();
  const dial =
    phone && /^[+\d\s().-]+$/.test(phone)
      ? phone.replace(/[^+\d]/g, "")
      : undefined;
  return html`
    <p class="bdt-next-steps-outcome">You may be eligible.</p>
    <div class="bdt-next-steps-contacts">
      ${apply &&
      html`<a href=${apply} target="_blank" rel="noopener noreferrer"
        >Apply for this benefit</a
      >`}
      ${help &&
      html`<a href=${help} target="_blank" rel="noopener noreferrer"
        >Get help</a
      >`}
      ${phone &&
      html`<span
        >Help phone:${" "}
        ${dial ? html`<a href=${`tel:${dial}`}>${phone}</a>` : phone}</span
      >`}
    </div>
    <${Markdown} text=${content?.eligible?.body} />
  `;
}

type Field = { showWhen?: string; benefitContent?: BenefitContent };

export function NextSteps({ field }: { field: Field }) {
  const { getService } = useContext(FormContext);
  useLayoutEffect(
    () => getService("screeningResults", false)?.showNextSteps(),
    [getService],
  );
  const { results, pending, failed, mode } = useScreeningResults();
  const benefits = Object.entries(results ?? {});
  const complete =
    benefits.length > 0 &&
    benefits.every(([, benefit]) => ["TRUE", "FALSE"].includes(benefit.result));
  // Stays in the DOM so screen readers announce completion, as the page's message did.
  return html`<div>
    <p role="status" class="bdt-next-steps-visually-hidden">
      ${mode === "live" && complete && !pending && !failed
        ? "Screening complete. Your next steps are below."
        : ""}
    </p>
    <${Body}
      field=${field}
      benefits=${benefits}
      complete=${complete}
      pending=${pending}
      failed=${failed}
      mode=${mode}
    />
  </div>`;
}

function Body({
  field,
  benefits,
  complete,
  pending,
  failed,
  mode,
}: {
  field: Field;
  benefits: [string, BenefitResult][];
  complete: boolean;
  pending: boolean;
  failed: boolean;
  mode: "live" | "editor";
}) {
  if (failed)
    return html`<div class="fjs-form-field bdt-next-steps">
      <p role="alert">
        We couldn't update your next steps. Change an answer to try again.
      </p>
    </div>`;
  if (pending)
    return html`<div class="fjs-form-field bdt-next-steps">
      <p role="status">Updating your next steps…</p>
    </div>`;
  if (!benefits.length)
    return mode === "editor"
      ? html`<div class="fjs-form-field bdt-next-steps">
          Next steps will appear after screening.
        </div>`
      : null;
  const hideDecided = field.showWhen === "whenComplete" && !complete;
  return html` <section
    class="fjs-form-field bdt-next-steps"
    aria-label="Your next steps"
  >
    ${mode === "editor" &&
    html`<p class="bdt-next-steps-sample">
      Example results for the editing canvas
    </p>`}
    <h2>
      ${complete
        ? "Screening complete — your next steps"
        : "Your next steps so far"}
    </h2>
    ${hideDecided &&
    html`<p>Answer the remaining questions to see your next steps.</p>`}
    ${benefits.map(([id, benefit]) => {
      const content = field.benefitContent?.[id];
      if (benefit.result === "UNABLE_TO_DETERMINE")
        return html`<section key=${id}>
          <h3>${benefit.name}</h3>
          <p>Still needs more information.</p>
        </section>`;
      if (hideDecided) return null;
      const unmet = Object.values(benefit.check_results ?? {}).filter(
        (check) => check.result === "FALSE",
      );
      return html` <section key=${id} class="bdt-next-steps-benefit">
        <h3>${benefit.name}</h3>
        ${benefit.result === "TRUE"
          ? html`<${Eligible} content=${content} />`
          : html`
              <p class="bdt-next-steps-outcome">
                You do not appear to be eligible.
              </p>
              ${unmet.length > 0 &&
              html`<h4>Requirements not met</h4>
                <ul>
                  ${unmet.map(
                    (check) =>
                      html`<li>${check.aliasName?.trim() || check.name}</li>`,
                  )}
                </ul>`}
              <${Markdown} text=${content?.ineligible?.note} />
            `}
      </section>`;
    })}
  </section>`;
}

NextSteps.config = {
  ...Text.config,
  type: NEXT_STEPS_TYPE,
  name: "Next steps",
  icon: iconsByType("text"),
  propertiesPanelEntries: [],
  create: (options = {}) => ({ showWhen: "whenDecided", ...options }),
};
