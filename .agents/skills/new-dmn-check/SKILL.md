---
name: new-dmn-check
description: Create a DMN 1.6 eligibility check in library-api with KIE Tools 10.2-compatible layout and Bruno tests.
---

Create a new DMN eligibility check in `library-api`. Use `$ARGUMENTS` when the host expands it; otherwise derive the same information from the user's request. Arguments are optional: check name in PascalCase and/or category (for example, `PersonMinIncome income`).

## Step 1 — Gather Requirements

Parse `$ARGUMENTS` if provided:
- First token (PascalCase word) → check name (e.g. `PersonMinIncome`)
- Second token (lowercase word) → category (e.g. `income`)

If either is missing, ask the user for:
1. **Check name** — PascalCase, globally unique (e.g. `PersonMinIncome`). Derive the file name by converting to kebab-case (e.g. `person-min-income.dmn`) and the service name by appending `Service` (e.g. `PersonMinIncomeService`).
2. **Category** — existing (`age`, `enrollment`) or a new category name.
3. **Description** — one sentence describing what eligibility condition is checked.
4. **Parameters** beyond `situation` — for each: name, FEEL type (`string`, `number`, `date`, `boolean`), and purpose. If there are none, the `parameters` input will be omitted entirely from the DMN model.
5. **FEEL logic** — either a FEEL expression or a plain-English description. Describe which `situation` fields are used (e.g. `situation.people`, `situation.enrollments`, `situation.simpleChecks.*`).
6. **Does the logic need intermediate values?** — Yes → context-chain pattern; No → simple literal expression pattern.

## Step 2 — Validate Before Generating

Run these checks before writing any files:

**A. Model name uniqueness** — Search all existing DMN files:
```
Grep for: name="{CheckName}"
Path: library-api/src/main/resources/**/*.dmn
```
If a match is found, warn the user and stop. The name must be globally unique.

**B. Category directory** — Check if `library-api/src/main/resources/checks/{category}/` exists.
- If it exists, note the base module file (e.g. `Age.dmn`, `Enrollment.dmn`) and its namespace URI for importing.
- If it doesn't exist, warn the user that a base module DMN must be created first for the new category (with its own namespace URI, types, and BKMs), and offer to create a minimal one.

**C. Existing checks in category** — List existing check DMNs in the category directory so the user can confirm there are no near-duplicate checks.

## Step 3 — Generate the DMN File

**File path**: `library-api/src/main/resources/checks/{category}/{check-name}.dmn`

### Documentation

Read [the shared DMN documentation convention](../references/dmn-documentation.md)
when creating or updating a model. Put program scope and sources on benefits,
rule interpretation and input/boundary/unknown behavior on implementing checks,
and shared field contracts on their owning types. Use named documentation links
and selective diagram annotations instead of a separate program-rule Markdown
file. Preserve policy review dates when moving documentation and identify known
implementation gaps explicitly.


### DMN 1.6 namespaces

Generate DMN 1.6 XML with these exact values:

| Prefix/use | Namespace |
|---|---|
| `dmn` model and every import's `importType` | `https://www.omg.org/spec/DMN/20240513/MODEL/` |
| `feel` and `typeLanguage` | `https://www.omg.org/spec/DMN/20240513/FEEL/` |
| `dmndi` | `https://www.omg.org/spec/DMN/20230324/DMNDI/` |
| `di` | `http://www.omg.org/spec/DMN/20180521/DI/` |
| `dc` | `http://www.omg.org/spec/DMN/20180521/DC/` |
| `kie` | `https://kie.org/dmn/extensions/1.0` |

Do not copy the 20180521 model/FEEL/DMNDI or Drools 1.2 extension
namespaces from older files or examples. Keep the model's own default
namespace as its fresh KIE UUID URI.

