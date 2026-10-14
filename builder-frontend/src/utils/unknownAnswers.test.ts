import { describe, expect, it } from 'vitest';

import {
  findFilledUnknownPaths,
  keepUnansweredPaths,
  restoreUnknownAnswers,
} from './unknownAnswers';

describe('unknown prefill answers', () => {
  const prefill = {
    custom: { wantsExtraCash: null, householdIncome: 0 },
    simpleChecks: { livesInPhiladelphiaPa: false },
    people: [{ name: 'Alex' }],
  };
  // What form-js holds after importing the prefill.
  const formData = {
    custom: { wantsExtraCash: false, householdIncome: 0, city: '' },
    simpleChecks: { livesInPhiladelphiaPa: false },
    people: [{ name: 'Alex', note: '' }],
  };

  it('finds empty values form-js filled in for null or omitted answers', () => {
    expect(findFilledUnknownPaths(prefill, formData)).toEqual([
      ['custom', 'wantsExtraCash'],
      ['custom', 'city'],
      ['people', '0', 'note'],
    ]);
  });

  it('sends unanswered unknowns as null without mutating form data', () => {
    const paths = findFilledUnknownPaths(prefill, formData);
    expect(restoreUnknownAnswers(formData, paths)).toEqual({
      custom: { wantsExtraCash: null, householdIncome: 0, city: null },
      simpleChecks: { livesInPhiladelphiaPa: false },
      people: [{ name: 'Alex', note: null }],
    });
    expect(formData.custom.wantsExtraCash).toBe(false);
  });

  it('keeps an answer once the user changes it, even back to the empty value', () => {
    let paths = findFilledUnknownPaths(prefill, formData);
    const checked = structuredClone(formData);
    checked.custom.wantsExtraCash = true;
    paths = keepUnansweredPaths(paths, checked);
    checked.custom.wantsExtraCash = false;
    paths = keepUnansweredPaths(paths, checked);
    expect(restoreUnknownAnswers(checked, paths).custom).toEqual({
      wantsExtraCash: false,
      householdIncome: 0,
      city: null,
    });
  });

  it('does not add paths that are missing from the data', () => {
    expect(restoreUnknownAnswers({}, [['custom', 'city']])).toEqual({});
  });
});
