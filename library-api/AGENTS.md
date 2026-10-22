# library-api

This subtree is a Java 21, Quarkus 3.27, Kogito 10.2-based service using DMN 1.6. DMN files under `src/main/resources/` are discovered at build time and exposed through the custom endpoint `POST /api/v1/{resource-path}`.

Read the repository-root `AGENTS.md` as well. Use this file for stable component facts; use the DMN skills for detailed creation procedures and templates.

## Commands

- Run in dev mode: `bin/dev` (HTTP `localhost:8083`; Swagger UI at `/q/swagger-ui`)
- Compile/package: `mvn clean package`
- Java tests: `mvn test`
- DMN API tests: start the service, then `(cd test/bdt && bru run)`

DMN-only changes hot reload in dev mode. Java changes may require a restart. A clean compile is useful after adding or renaming DMN models.

## Architecture

- `src/main/resources/BDT.dmn` defines shared types, including `tSituation`.
- `src/main/resources/checks/` contains reusable eligibility decisions and category base modules.
- `src/main/resources/benefits/` composes checks into program eligibility decisions.
- `src/main/java/org/codeforphilly/bdt/library/api/` contains the custom discovery, routing, evaluation, and OpenAPI code.
- `src/main/java/org/codeforphilly/bdt/library/functions/` contains custom FEEL functions.
- `test/bdt/` contains Bruno tests for endpoint and business-rule behavior.

The project disables Kogito's default decision REST generation and uses its own dynamic REST resource. Do not infer endpoint behavior from Kogito defaults.

## DMN invariants

- Model names must be globally unique across all DMN files.
- Every exposed model needs a Decision Service named exactly `{ModelName}Service`.
- File names and endpoint path segments use kebab-case.
- Imports form an acyclic hierarchy rooted in `BDT.dmn`; namespace-qualify references to imported decisions and services.
- Imported decision services must also have unique names.
- Keep local `tSituation` projections minimal so generated OpenAPI schemas request only fields the decision reads.
- Checks return the standard check response; benefits expose `checks`, `isEligible`, and constant `nextSteps` through the standard benefit response. `nextSteps` uses `BDT.tNextSteps`, has no information requirements, and is evaluated once at startup for metadata export.
- Keep DMNDI shapes and edges valid so files remain usable in graphical DMN editors.

For creation work, read the matching skill instead of reconstructing these conventions:

- `.agents/skills/new-dmn-check/SKILL.md`
- `.agents/skills/new-dmn-benefit/SKILL.md`

## Java boundaries

- Never edit `target/generated-sources/kogito/` or other `target/` content.
- Editable REST/OpenAPI code lives under `src/main/java/org/codeforphilly/bdt/library/api/`.
- Editable custom FEEL functions live under `src/main/java/org/codeforphilly/bdt/library/functions/`.
- Preserve compatibility with Java 21 and the versions pinned in `pom.xml`.

## Testing

- Use Java tests for model discovery, path mapping, schema generation, and internal API behavior.
- Use Bruno tests for DMN business logic and endpoint behavior; mirror the DMN resource path under `test/bdt/`.
- When adding a DMN model, validate startup/model discovery and add pass/fail business scenarios appropriate to the rule.
