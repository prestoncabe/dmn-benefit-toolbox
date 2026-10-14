import {
  CHECKLIST_WITH_NONE_TYPE,
  NONE_OF_THESE_VALUE,
} from '@/components/screenerEditor/formJsExtensions/customFormFields/ChecklistWithNone';
import { isUnsafeObjectKey } from '@/utils/unsafeObjectKeys';

interface FormComponent {
  type: string;
  key?: string;
  path?: string;
  components?: FormComponent[];
}

// Field types whose answers are arrays of selected options.
const ARRAY_FIELD_TYPES = ['checklist', 'taglist', CHECKLIST_WITH_NONE_TYPE];

/**
 * Convert array answers to eligibility input data. form-js uses [] for an
 * untouched field, so an empty answer is sent as null (unanswered). Only an
 * explicit "None of these" answer is sent as [].
 */
export function normalizeArrayFieldData<T extends object>(
  schema: { components?: FormComponent[] },
  data: T,
): T {
  const result = structuredClone(data);

  function normalizeAtPath(target: any, segments: string[]) {
    if (Array.isArray(target)) {
      target.forEach((item) => normalizeAtPath(item, segments));
      return;
    }
    if (target === null || typeof target !== 'object') return;

    const [segment, ...rest] = segments;
    if (isUnsafeObjectKey(segment)) return;
    if (rest.length > 0) {
      // A missing parent means the field's section wasn't submitted (e.g. it
      // is hidden). Creating it would add people or list items to the data.
      normalizeAtPath(target[segment], rest);
      return;
    }

    const value = target[segment];
    target[segment] = Array.isArray(value) && value.includes(NONE_OF_THESE_VALUE)
      ? []
      : Array.isArray(value) && value.length > 0 ? value : null;
  }

  function visit(components: FormComponent[] | undefined, parentPath = '') {
    for (const component of components ?? []) {
      const prefix = [parentPath, component.path].filter(Boolean).join('.');

      if (ARRAY_FIELD_TYPES.includes(component.type) && component.key) {
        const path = [prefix, component.key].filter(Boolean).join('.');
        normalizeAtPath(result, path.split('.'));
      }

      visit(component.components, prefix);
    }
  }

  visit(schema?.components);
  return result;
}
