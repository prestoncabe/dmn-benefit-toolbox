## Usage

```bash
$ npm install # or pnpm install or yarn install
```

### Learn more on the [Solid Website](https://solidjs.com) and come chat with us on our [Discord](https://discord.com/invite/solidjs)

## Available Scripts

In the project directory you can run:

### `npm run dev`

Runs the app in the development mode.<br>
Open [http://localhost:5173](http://localhost:5173) to view it in the browser.

### `npm run build`

Builds the app for production to the `dist` folder.<br>
It correctly bundles Solid in production mode and optimizes the build for the best performance.

The build is minified and the filenames include the hashes.<br>
Your app is ready to be deployed!

## Deployment

Learn more about deploying your application with the [documentations](https://vite.dev/guide/static-deploy.html)

## Screening results in custom form fields

Both live form renderers and the editor register `screeningResultsModule` from
`src/components/screenerEditor/formJsExtensions/screeningResults/screeningResults.ts`.
Each form has its own service. Preact custom fields can read its reactive snapshot
with `useScreeningResults()` from the adjacent `useScreeningResults.ts`:

```ts
const { results, pending, failed, mode } = useScreeningResults();
```

`results` is the latest `ScreenerResult`, or `undefined` before evaluation.
`pending` stays true from an answer edit until results for those latest answers
arrive, including the debounce interval and failed requests. `failed` is true when
the latest evaluation failed, until one succeeds, so a field can show an error
instead of waiting indefinitely. Previous results remain available while pending.
Snapshots and their results are frozen copies; copy them before sorting or annotating.
The parents handle stale responses before updating the service.

`mode` is `live` for published screeners and preview, and `editor` for sample
results in the editing canvas. Editor samples use the configured benefit IDs and
names, plus clearly named examples for any eligible, ineligible, or undecided
outcome those benefits don't cover. Sample data and
live results are kept outside form answers and saved schemas.

The hook uses the viewer's `FormContext`, shared by both field renderers. The
`useService` exported by form-js 1.15 uses an editor-only context and cannot serve
viewer fields. Consumers outside Preact can use `getSnapshot()` and `subscribe()`
on the service; `subscribe()` returns an unsubscribe function and updates fire
`screeningResults.changed` on the form's event bus.

## Next steps component

Analysts can place **Next steps** from the presentation palette. Its `next_steps`
schema field stores `benefitContent`, a map from benefit ID to the `NextSteps`
shape, and `showWhen`: `whenDecided` (default) or `whenComplete`. Content is copied
when the field is inserted. The properties panel opens a modal to edit the copy
or reset an individual benefit from its source. New benefits need content added
to existing components; removed benefits are ignored when displaying results.

Editor startup loads the benefit sources before enabling insertion. Failed
loads show a retry action and preserve saved component content. Content edits
change the form draft and require saving the form. Publish snapshots that schema;
viewers do not fetch the working benefit content.
Publishing keeps benefit IDs stable so those results match the copied content.
Screener imports remap component copies to the newly allocated benefit IDs.

The component renders sanitized Markdown without template evaluation. Web links
accept HTTP(S) URLs and open in a new tab. Pending or failed evaluations hide old
instructions until fresh decisions arrive. Forms containing the component use
its progress heading; existing forms without it keep the legacy completion message.