**Rules**:
- Generate fresh UUID v4 values for every `id` attribute (format: `_XXXXXXXX-XXXX-4XXX-XXXX-XXXXXXXXXXXX` using uppercase hex). Each UUID must be unique within the file.
- The model's own `namespace` attribute is a fresh UUID URI: `https://kie.apache.org/dmn/_{UUID}`.
- BDT namespace is always: `https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79`
- BDT `locationURI`: use `../BDT.dmn` for checks directly in `checks/{category}/` (one level deep). Use `../../BDT.dmn` if nested deeper.
- Category base module import is **mandatory** — always import `{Category}.dmn` (same directory) even if no types from it are referenced. This establishes the category association.
- Decision Service name: exactly `{CheckName}Service`.
- Decision Service output type: `BDT.tCheckResponse`.
- The output decision must be named `checkResult` with `typeRef="boolean"`.
- **Parameters**: if the check needs caller-supplied values, define a `tParameters` item definition and include the `parameters` inputData node, its `dmn:informationRequirement`, and its DMNDI shape/edge. If the check has no parameters, omit all of these entirely — no `tParameters` type, no `parameters` inputData element. This way the generated OpenAPI schema accurately reflects that the endpoint takes only `situation`.
- `tSituation` item definition includes only the fields the check actually reads (keep it minimal — don't copy BDT's full tSituation).
- If a `tSituation` field is itself a complex type (e.g. `simpleChecks`), define a **local** version of that nested type containing only the specific properties this check uses. Reference the local type, not the BDT one. Example: if the check reads only `situation.simpleChecks.ownerOccupant`, define a local `tSimpleChecks` with just `ownerOccupant: boolean`, and use `typeRef="tSimpleChecks"` in `tSituation` (not `BDT.tSimpleChecks`).

### Inverse Check Pattern

If the new check is the logical inverse of an **existing** check (e.g. `NoTenYearTaxAbatement` is the inverse of `TenYearTaxAbatement`), model it by importing and negating — do **not** duplicate the logic:

1. Add a third import for the sibling check DMN (e.g. `xmlns:included3="{siblingNamespace}"` and a `<dmn:import>` element).
2. Add a `<dmn:knowledgeRequirement>` in the `checkResult` decision referencing the sibling's Decision Service id: `href="{siblingNamespace}#{siblingServiceId}"`.
3. Write the FEEL expression as: `not({SiblingAlias}.{SiblingCheckName}Service(situation: situation))` — or include `parameters: parameters` if the sibling takes parameters.
4. In DMNDI, add a collapsed `300×100` `<dmndi:DMNShape isCollapsed="true">` for the imported service (using `dmnElementRef="included3:{siblingServiceId}"`) and a `<dmndi:DMNEdge>` for the knowledge requirement edge. Place the imported service above and alongside the referencing decision, with its divider at `y + 50`.
5. The sibling check's namespace URI and Decision Service id can be found in its DMN file — read it before generating.

**Example**: `no-ten-year-tax-abatement.dmn` imports `ten-year-tax-abatement.dmn` and evaluates:
```
not(TenYearTaxAbatement.TenYearTaxAbatementService(situation: situation))
```
This mirrors `person-not-enrolled-in-benefit.dmn` which imports `person-enrolled-in-benefit.dmn`.

### Template A — Simple Literal Expression

Use when the FEEL logic is a single expression with no intermediate values needed.

