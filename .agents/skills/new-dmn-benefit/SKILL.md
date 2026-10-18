---
name: new-dmn-benefit
description: Create a DMN 1.6 benefit in library-api with KIE Tools 10.2-compatible layout and Bruno tests.
---

Create a new DMN benefit in `library-api`. Use `$ARGUMENTS` when the host expands it; otherwise derive the same information from the user's request. Arguments are optional: benefit name in PascalCase, state, and locality (for example, `PhlHomesteadExemption pa phl`).

## Step 1 — Gather Requirements

Parse `$ARGUMENTS` if provided (up to 3 positional tokens):
- First token (PascalCase word) → benefit name (e.g. `PhlHomesteadExemption`)
- Second token (2-letter lowercase) → state abbreviation (e.g. `pa`)
- Third token (lowercase) → locality (e.g. `phl`)

If any are missing, ask the user for:
1. **Benefit name** — PascalCase, globally unique (e.g. `PhlHomesteadExemption`). Derive the file name by converting to kebab-case (e.g. `homestead-exemption.dmn`) and the service name by appending `Service` (e.g. `PhlHomesteadExemptionService`).
2. **State** — optional 2-letter code (lowercase). Omit for federal benefits.
3. **Locality** — optional (lowercase). Requires state. Omit for state-level benefits.
Do **not** ask for parameters or FEEL logic — benefits always use the standard `checks`/`isEligible` pattern.

## Step 2 — Validate Before Generating

Run these checks before writing any files:

**A. Model name uniqueness** — Search all existing DMN files:
```
Grep for: name="{BenefitName}"
Path: library-api/src/main/resources/**/*.dmn
```
If a match is found, warn the user and stop. The name must be globally unique.

**B. Target directory** — Compute path:
- No state: `library-api/src/main/resources/benefits/`
- State only: `library-api/src/main/resources/benefits/{state}/`
- State+locality: `library-api/src/main/resources/benefits/{state}/{locality}/`

**C. Existing benefits** — List DMN files in the target directory so the user can confirm there are no near-duplicate benefits.

## Step 3 — Discover and Select Checks

1. Scan `library-api/src/main/resources/checks/**/*.dmn`. **Exclude** category base modules — files whose name matches the PascalCase category name (e.g. `Age.dmn`, `Enrollment.dmn`, `Residence.dmn`). These are base modules, not checks.

2. For each check DMN, read the file and extract:
   - **Model name** — the `name` attribute on `<dmn:definitions>`
   - **Namespace URI** — the `namespace` attribute on `<dmn:definitions>`
   - **Decision Service id** — the `id` attribute on the `<dmn:decisionService>` element
   - **Decision Service name** — the `name` attribute on the `<dmn:decisionService>` element
   - **Has `tParameters`** — whether a `<dmn:itemDefinition ... name="tParameters">` exists
   - **Parameter names and types** — if `tParameters` exists, the `name` and `typeRef` of each `<dmn:itemComponent>` inside it
   - **Category** — the directory name under `checks/` (e.g. `age`, `enrollment`, `residence`)
   - **Which `tSituation` fields it reads** — the `name` attributes of `<dmn:itemComponent>` elements inside the `<dmn:itemDefinition ... name="tSituation">` element
   - **File path** — relative path from `library-api/src/main/resources/` (e.g. `checks/enrollment/person-not-enrolled-in-benefit.dmn`)

3. Present the full list to the user in a table. Ask which checks to include in the benefit.

4. For each selected check **that has `tParameters`**, ask how to populate each parameter. Suggest common patterns:
   - `situation.primaryPersonId` for `personId` params
   - `"BenefitName"` (string literal) for `benefit` params
   - Number literals (e.g. `65`) for `minAge` params
   - `today()` or `date(today().year, 12, 31)` for date params

5. For each selected check, ask for a **descriptive variable name** for the check's context entry in the `checks` decision (e.g. `OwnerOccupant`, `NotAlreadyEnrolled`, `Age65Plus`). Default to the check's model name.

## Step 4 — Generate DMN File

**File path**: from Step 2B + kebab-case benefit name + `.dmn`

