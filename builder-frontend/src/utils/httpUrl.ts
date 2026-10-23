// The server checks links with java.net.URI, which is stricter than the browser's URL parser.
// Send the browser's normalized URL with anything URI rejects percent-encoded, or null if the
// link is not one the server accepts.
const encodeForUri = (part: string) =>
  part
    .replace(/%(?![0-9A-Fa-f]{2})/g, "%25")
    .replace(/[^A-Za-z0-9\-._~:/?@!$&'()*+,;=%]/g, encodeURIComponent);

export function normalizeHttpUrl(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (
    !["https:", "http:"].includes(url.protocol) ||
    !url.hostname ||
    url.hostname.includes("_") ||
    url.username ||
    url.password
  )
    return null;
  return (
    url.origin +
    encodeForUri(url.pathname + url.search) +
    (url.hash && "#" + encodeForUri(url.hash.slice(1)))
  );
}
