# Benefit next steps

Each benefit exposes a constant `nextSteps` decision of type `BDT.tNextSteps`
alongside `checks` and `isEligible` in its decision service:

```json
{
  "eligible": {
    "applyUrl": "https://tax-services.phila.gov/_/",
    "helpUrl": "https://www.phila.gov/",
    "helpPhone": "(215) 686-6442",
    "body": "Markdown application instructions"
  },
  "ineligible": {
    "note": "Optional Markdown note"
  }
}
```

String fields may be `null` when unavailable. This content describes how to apply
and get help; eligibility rules remain in the benefit's checks.

`nextSteps` has no information requirements. At startup, `ModelRegistry` evaluates
only this decision with an empty context and caches its result. It rejects
information requirements, evaluation errors, and non-context results. The content
is exported under each benefit operation's `x-bdt-benefit.nextSteps` in OpenAPI.
`bin/library/load-library-metadata.py` copies it into the benefit metadata artifact
alongside the benefit's checks. This lets the builder read instructions without
evaluating the benefit DMN. The benefit API returns the same content for eligible,
ineligible, and undecided results.

## Philadelphia content sources

The initial content was checked against these official pages on October 22, 2026.
Application links use the [Philadelphia Tax Center](https://tax-services.phila.gov/_/).
Program pages provide the instructions and paper application options; the
[Revenue contact directory](https://www.phila.gov/departments/department-of-revenue/about/contact-us/)
verifies the Real Estate Tax phone number and the Homestead/LOOP hotline.

| Benefit | Source |
| --- | --- |
| Homestead Exemption | [Program and application instructions](https://www.phila.gov/services/payments-assistance-taxes/taxes/property-and-real-estate-taxes/get-real-estate-tax-relief/get-the-homestead-exemption/) |
| LOOP | [Program and application instructions](https://www.phila.gov/services/payments-assistance-taxes/payment-plans-and-assistance-programs/income-based-programs-for-residents/apply-for-the-longtime-owner-occupants-program-loop/) |
| Low-income Tax Freeze | [Program and application instructions](https://www.phila.gov/services/payments-assistance-taxes/taxes/property-and-real-estate-taxes/get-real-estate-tax-relief/tax-freeze/apply-for-the-low-income-real-estate-tax-freeze/) |
| Owner-Occupied Payment Agreement | [Program and application instructions](https://www.phila.gov/services/payments-assistance-taxes/payment-plans-and-assistance-programs/income-based-programs-for-residents/set-up-an-owner-occupied-real-estate-tax-payment-agreement-oopa/) |
| Real Estate Tax Installment Plan | [Program and application instructions](https://www.phila.gov/services/payments-assistance-taxes/payment-plans-and-assistance-programs/income-based-programs-for-residents/set-up-real-estate-tax-installment-plan/) |
| Senior Citizen Tax Freeze | [Program and application instructions](https://www.phila.gov/services/payments-assistance-taxes/payment-plans-and-assistance-programs/income-based-programs-for-residents/apply-for-the-low-income-senior-citizen-real-estate-tax-freeze/) |

Revisit the official pages when updating content. Application periods, required
documents, and contact information can change. Automated tests validate the data
shape and export; they do not verify current program policy. External link checks
are kept outside the required test suite because municipal sites can rate-limit
or require browser cookies even when their links work.