### Documentation

Read [the shared DMN documentation convention](../references/dmn-documentation.md)
when creating or updating a model. Put program scope and sources on benefits,
rule interpretation and input/boundary/unknown behavior on implementing checks,
and shared field contracts on their owning types. Use named documentation links
and selective diagram annotations instead of a separate program-rule Markdown
file. Preserve policy review dates when moving documentation and identify known
implementation gaps explicitly.


### DMN 1.6 namespaces

Generate DMN 1.6 XML. Use these exact namespace values; do not copy the
pre-1.6 values from an older model or editor example:

| Prefix/use | Namespace |
|---|---|
| `dmn` model and every import's `importType` | `https://www.omg.org/spec/DMN/20240513/MODEL/` |
| `feel` and `typeLanguage` | `https://www.omg.org/spec/DMN/20240513/FEEL/` |
| `dmndi` | `https://www.omg.org/spec/DMN/20230324/DMNDI/` |
| `di` | `http://www.omg.org/spec/DMN/20180521/DI/` |
| `dc` | `http://www.omg.org/spec/DMN/20180521/DC/` |
| `kie` | `https://kie.org/dmn/extensions/1.0` |

Keep the model's own default namespace as its fresh KIE UUID URI.

### Import path computation

Compute relative paths from the benefit DMN file to imported files:

| Benefit depth | Benefits.dmn | BDT.dmn | Check files |
|---|---|---|---|
| `benefits/` | `Benefits.dmn` | `../BDT.dmn` | `../checks/{category}/{file}` |
| `benefits/{state}/` | `../Benefits.dmn` | `../../BDT.dmn` | `../../checks/{category}/{file}` |
| `benefits/{state}/{locality}/` | `../../Benefits.dmn` | `../../../BDT.dmn` | `../../../checks/{category}/{file}` |

### xmlns and import numbering

Assign `included{N}` aliases in this order:
1. `included1` → Benefits.dmn
2. `included2` through `included{2+numChecks-1}` → each selected check DMN (in the order selected)
3. Next number → BDT.dmn
4. Remaining numbers → each unique category base module needed (e.g. `Enrollment.dmn`, `Residence.dmn`)

Only import a category base module if a type from it is referenced in the benefit's `tSituation` (e.g. `Enrollment.tEnrollmentList`). Unlike checks, benefits do NOT need to import category base modules by default.

### Type definitions

Define these local item definitions:

**`tSituation`** — union of all `tSituation` fields from the selected checks. Only include fields that at least one selected check reads. Deduplicate fields that appear in multiple checks.

**`tSimpleChecks`** (only if any check reads `simpleChecks`) — union of all `simpleChecks` boolean fields across selected checks.

**`tPerson`** and **`tPeople`** (only if any check reads `people`) — with `id: string` and `dateOfBirth: date`. `tPeople` is a collection of `tPerson`.

For fields that reference imported types (e.g. `enrollments: Enrollment.tEnrollmentList`, `relationships: BDT.tRelationshipList`), use the fully qualified type. For locally defined complex types (e.g. `simpleChecks: tSimpleChecks`), use the local type name.

### Decision Service

```xml
<dmn:decisionService id="_{DS_UUID}" name="{BenefitName}Service">
  <dmn:extensionElements/>
  <dmn:variable id="_{DS_VAR_UUID}" name="{BenefitName}Service" typeRef="Benefits.tBenefitResponse"/>
  <dmn:outputDecision href="#{CHECKS_DECISION_UUID}"/>
  <dmn:outputDecision href="#{ISELIGIBLE_DECISION_UUID}"/>
  <dmn:inputData href="#{SITUATION_INPUT_UUID}"/>
</dmn:decisionService>
```

Key rules:
- Return type is always `Benefits.tBenefitResponse`
- Two output decisions: `checks` and `isEligible`
- One input: `situation` only (benefits never take `parameters`)

### `checks` decision

Uses a `<dmn:context>` with one `<dmn:contextEntry>` per selected check.

Each entry uses `<dmn:invocation>` to call the check's Decision Service:

