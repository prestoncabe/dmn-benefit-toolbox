# Documentation in DMN models

Keep rule documentation with the model that owns the rule. Use the DMN 1.6 and
KIE 1.0 namespaces from the calling skill.

## Ownership

- Benefit: concise model description of program, jurisdiction and screening
  scope; program links; composition, income basis and limitations on `checks`.
- Check: actual rule on `checkResult`, including boundaries, date choice,
  invalid/missing inputs, verification requirements and unresolved policy.
  Attach the sources supporting that rule to the implementing decision.
- Shared types/BKMs: meanings, units, identifiers, collection semantics and
  reusable algorithms in `BDT.dmn` or the owning category module. Describe local
  endpoint types as minimal projections; avoid copying shared policy narratives.
- Diagram: short annotations for caveats a reviewer should see, such as provider
  approval, alternative routes or an omitted requirement. Keep long prose in
  descriptions so diagrams remain readable.
- README: development commands and navigation, not a second copy of program rules.

Document the implementation, including gaps; do not describe a partial screen as
complete eligibility. Generic parameterized comparisons have technical contracts,
not invented program policies. Record policy review dates separately from source
publication/effective dates. Moving documentation does not refresh its review.
For a policy change, consult authoritative sources and update boundary/unknown
scenarios. Do not silently change rules during documentation edits.

## Descriptions and documentation links

Keep a concise `dmn:description` directly under `dmn:definitions`, before
extensions/imports; the library exposes it through discovery/OpenAPI. Add detail
on decisions, shared types/components, parameters, input data and BKMs. Follow
DMN child order: description precedes extensions, variables and type references.

KIE Tools 10.2 documentation links serialize as named attachments:

```xml
<dmn:decision id="_{DECISION_UUID}" name="checkResult">
  <dmn:description>Actual rule, boundaries, unknown behavior and policy period.</dmn:description>
  <dmn:extensionElements>
    <kie:attachment name="Agency policy — section/page and applicable period"
                    url="https://agency.example/policy.pdf" />
  </dmn:extensionElements>
  <!-- Existing variable, requirements and expression follow. -->
</dmn:decision>
```

Use direct authoritative URLs and meaningful titles; identify applicable
sections/pages and policy periods in the title or description. Preserve other
extensions and XML-escape URLs. Prefer controlling regulations/rates/plans when
overviews conflict, and explain unavailable or inconclusive sources. Root links
make program sources discoverable; decision/BKM links locate the implemented
rule. A URL alone does not explain its interpretation. No runtime web lookup is
needed, and these links do not themselves appear in the builder today.

## Text annotations

Use standard `dmn:textAnnotation` with `textFormat="text/plain"`. Associate a
node-specific caveat with that node via `dmn:association`,
`associationDirection="None"`, and `sourceRef`/`targetRef` hrefs. These artifacts
do not create executable dependencies.

Provide `dmndi:DMNShape` for the annotation and `dmndi:DMNEdge` for the
association. Generate fresh UUIDs, leave enough bounds for text, place notes
outside service boxes/other shapes, and calculate waypoints on node/annotation
borders. Decision/service minimum sizes and divider rules do not apply to notes.
Avoid repeating a shared rule note on every consuming benefit diagram.

## Verification

- Parse XML; validate KIE attachment namespaces, nonempty names/URLs, local
  association references, and DMNDI shapes/edges.
- Confirm documentation edits preserve FEEL, types, imports, service interfaces,
  existing IDs and executable requirements.
- Verify the KIE Tools editor marshaller retains descriptions, links and annotations.
  The default Java engine marshaller can skip unregistered extensions; do not use
  its serialized output to rewrite documented models. Inspect
  representative annotated diagrams in KIE Tools 10.2 when available; XML parsing
  alone does not establish visual/editor validation.
- Run checks appropriate to scope: model compilation/discovery for documentation
  migrations, plus business scenarios for changed rules and boundary behavior.

References: [KIE Tools 10.2 extension schema](https://github.com/apache/incubator-kie-tools/blob/10.2.0/packages/dmn-marshaller/src/schemas/kie-1_0/KIE.xsd),
[OMG DMN 1.6](https://www.omg.org/spec/DMN/1.6/).
