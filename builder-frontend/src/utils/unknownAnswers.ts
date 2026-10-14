type Path = string[];

// form-js's empty values for checkboxes (false) and text fields ('').
const isFilledEmptyValue = (value: unknown) => value === false || value === '';

const valueAt = (data: unknown, path: Path) =>
  path.reduce<any>(
    (value, segment) => (value == null ? undefined : value[segment]),
    data,
  );

/**
 * form-js replaces null or omitted prefill answers with its empty values, so an
 * unknown CRM answer would be evaluated as "No" or blank. Find those paths.
 */
export function findFilledUnknownPaths(
  prefill: unknown,
  formData: unknown,
  path: Path = [],
): Path[] {
  if (isFilledEmptyValue(formData)) {
    return valueAt(prefill, path) == null ? [path] : [];
  }
  if (formData === null || typeof formData !== 'object') return [];
  return Object.entries(formData).flatMap(([key, value]) =>
    findFilledUnknownPaths(prefill, value, [...path, key]),
  );
}

/** Drop paths the user has answered; an answer stays answered once changed. */
export function keepUnansweredPaths(paths: Path[], formData: unknown): Path[] {
  return paths.filter((path) => isFilledEmptyValue(valueAt(formData, path)));
}

/** Send still-unanswered unknown answers as null instead of form-js's empty values. */
export function restoreUnknownAnswers<T>(data: T, paths: Path[]): T {
  const result = structuredClone(data);
  for (const path of paths) {
    const parent = valueAt(result, path.slice(0, -1));
    const key = path[path.length - 1];
    if (
      parent &&
      typeof parent === 'object' &&
      isFilledEmptyValue(parent[key])
    ) {
      parent[key] = null;
    }
  }
  return result;
}