```xml
<dmn:contextEntry>
  <dmn:variable id="_{UUID}" name="{VariableName}" typeRef="boolean"/>
  <dmn:invocation id="_{UUID}">
    <dmn:literalExpression id="_{UUID}">
      <dmn:text>{CheckAlias}.{CheckName}Service</dmn:text>
    </dmn:literalExpression>
    <!-- Binding for situation (always present) -->
    <dmn:binding>
      <dmn:parameter id="_{UUID}" name="situation" typeRef="{CheckAlias}.tSituation"/>
      <dmn:literalExpression id="_{UUID}">
        <dmn:text>situation</dmn:text>
      </dmn:literalExpression>
    </dmn:binding>
    <!-- Binding for parameters (only if check has tParameters) -->
    <dmn:binding>
      <dmn:parameter id="_{UUID}" name="parameters" typeRef="{CheckAlias}.tParameters"/>
      <dmn:context id="_{UUID}">
        <!-- One contextEntry per parameter -->
        <dmn:contextEntry>
          <dmn:variable id="_{UUID}" name="{paramName}" typeRef="{paramType}"/>
          <dmn:literalExpression id="_{UUID}">
            <dmn:text>{paramValue from Step 3}</dmn:text>
          </dmn:literalExpression>
        </dmn:contextEntry>
      </dmn:context>
    </dmn:binding>
  </dmn:invocation>
</dmn:contextEntry>
```

The `checks` decision needs:
- `<dmn:informationRequirement>` referencing the `situation` input
- `<dmn:knowledgeRequirement>` for each imported check Decision Service: `href="{checkNamespace}#{checkServiceId}"`
- Variable typeRef is `context`

### `isEligible` decision

Standard pattern — identical in every benefit:

```xml
<dmn:decision id="_{ISELIGIBLE_UUID}" name="isEligible">
  <dmn:extensionElements/>
  <dmn:variable id="_{UUID}" name="isEligible" typeRef="boolean"/>
  <dmn:informationRequirement id="_{UUID}">
    <dmn:requiredDecision href="#{CHECKS_DECISION_UUID}"/>
  </dmn:informationRequirement>
  <dmn:context id="_{UUID}">
    <dmn:contextEntry>
      <dmn:variable id="_{UUID}" name="checksAsList" typeRef="BDT.tBooleanList"/>
      <dmn:literalExpression id="_{UUID}">
        <dmn:text>// e.g. [true, false, true, null, ...]
for check in (get entries(checks))
return
  check.value</dmn:text>
      </dmn:literalExpression>
    </dmn:contextEntry>
    <dmn:contextEntry>
      <dmn:variable id="_{UUID}" name="result" typeRef="boolean"/>
      <dmn:literalExpression id="_{UUID}">
        <dmn:text>// true if all checks are true
// null if there are any null checks
// false if any check is false
all(checksAsList)</dmn:text>
      </dmn:literalExpression>
    </dmn:contextEntry>
    <dmn:contextEntry>
      <dmn:literalExpression id="_{UUID}">
        <dmn:text>result</dmn:text>
      </dmn:literalExpression>
    </dmn:contextEntry>
  </dmn:context>
</dmn:decision>
```

### Input data

Only `situation` — benefits never have a `parameters` input:

```xml
<dmn:inputData id="_{SITUATION_INPUT_UUID}" name="situation">
  <dmn:extensionElements/>
  <dmn:variable id="_{UUID}" name="situation" typeRef="tSituation"/>
</dmn:inputData>
```

### DMNDI layout

Layout the diagram with:
- Imported check services in an **upper dependency row**, alongside and a little above the `checks` decision
- The expanded benefit Decision Service below that dependency row
- `situation` in a separate lower input row
- Edges from each imported check service to the `checks` decision
- Edge from `situation` to `checks` decision
- Edge from `checks` to `isEligible` decision

KIE Tools 10.2 clamps shapes smaller than its defaults when a model is opened.
Always generate geometry at or above these editor defaults:

