// The demo CRM uses flat field names and monthly income. The Philadelphia
// screener expects nested people/custom/simpleChecks data and annual income.
export function clientToScreenerData(client) {
  const city =
    typeof client.home_city === "string" ? client.home_city.trim() : "";
  const birthDate =
    typeof client.birth_date === "string" ? client.birth_date.trim() : "";
  const monthlyIncome = client.monthly_income;
  const annualIncome =
    typeof monthlyIncome === "number" &&
    Number.isFinite(monthlyIncome) &&
    monthlyIncome >= 0
      ? Math.round(monthlyIncome * 12 * 100) / 100
      : null;

  return {
    people: {
      client: {
        dateOfBirth: /^\d{4}-\d{2}-\d{2}$/.test(birthDate) ? birthDate : null,
      },
    },
    custom: {
      householdIncome: Number.isFinite(annualIncome) ? annualIncome : null,
      wantsExtraCash:
        client.cash_support_requested === "requested"
          ? true
          : client.cash_support_requested === "declined"
            ? false
            : null,
    },
    simpleChecks: {
      livesInPhiladelphiaPa: city
        ? city.toLowerCase() === "philadelphia"
        : null,
    },
  };
}