Based on `person-enrolled-in-benefit.dmn`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<dmn:definitions
    xmlns:dmn="https://www.omg.org/spec/DMN/20240513/MODEL/"
    xmlns="https://kie.apache.org/dmn/_{MODEL_NAMESPACE_UUID}"
    xmlns:feel="https://www.omg.org/spec/DMN/20240513/FEEL/"
    xmlns:kie="https://kie.org/dmn/extensions/1.0"
    xmlns:dmndi="https://www.omg.org/spec/DMN/20230324/DMNDI/"
    xmlns:di="http://www.omg.org/spec/DMN/20180521/DI/"
    xmlns:dc="http://www.omg.org/spec/DMN/20180521/DC/"
    xmlns:included1="https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79"
    xmlns:included2="{CATEGORY_MODULE_NAMESPACE_URI}"
    id="_{DEFINITIONS_UUID}"
    name="{CheckName}"
    typeLanguage="https://www.omg.org/spec/DMN/20240513/FEEL/"
    namespace="https://kie.apache.org/dmn/_{MODEL_NAMESPACE_UUID}">
  <dmn:description>{One sentence description}</dmn:description>
  <dmn:extensionElements>
    <kie:attachment name="{Authoritative source title or technical contract reference}"
                    url="{Source URL}" />
  </dmn:extensionElements>
  <dmn:import id="_{IMPORT_BDT_UUID}" name="BDT"
      namespace="https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79"
      locationURI="../BDT.dmn"
      importType="https://www.omg.org/spec/DMN/20240513/MODEL/"/>
  <!-- Always import the category base module, even if no types from it are referenced -->
  <dmn:import id="_{IMPORT_CATEGORY_UUID}" name="{Category}"
      namespace="{CATEGORY_MODULE_NAMESPACE_URI}"
      locationURI="{Category}.dmn"
      importType="https://www.omg.org/spec/DMN/20240513/MODEL/"/>

  <!-- OMIT the tParameters block entirely if the check has no parameters -->
  <dmn:itemDefinition id="_{TPARAMS_UUID}" name="tParameters" isCollection="false">
    <!-- One dmn:itemComponent per parameter -->
    <dmn:itemComponent id="_{PARAM1_UUID}" name="{paramName}" isCollection="false">
      <dmn:typeRef>{feelType}</dmn:typeRef>
    </dmn:itemComponent>
  </dmn:itemDefinition>

  <dmn:itemDefinition id="_{TSITUATION_UUID}" name="tSituation" isCollection="false">
    <!-- Only the fields this check actually reads from situation -->
    <!-- Example for enrollment check: -->
    <dmn:itemComponent id="_{SITUATION_FIELD_UUID}" name="enrollments" isCollection="false">
      <dmn:typeRef>Enrollment.tEnrollmentList</dmn:typeRef>
    </dmn:itemComponent>
  </dmn:itemDefinition>

  <dmn:decisionService id="_{DS_UUID}" name="{CheckName}Service">
    <dmn:extensionElements/>
    <dmn:variable id="_{DS_VAR_UUID}" name="{CheckName}Service" typeRef="BDT.tCheckResponse"/>
    <dmn:outputDecision href="#{DECISION_UUID}"/>
    <dmn:inputData href="#{SITUATION_INPUT_UUID}"/>
    <!-- OMIT the next line if the check has no parameters -->
    <dmn:inputData href="#{PARAMS_INPUT_UUID}"/>
  </dmn:decisionService>

  <dmn:decision id="_{DECISION_UUID}" name="checkResult">
    <dmn:extensionElements/>
    <dmn:variable id="_{DECISION_VAR_UUID}" name="checkResult" typeRef="boolean"/>
    <dmn:informationRequirement id="_{IR1_UUID}">
      <dmn:requiredInput href="#{SITUATION_INPUT_UUID}"/>
    </dmn:informationRequirement>
    <!-- OMIT the next informationRequirement if the check has no parameters -->
    <dmn:informationRequirement id="_{IR2_UUID}">
      <dmn:requiredInput href="#{PARAMS_INPUT_UUID}"/>
    </dmn:informationRequirement>
    <dmn:literalExpression id="_{EXPR_UUID}">
      <dmn:text>{FEEL expression}</dmn:text>
    </dmn:literalExpression>
  </dmn:decision>

  <dmn:inputData id="_{SITUATION_INPUT_UUID}" name="situation">
    <dmn:extensionElements/>
    <dmn:variable id="_{SITUATION_VAR_UUID}" name="situation" typeRef="tSituation"/>
  </dmn:inputData>
  <!-- OMIT the next inputData block entirely if the check has no parameters -->
  <dmn:inputData id="_{PARAMS_INPUT_UUID}" name="parameters">
    <dmn:extensionElements/>
    <dmn:variable id="_{PARAMS_VAR_UUID}" name="parameters" typeRef="tParameters"/>
  </dmn:inputData>
  <dmndi:DMNDI>
    <dmndi:DMNDiagram id="_{DIAGRAM_UUID}" name="DRG">
      <di:extension>
        <kie:ComponentsWidthsExtension>
          <kie:ComponentWidths dmnElementRef="_{EXPR_UUID}">
            <kie:width>917</kie:width>
          </kie:ComponentWidths>
        </kie:ComponentsWidthsExtension>
      </di:extension>
      <!-- Expanded Decision Service: KIE Tools 10.2 minimum is 280×280.
           Its output decision is in the upper section; inputs go below. -->
      <dmndi:DMNShape id="dmnshape-drg-_{DS_UUID}" dmnElementRef="_{DS_UUID}" isCollapsed="false">
        <dmndi:DMNStyle>
          <dmndi:FillColor red="255" green="255" blue="255"/>
          <dmndi:StrokeColor red="0" green="0" blue="0"/>
          <dmndi:FontColor red="0" green="0" blue="0"/>
        </dmndi:DMNStyle>
        <dc:Bounds x="{DS_X}" y="100" width="{DS_WIDTH}" height="280"/>
        <dmndi:DMNLabel/>
        <dmndi:DMNDecisionServiceDividerLine>
          <di:waypoint x="{DS_X}" y="240"/>
          <di:waypoint x="{DS_X_RIGHT}" y="240"/>
        </dmndi:DMNDecisionServiceDividerLine>
      </dmndi:DMNShape>
      <!-- Ordinary nodes must be at least 160×80 in KIE Tools 10.2. -->
      <dmndi:DMNShape id="dmnshape-drg-_{DECISION_UUID}" dmnElementRef="_{DECISION_UUID}" isCollapsed="false">
        <dmndi:DMNStyle>
          <dmndi:FillColor red="255" green="255" blue="255"/>
          <dmndi:StrokeColor red="0" green="0" blue="0"/>
          <dmndi:FontColor red="0" green="0" blue="0"/>
        </dmndi:DMNStyle>
        <dc:Bounds x="{DECISION_X}" y="130" width="160" height="80"/>
        <dmndi:DMNLabel/>
      </dmndi:DMNShape>
      <!-- Input nodes use a lower row, at least 100px below the service box. -->
      <dmndi:DMNShape id="dmnshape-drg-_{SITUATION_INPUT_UUID}" dmnElementRef="_{SITUATION_INPUT_UUID}" isCollapsed="false">
        <dmndi:DMNStyle>
          <dmndi:FillColor red="255" green="255" blue="255"/>
          <dmndi:StrokeColor red="0" green="0" blue="0"/>
          <dmndi:FontColor red="0" green="0" blue="0"/>
        </dmndi:DMNStyle>
        <dc:Bounds x="{SITUATION_X}" y="480" width="160" height="80"/>
        <dmndi:DMNLabel/>
      </dmndi:DMNShape>
      <!-- OMIT the next DMNShape if the check has no parameters -->
      <dmndi:DMNShape id="dmnshape-drg-_{PARAMS_INPUT_UUID}" dmnElementRef="_{PARAMS_INPUT_UUID}" isCollapsed="false">
        <dmndi:DMNStyle>
          <dmndi:FillColor red="255" green="255" blue="255"/>
          <dmndi:StrokeColor red="0" green="0" blue="0"/>
          <dmndi:FontColor red="0" green="0" blue="0"/>
        </dmndi:DMNStyle>
        <dc:Bounds x="{PARAMS_X}" y="480" width="160" height="80"/>
        <dmndi:DMNLabel/>
      </dmndi:DMNShape>
      <!-- Recalculate waypoints from the final geometry. Inputs flow upward to checkResult. -->
      <dmndi:DMNEdge id="dmnedge-drg-_{IR1_UUID}-AUTO-TARGET" dmnElementRef="_{IR1_UUID}">
        <di:waypoint x="{SITUATION_CENTER_X}" y="520"/>
        <di:waypoint x="{SITUATION_TARGET_X}" y="210"/>
      </dmndi:DMNEdge>
      <!-- OMIT the next DMNEdge if the check has no parameters -->
      <dmndi:DMNEdge id="dmnedge-drg-_{IR2_UUID}-AUTO-TARGET" dmnElementRef="_{IR2_UUID}">
        <di:waypoint x="{PARAMS_CENTER_X}" y="520"/>
        <di:waypoint x="{PARAMS_TARGET_X}" y="210"/>
      </dmndi:DMNEdge>
    </dmndi:DMNDiagram>
  </dmndi:DMNDI>
