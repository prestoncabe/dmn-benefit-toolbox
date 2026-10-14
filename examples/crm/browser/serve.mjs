import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

// Serve only the example and the canonical adapter; don't expose the repository.
const assets = new Map([
  ["/", [new URL("./demo.html", import.meta.url), "text/html"]],
  ["/demo.html", [new URL("./demo.html", import.meta.url), "text/html"]],
  ["/demo.js", [new URL("./demo.js", import.meta.url), "text/javascript"]],
  [
    "/clientMapping.mjs",
    [new URL("./clientMapping.mjs", import.meta.url), "text/javascript"],
  ],
  [
    "/host.js",
    [
      new URL(
        "../../../builder-frontend/public/integrations/host.js",
        import.meta.url,
      ),
      "text/javascript",
    ],
  ],
]);

// PORT=0 picks a free port; tests read the printed URL.
const port = Number(process.env.PORT ?? 4174);
const server = createServer(async (request, response) => {
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  const asset = assets.get(new URL(request.url, "http://localhost").pathname);
  if (!asset) {
    response.writeHead(404).end();
    return;
  }
  try {
    const content = await readFile(asset[0]);
    response.writeHead(200, {
      "Content-Type": `${asset[1]}; charset=utf-8`,
      "Cache-Control": "no-store",
    });
    response.end(request.method === "HEAD" ? undefined : content);
  } catch (error) {
    console.error("Could not serve CRM example asset", error);
    response.writeHead(500).end();
  }
});
server.listen(port, "127.0.0.1", () => {
  console.log(
    `CRM browser example: http://127.0.0.1:${server.address().port}/demo.html`,
  );
});
