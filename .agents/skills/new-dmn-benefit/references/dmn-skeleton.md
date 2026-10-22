# Benefit DMN skeleton

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
    xmlns:included1="{BENEFITS_NAMESPACE}"
    xmlns:included2="{CHECK_1_NAMESPACE}"
    ...
    xmlns:includedN="{BDT_NAMESPACE}"
    ...
    id="_{DEFINITIONS_UUID}"
    name="{BenefitName}"
    typeLanguage="https://www.omg.org/spec/DMN/20240513/FEEL/"
    namespace="https://kie.apache.org/dmn/_{MODEL_NAMESPACE_UUID}">
  <dmn:extensionElements/>

  <!-- Imports: Benefits.dmn first, then checks, then BDT, then category base modules -->
  <dmn:import id="_{UUID}" name="Benefits"
      namespace="https://kie.apache.org/dmn/_9514D95A-63FB-4345-911B-D83E1867F709"
      locationURI="{relative path to Benefits.dmn}"
      importType="https://www.omg.org/spec/DMN/20240513/MODEL/"/>
  <!-- One import per selected check -->
  <dmn:import id="_{UUID}" name="{CheckModelName}"
      namespace="{checkNamespace}"
      locationURI="{relative path to check DMN}"
      importType="https://www.omg.org/spec/DMN/20240513/MODEL/"/>
  <!-- BDT import -->
  <dmn:import id="_{UUID}" name="BDT"
      namespace="https://kie.apache.org/dmn/_1B91A885-130A-4E0B-A762-E12AA6DD5C79"
      locationURI="{relative path to BDT.dmn}"
      importType="https://www.omg.org/spec/DMN/20240513/MODEL/"/>
  <!-- Category base module imports (only if types are referenced) -->
  <dmn:import id="_{UUID}" name="{CategoryName}"
      namespace="{categoryNamespace}"
      locationURI="{relative path to category base DMN}"
      importType="https://www.omg.org/spec/DMN/20240513/MODEL/"/>

  <!-- Type definitions -->
  <dmn:itemDefinition id="_{UUID}" name="tSituation" isCollection="false">
    <!-- Union of fields from all selected checks' tSituation -->
  </dmn:itemDefinition>
  <!-- tSimpleChecks, tPerson, tPeople only if needed -->

  <!-- Decision Service -->
  ...
  <!-- checks decision with context entries -->
  ...
  <!-- isEligible decision -->
  ...
  <!-- Constant nextSteps decision; also include it in the service's outputDecision list -->
  <dmn:decision id="_{NEXTSTEPS_DECISION_UUID}" name="nextSteps">
    <dmn:extensionElements/>
    <dmn:variable id="_{NEXTSTEPS_VAR_UUID}" name="nextSteps" typeRef="BDT.tNextSteps"/>
    <dmn:literalExpression id="_{NEXTSTEPS_EXPRESSION_UUID}">
      <dmn:text>{
  eligible: {
    applyUrl: null,
    helpUrl: null,
    helpPhone: null,
    body: "Draft Markdown instructions using verified official program sources."
  },
  ineligible: {note: null}
}</dmn:text>
    </dmn:literalExpression>
  </dmn:decision>
  <!-- situation inputData -->
  ...
  <!-- DMNDI: three output shapes inside a service at least 660px wide;
       nextSteps has no information edges. Include ComponentWidths for its expression. -->
  ...
</dmn:definitions>
```