- Ordinary decisions, inputs, and BKMs: **160×80 minimum**
- Expanded Decision Service: **280×280 minimum**
- Collapsed imported Decision Service: **exactly 300×100**, with `isCollapsed="true"`
- Expanded service divider: `service y + 140`
- Collapsed service divider: `service y + 50`

**Benefit service box**:
- Width: `max(440, len("{BenefitName}Service") * 12 + 40)`
- Height: `280`
- Place it below the dependency row; `y=100` is suitable when direct dependencies end at `y=100`
- Center it beneath the complete dependency row rather than anchoring it to a fixed x-coordinate
- Divider line: from `(x, y + 140)` to `(x + width, y + 140)`

**Output decisions inside the service box**:
- Both are `160×80` and use `y = service y + 30`
- For a 440px service, place `checks` at `x + 30` and `isEligible` at `x + 250`
- For a wider service, retain at least 30px left/right margins and distribute the two decisions evenly

**Situation input**: `160×80`, centered below the service with at least a 100px vertical gap; `y=480` is suitable for a service at `y=100`.

**Imported check services**:
- Use `300×100`, `isCollapsed="true"`, and a divider at `y + 50`
- Put direct dependencies in one upper row with at least 60px horizontal gaps
- Center the complete row over the `checks` decision; keep a normal left canvas margin
- If a referenced local BKM itself has knowledge requirements, put that BKM in an intermediate row and its own referenced BKMs/services in a row above it
- Never mark an imported Decision Service expanded unless its child shapes are also represented inside it

**Edges**:
- Recalculate every waypoint after placing shapes; do not retain coordinates from a copied template
- Knowledge edges run from the referenced BKM/service toward the referencing decision
- Information edges run upward from the lower input row
- Terminate each edge on the border of its target node, not on the enclosing Decision Service

**ComponentWidths**: include `<kie:ComponentWidths>` entries for every context, invocation, and literal expression. Use these standard widths:
- `checks` context: `<kie:width>50</kie:width><kie:width>376</kie:width><kie:width>599</kie:width>`
- Each invocation: `<kie:width>50</kie:width><kie:width>239</kie:width><kie:width>290</kie:width>`
- Each invocation's literal expression (the service name): no widths (self-closing)
- Each binding's literal expression: `<kie:width>290</kie:width>`
- Parameter context (if any): `<kie:width>50</kie:width><kie:width>120</kie:width><kie:width>100</kie:width>`
- Each parameter value expression: `<kie:width>100</kie:width>`
- `isEligible` context: `<kie:width>50</kie:width><kie:width>222</kie:width><kie:width>504</kie:width>`
- Each `isEligible` literal expression: `<kie:width>504</kie:width>`

### Full template structure

Read [references/dmn-skeleton.md](references/dmn-skeleton.md) when generating the DMN XML. It preserves the import and element ordering expected by this workflow.

### Constraints to enforce

1. **Fresh UUID v4** for every `id` attribute — format `_XXXXXXXX-XXXX-4XXX-XXXX-XXXXXXXXXXXX` using uppercase hex. Each UUID must be unique within the file.
2. **Model namespace** is a fresh UUID URI: `https://kie.apache.org/dmn/_{UUID}`.
3. **No `parameters` input** — benefits only take `situation`.
4. **Service return type**: `Benefits.tBenefitResponse`.
5. **Output decisions**: `checks` (typeRef `context`) + `isEligible` (typeRef `boolean`).
6. **Namespace-qualified invocations**: always `{CheckAlias}.{CheckServiceName}` in invocation literal expressions.
7. **`knowledgeRequirement`** for each imported check service — `href="{checkNamespace}#{checkServiceId}"`. This ensures DMNDI shapes and edges render correctly.
8. **Category base modules imported only when types referenced** — e.g. import `Enrollment.dmn` only if `Enrollment.tEnrollmentList` appears in `tSituation`.
9. **Known namespace URIs** — Benefits.dmn: `https://kie.apache.org/dmn/_9514D95A-63FB-4345-911B-D83E1867F709`, BDT.dmn: `https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79`. Read check and category base module DMNs to get their namespace URIs — do not hardcode them.

## Step 5 — Generate Bruno Test Files

