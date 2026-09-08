export const titleCase = (str: string) => {
  const words = str
    .replace(/[-_]+/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .trim();

  return words.length === 0
    ? words
    : words.charAt(0).toUpperCase() + words.slice(1);
}
