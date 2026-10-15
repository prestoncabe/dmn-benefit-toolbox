# Philadelphia example screener

This seed is built from scratch for new accounts. The three tax benefits use
check configurations derived from the library API 0.10.0 OpenAPI benefit
compositions. Each enrollment check binds `personId` to the form's `client` ID.
Philly Cash is fictional and has three checks: Philadelphia residence, wanting
extra cash, and a household income limit. Residence reuses the library check.
The two custom checks each include a working draft and published 1.0.0
versions, with a description, annotation, and diagram layout. Would like extra
cash compares a Yes/No answer with a boolean parameter. Household income limit
compares a number with an `incomeLimit` parameter, which Philly Cash sets to
40000; the user guide's custom check walkthrough uses it as its example. The
form binds the custom questions to `custom.wantsExtraCash` and
`custom.householdIncome`; the evaluator passes the contents of `custom` to the
DMN, whose inputs are named `wantsExtraCash` and `householdIncome`.

The form demonstrates boolean Yes/No fields, explicit None of these enrollment
answers, date fields, conditional spouse details, shared question keys, and
Markdown guidance. Descriptions explain aliases, parameters, version pinning,
editing, previewing, and publishing. Publishing remains a user action so each
new account can explore and edit its own working copy first.

When the library version changes, review the example's library checks and update
their `sourceCheckId` and `checkVersion` together. The example transfer E2E test
checks these references against the running library's version and endpoints.

Try these scenarios in Preview:

- Explore all four: residence Yes, wants cash Yes, income 30000, owner-occupant
  Yes, delinquency Yes, abatement No, enrollments None of these, applicant born
  over 65 years ago.
- Only cash passes: residence Yes, wants cash Yes, income 30000, owner-occupant No.
- Cash fails: residence No, wants cash No, or income above 40000.
- Cash is unknown: residence Yes, extra-cash or income answer blank.
- Spouse age: applicant under 65, spouse Yes with birth date over 65 years ago.
- Existing enrollment: select one program to fail only its enrollment check.

Selecting None of these sends an empty enrollment list, which the library
treats as not enrolled in any program, so each enrollment check passes. Leaving
the question blank keeps enrollment unknown. Selecting an existing program
makes that program fail and allows the other enrollment checks to pass.

Run `mvn test -Dtest=ExampleScreenerImportServiceTest,ExampleScreenerSeedTest`
in builder-api to check account import, resource integrity, and custom DMN
validation. See the editing-example-screener documentation for UI
editing. The supported export is the app's Export Example Screener action,
which writes this bundled manifest format through the builder API.