**Directory**: `library-api/test/bdt/benefits/{state?}/{locality?}/{BenefitName}/`

Create `folder.bru` files for any **new** intermediate directories that don't already have one. Check before creating — the `benefits/`, `benefits/pa/`, and `benefits/pa/phl/` folder.bru files likely already exist.

**Folder.bru format** (for the benefit test folder):
```bru
meta {
  name: {BenefitName}
  seq: {next sequential number}
}

auth {
  mode: inherit
}
```

**URL pattern**: `{{host}}/benefits/{state}/{locality}/{benefit-kebab-name}`
- If no state: `{{host}}/benefits/{benefit-kebab-name}`
- If state only: `{{host}}/benefits/{state}/{benefit-kebab-name}`
- If state+locality: `{{host}}/benefits/{state}/{locality}/{benefit-kebab-name}`

Create three test files:

### Smoke Test.bru (seq: 1)

Send partial data — enough for some checks to evaluate but missing fields for others. Expect `isEligible: eq null`.

```bru
meta {
  name: Smoke Test
  type: http
  seq: 1
}

post {
  url: {{host}}/benefits/{path}
  body: json
  auth: inherit
}

body:json {
  {
    "situation": {
      {partial situation data — include enough fields for one check to pass,
       but omit fields needed by other checks so they return null}
    }
  }
}

assert {
  res.body.isEligible: eq null
}

settings {
  encodeUrl: true
  timeout: 0
}
```

### Eligible.bru (seq: 2)

Send complete data where all checks pass. Expect `isEligible: eq true`.

```bru
meta {
  name: Eligible
  type: http
  seq: 2
}

post {
  url: {{host}}/benefits/{path}
  body: json
  auth: inherit
}

body:json {
  {
    "situation": {
      {complete situation data where every check evaluates to true}
    }
  }
}

assert {
  res.body.isEligible: eq true
}

settings {
  encodeUrl: true
  timeout: 0
}
```

### Ineligible.bru (seq: 3)

Send complete data where at least one check fails. Expect `isEligible: eq false`.

```bru
meta {
  name: Ineligible
  type: http
  seq: 3
}

post {
  url: {{host}}/benefits/{path}
  body: json
  auth: inherit
}

body:json {
  {
    "situation": {
      {complete situation data where at least one check evaluates to false}
    }
  }
}

assert {
  res.body.isEligible: eq false
}

settings {
  encodeUrl: true
  timeout: 0
}
```

**Test data guidelines**:
- Use realistic field values: concrete person IDs (e.g. `"p1"`, `"p2"`), ISO 8601 dates, descriptive enrollment benefits
- For enrollment-based checks (e.g. `PersonNotEnrolledInBenefit`): Eligible case has enrollments for *other* benefits or *other* people; Ineligible case has the primary person enrolled in this benefit
- For age-based checks: use `dateOfBirth` values that clearly satisfy or fail the age condition
- For simpleChecks-based checks: set the boolean fields to `true` (pass) or `false` (fail) explicitly
- Smoke Test should include `primaryPersonId` and one or two fields but leave other check inputs missing

## Step 6 — Run Tests

**1. Static DMN validation** — before running the application tests:

- Validate descriptions, documentation links, annotation/association references and documentation preservation using the shared convention.
- Parse the new file as XML.
- Confirm the DMN 1.6 namespace set from Step 4 and verify that no old 20180521 model/FEEL/DMNDI or Drools 1.2 extension namespace remains.
- Check every DMNDI shape against the 10.2 minimum sizes, every imported Decision Service's collapsed state and divider, and every edge's source/target reference and final waypoint.
- Run `git diff --check`.

**2. Maven tests** — compile and verify the DMN integrates correctly:
```bash
devbox run test -- library-api
```
If tests fail, diagnose the error (most likely a malformed DMN, missing import, or namespace conflict) and fix the DMN file before proceeding.

**3. Bruno tests** — run the full API test suite (requires the library-api dev server running at `http://localhost:8083`):
```bash
cd library-api/test/bdt && bru run
```
If the server is not running, start it first (`cd library-api && quarkus dev`), wait for it to be ready, then run `bru run`.