</dmn:definitions>
```

**DMNDI layout coordinate guide** (fill in the placeholders above):
- `{DS_WIDTH}` — `max(280, len("{CheckName}Service") * 12 + 40)`; round up to a convenient integer
- `{DS_X}` — choose a center that leaves room for the complete lower input row and any upper knowledge references
- `{DS_X_RIGHT}` — `DS_X + DS_WIDTH`
- `{DECISION_X}` — `DS_X + (DS_WIDTH - 160) / 2`; y is **130**
- Lower-row width for `N` inputs is `N * 160 + (N - 1) * 60`; center that complete row beneath the Decision Service
- `{SITUATION_CENTER_X}` — `SITUATION_X + 80`
- `{PARAMS_CENTER_X}` — `PARAMS_X + 80`; omit if no parameters
- `{SITUATION_TARGET_X}` and `{PARAMS_TARGET_X}` — distinct points on the bottom border of `checkResult`, calculated from the source/target centers so multiple edges do not completely overlap

**DMNDI rules** (always enforce):
- Ordinary decisions, input data, and BKMs are at least **160×80**.
- Expanded Decision Services are at least **280×280**, with the divider at `y + 140`.
- Imported Decision Services are collapsed: `isCollapsed="true"`, **300×100**, divider at `y + 50`.
- Input nodes (`situation`, `parameters`) go in the lower row (`y=480` for a service ending at `y=380`).
- Referenced BKMs and Decision Services go alongside and above the referencing decision: a 160×80 BKM can use `y=20`, and a 300×100 imported service can use `y=0` when the local service starts at `y=100`.
- If a referenced BKM has its own knowledge requirements, use another dependency tier above it.
- Recalculate all edge waypoints after layout. The target waypoint must land on the referencing node, never on its enclosing Decision Service.
- Do **not** add `DMNShape` entries for BKMs or decisions imported from BDT or the category module unless they are directly called (via `dmn:knowledgeRequirement`) by this check. Unused imported elements clutter the diagram.

### Template B — Context Chain

Use when the FEEL logic needs intermediate computed values (e.g. extract a field, call a BKM, then compare).

Based on `person-min-age.dmn`. The decision body uses a `dmn:context` instead of `dmn:literalExpression`:

```xml
  <dmn:decision id="_{DECISION_UUID}" name="checkResult">
    <dmn:extensionElements/>
    <dmn:variable id="_{DECISION_VAR_UUID}" name="checkResult" typeRef="boolean"/>
    <dmn:informationRequirement id="_{IR1_UUID}">
      <dmn:requiredInput href="#{SITUATION_INPUT_UUID}"/>
    </dmn:informationRequirement>
    <dmn:informationRequirement id="_{IR2_UUID}">
      <dmn:requiredInput href="#{PARAMS_INPUT_UUID}"/>
    </dmn:informationRequirement>
    <!-- Add dmn:knowledgeRequirement here if calling a BKM from an imported module:
    <dmn:knowledgeRequirement id="_{KR_UUID}">
      <dmn:requiredKnowledge href="{importedModuleNamespace}#{bkmId}"/>
    </dmn:knowledgeRequirement>
    -->
    <dmn:context id="_{CONTEXT_UUID}">
      <!-- Named intermediate value entries -->
      <dmn:contextEntry>
        <dmn:variable id="_{VAR1_UUID}" name="{intermediateName}" typeRef="{feelType}"/>
        <dmn:literalExpression id="_{EXPR1_UUID}">
          <dmn:text>{FEEL expression for this step}</dmn:text>
        </dmn:literalExpression>
      </dmn:contextEntry>
      <!-- ... more entries as needed ... -->
      <!-- Final (unnamed) entry returns the boolean result -->
      <dmn:contextEntry>
        <dmn:literalExpression id="_{FINAL_EXPR_UUID}">
          <dmn:text>{final boolean expression, e.g.: result}</dmn:text>
        </dmn:literalExpression>
      </dmn:contextEntry>
    </dmn:context>
  </dmn:decision>
