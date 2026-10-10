import assert from "node:assert/strict";
import test from "node:test";
import { clientToScreenerData } from "../clientMapping.mjs";

test("flat CRM fields map into three screener branches and annual income", () => {
  assert.deepEqual(
    clientToScreenerData({
      id: "client-123",
      full_name: "Synthetic client",
      case_notes: "Keep this in the CRM",
      birth_date: "1960-02-03",
      home_city: " Philadelphia ",
      monthly_income: 2750.25,
      cash_support_requested: "requested",
    }),
    {
      people: { client: { dateOfBirth: "1960-02-03" } },
      custom: { householdIncome: 33003, wantsExtraCash: true },
      simpleChecks: { livesInPhiladelphiaPa: true },
    },
  );
});

test("missing and unanswered CRM fields stay unknown", () => {
  const expected = {
    people: { client: { dateOfBirth: null } },
    custom: { householdIncome: null, wantsExtraCash: null },
    simpleChecks: { livesInPhiladelphiaPa: null },
  };
  assert.deepEqual(clientToScreenerData({}), expected);
  assert.deepEqual(
    clientToScreenerData({
      birth_date: "",
      home_city: " ",
      monthly_income: null,
      cash_support_requested: "unknown",
    }),
    expected,
  );
});

test("zero income and explicit negative answers are preserved", () => {
  const data = clientToScreenerData({
    home_city: "Pittsburgh",
    monthly_income: 0,
    cash_support_requested: "declined",
  });
  assert.equal(data.custom.householdIncome, 0);
  assert.equal(data.custom.wantsExtraCash, false);
  assert.equal(data.simpleChecks.livesInPhiladelphiaPa, false);
});

test("income conversion rounds to cents and rejects unusable amounts", () => {
  assert.equal(
    clientToScreenerData({ monthly_income: 3333.33 }).custom.householdIncome,
    39999.96,
  );
  for (const monthly_income of ["2500", -1, NaN, Infinity, Number.MAX_VALUE]) {
    assert.equal(
      clientToScreenerData({ monthly_income }).custom.householdIncome,
      null,
    );
  }
});
