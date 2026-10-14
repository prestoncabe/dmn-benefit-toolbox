// Property names that can reach an object's prototype when copied or assigned.
const UNSAFE_OBJECT_KEYS = ['__proto__', 'prototype', 'constructor'];

export const isUnsafeObjectKey = (key: string) =>
  UNSAFE_OBJECT_KEYS.includes(key);