```

The rest of the file structure (imports, itemDefinitions, inputData, decisionService, DMNDI) is identical to Template A.

**BKM knowledge requirement note**: When calling a BKM from an imported module (e.g. `Age.as of date(...)`), the `<dmn:knowledgeRequirement>` href must reference the imported module's namespace URI followed by `#` and the BKM's `id` attribute in that file. Find the BKM id by reading the base module DMN. Use namespace-qualified calls in FEEL: `{ImportAlias}.{bkmName}(...)`.

## Step 4 — Generate Bruno Test Files

**Directory**: `library-api/test/bdt/checks/{category}/{CheckName}/`

Create three files:

**Pass.bru** — a request that should make `checkResult` evaluate to `true`:
```bru
meta {
  name: Pass
  type: http
  seq: 1
}

post {
  url: {{host}}/checks/{category}/{check-name}
  body: json
  auth: inherit
}

body:json {
  {
    "situation": {
      {situationFieldsForPassCase}
    },
    "parameters": {
      {parametersForPassCase}
    }
  }
}

assert {
  res.body.checkResult: eq true
  res.status: eq 200
}
```

**Fail.bru** — a request that should make `checkResult` evaluate to `false`:
```bru
meta {
  name: Fail
  type: http
  seq: 2
}

post {
  url: {{host}}/checks/{category}/{check-name}
  body: json
  auth: inherit
}

body:json {
  {
    "situation": {
      {situationFieldsForFailCase}
    },
    "parameters": {
      {parametersForFailCase}
    }
  }
}

assert {
  res.body.checkResult: eq false
  res.status: eq 200
}
```