If any Bruno tests fail:
- Check that the endpoint URL in the `.bru` files matches the actual generated route (verify in Swagger UI at `http://localhost:8083/q/swagger-ui`)
- Verify the test data produces the expected `true`/`false`/`null` outcomes for each check
- Fix the DMN or `.bru` files and re-run until all tests pass

**Common failure causes**:
- **"Model not found"**: Decision Service name doesn't match `{BenefitName}Service`
- **XML parse error**: Malformed UUID, missing closing tag, or mismatched namespace
- **Wrong check result**: Parameter binding values are incorrect (check the FEEL expressions in invocations)
- **null instead of true/false**: `tSituation` is missing a field that a check needs
- **Endpoint 404**: File path doesn't match expected URL pattern

Only proceed to Step 7 once **both** `mvn test` and `bru run` pass cleanly.

## Step 7 — Print Summary

After all tests pass, print:

```
## Files Created

- library-api/src/main/resources/benefits/{path}/{benefit-name}.dmn
- library-api/test/bdt/benefits/{path}/{BenefitName}/folder.bru
- library-api/test/bdt/benefits/{path}/{BenefitName}/Smoke Test.bru
- library-api/test/bdt/benefits/{path}/{BenefitName}/Eligible.bru
- library-api/test/bdt/benefits/{path}/{BenefitName}/Ineligible.bru

## Checks Included

- {VariableName}: {CheckModelName} ({category}) {with parameters: ...}
- ...

## Next Steps

- [ ] Verify the endpoint in Swagger UI:
      http://localhost:8083/q/swagger-ui  (look for POST /benefits/{path}/{benefit-name})
- [ ] Review the checks context to confirm parameter bindings are correct
- [ ] Consider adding more test cases for edge conditions
- [ ] Run sync-library-metadata.sh if you need this benefit visible in builder-frontend
```

---

## Critical Constraints (always enforce)

1. **Model name uniqueness** — check all `.dmn` files before generating. Stop if duplicate found.
2. **Service name** — must be exactly `{BenefitName}Service`. No variations.
3. **File name** — kebab-case, `.dmn` extension (e.g. `homestead-exemption.dmn`).
4. **Namespace-qualified references** — when calling imported check services, always prefix with the import alias (e.g. `PersonNotEnrolledInBenefit.PersonNotEnrolledInBenefitService`).
5. **No circular imports** — benefits import checks; checks never import benefits.
6. **Import paths are relative** — compute based on benefit file depth under `benefits/`.
7. **Fresh UUIDs** — generate a new UUID v4 for every `id` attribute. Never reuse UUIDs from example files.
8. **`tSituation` is local and minimal** — define only the `situation` fields needed by the selected checks. Deduplicate across checks. Use local types for nested structures (e.g. `tSimpleChecks`) with only the fields actually needed.
9. **Output decisions** — `checks` (typeRef `context`) and `isEligible` (typeRef `boolean`). These are always the two outputs.
10. **Benefits never take `parameters`** — the only input is `situation`. Do not add a `parameters` input or `tParameters` type.
11. **`isEligible` pattern is fixed** — always uses `get entries(checks)` → `all(checksAsList)`. Do not customize this.
12. **`knowledgeRequirement` for every imported check service** — each check invoked in the `checks` context must have a corresponding `<dmn:knowledgeRequirement>` element with `href="{namespace}#{serviceId}"`.
13. **Benefits.dmn namespace** — always `https://kie.apache.org/dmn/_9514D95A-63FB-4345-911B-D83E1867F709`. Read this from the file, do not guess.
14. **BDT.dmn namespace** — always `https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79`.
15. **DMN 1.6 only** — use the 20240513 model/FEEL namespaces, 20230324 DMNDI namespace, and KIE extensions 1.0 namespace from Step 4.
16. **KIE Tools 10.2 geometry** — ordinary nodes are at least 160×80, expanded services at least 280×280, and imported collapsed services exactly 300×100 with correct divider lines.
17. **Knowledge above, inputs below** — referenced BKMs and Decision Services belong beside/above the referencing decision; ordinary input data belongs below it.