**Null.bru** — a request that should make `checkResult` evaluate to `null` (missing/unknown data):
```bru
meta {
  name: Null
  type: http
  seq: 3
}

post {
  url: {{host}}/checks/{category}/{check-name}
  body: json
  auth: inherit
}

body:json {
  {
    "situation": {
      {situationFieldsForNullCase}
    },
    "parameters": {
      {parametersForNullCase}
    }
  }
}

assert {
  res.body.checkResult: eq null
  res.status: eq 200
}
```

Use a situation where the relevant field is absent or set to `null`, triggering the null-return branch of the FEEL expression.

**simpleChecks-based checks**: when the check reads `situation.simpleChecks.<fieldName>`, the Null case must set the **specific boolean field** to `null` — not the entire `simpleChecks` object. This tests the inner null guard in the FEEL expression:
```json
"situation": {
  "simpleChecks": {
    "tenYearTaxAbatement": null
  }
}
```
Not:
```json
"situation": {
  "simpleChecks": null
}
```

**Parameterless checks**: omit `"parameters"` from the request body entirely in all three test files. Do not send `"parameters": {}` or `"parameters": null`.

Use realistic test data: concrete IDs (e.g. `"p1"`), dates (ISO 8601), and parameter values that clearly demonstrate the pass vs. fail condition.

## Step 5 — Run Tests

Run static validation and the full test suites to verify the new check integrates correctly.

**1. Static DMN validation**:

- Validate descriptions, documentation links, annotation/association references and documentation preservation using the shared convention.
- Parse the new file as XML.
- Confirm the DMN 1.6 namespace set from Step 3 and verify that no old 20180521 model/FEEL/DMNDI or Drools 1.2 extension namespace remains.
- Check every DMNDI shape against the 10.2 minimum sizes, every imported Decision Service's collapsed state and divider, and every edge's source/target reference and final waypoint.
- Run `git diff --check`.

**2. Maven tests** — compile and run the Java test suite:
```bash
devbox run test -- library-api
```
If tests fail, diagnose the error (most likely a malformed DMN or namespace conflict) and fix the DMN file before proceeding.

**3. Bruno tests** — run the full API test suite (requires the library-api dev server running at `http://localhost:8083`):
```bash
cd library-api/test/bdt && bru run
```
If the server is not running, start it first (`cd library-api && quarkus dev`), wait for it to be ready, then run `bru run`.

If any Bruno tests fail:
- Check that the endpoint URL in the `.bru` files matches the actual generated route (verify in Swagger UI at `http://localhost:8083/q/swagger-ui`)
- Verify the FEEL logic produces the expected `true`/`false`/`null` outputs for each test case
- Fix the DMN or `.bru` files and re-run until all tests pass

Only proceed to Step 6 once **both** `mvn test` and `bru run` pass cleanly.

## Step 6 — Print a Summary and Next-Steps Checklist

After all tests pass, print:

```
## Files Created

- library-api/src/main/resources/checks/{category}/{check-name}.dmn
- library-api/test/bdt/checks/{category}/{CheckName}/Pass.bru
- library-api/test/bdt/checks/{category}/{CheckName}/Fail.bru
- library-api/test/bdt/checks/{category}/{CheckName}/Null.bru

## Next Steps

- [ ] Verify the endpoint appears in Swagger UI:
      http://localhost:8083/q/swagger-ui  (look for POST /checks/{category}/{check-name})
- [ ] If you created a new category, create the base module DMN first:
      library-api/src/main/resources/checks/{category}/{Category}.dmn
```

---

## Critical Constraints (always enforce)

1. **Model name uniqueness** — check all `.dmn` files before generating. Stop if duplicate found.
2. **Service name** — must be exactly `{CheckName}Service`. No variations.
3. **File name** — kebab-case, `.dmn` extension (e.g. `person-min-income.dmn`).
4. **Namespace-qualified references** — when calling imported BKMs or decisions, always prefix with the import alias (e.g. `Age.as of date(...)`, `BDT.tCheckResponse`).
5. **No circular imports** — checks import BDT.dmn and their category base module, and may also import one sibling check when implementing the "inverse check" pattern (see Step 3). They must never create cycles (A imports B imports A).
6. **BDT import path** — relative to the check file: `../BDT.dmn` for `checks/{category}/` files.
7. **Fresh UUIDs** — generate a new UUID v4 for every `id` attribute. Never reuse UUIDs from example files.
8. **tSituation is local and minimal** — define only the `situation` fields this check actually reads. Do not copy BDT's full tSituation definition. If a field's type is itself complex (e.g. `simpleChecks`), define a local version of that nested type too, containing only the specific properties used. Never reference BDT's version of a nested type (e.g. `BDT.tSimpleChecks`) when a local minimal definition suffices.
9. **Output decision named `checkResult`** — the boolean output decision must always be named `checkResult`.
10. **Always import the category base module** — every check must import its category's base module DMN (e.g. `Residence.dmn`, `Age.dmn`), even if no types or BKMs from it are used in this check.
11. **DMNDI: inputs below, no unused imported shapes** — input nodes must always be placed below the decision service box (higher y). Never add `DMNShape` entries for BKMs or elements from imported modules unless they are explicitly called by a `dmn:knowledgeRequirement` in this check.
12. **Parameterless checks omit `parameters` entirely** — if the check has no caller-supplied parameters, remove `tParameters`, the `parameters` inputData element, its `dmn:informationRequirement`, its DMNDI shape and edge, and the `"parameters"` key from all Bruno test request bodies. An absent `parameters` input in the DMN type system is the correct way to express this; do not use an empty context or null type.
13. **simpleChecks Null.bru targets the specific field** — when the check reads `situation.simpleChecks.<fieldName>`, the Null test must set that specific boolean field to `null`, not the entire `simpleChecks` object to `null`. Setting the parent object to null tests a different (shallower) branch of the FEEL null guard than is most useful.
14. **DMN 1.6 only** — use the 20240513 model/FEEL namespaces, 20230324 DMNDI namespace, and KIE extensions 1.0 namespace from Step 3.
15. **KIE Tools 10.2 geometry** — ordinary nodes are at least 160×80, expanded services at least 280×280, and imported collapsed services exactly 300×100 with correct divider lines.
16. **Knowledge above, inputs below** — referenced BKMs and Decision Services belong beside/above the referencing decision; ordinary input data belongs below it.
