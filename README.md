# DMN Benefit Toolbox

**Use [DMN](https://www.omg.org/dmn/) and [FEEL](https://docs.camunda.io/docs/components/modeler/feel/what-is-feel/) to create APIs and Screeners for public benefit rules.**

## Motivation

Why spend hours troubleshooting low-level logic that is barely related to the benefit rules you are trying to model?

Why build benefit models and screeners from scratch when there are so many concepts that are shared between implementations?

***DMN Benefit Toolbox simplifies the management of rules and screeners for subject matter experts.***

## Example

As a proof of concept, we've built an API and a screener for several of the [tax relief benefits available in Philadelphia](https://www.phila.gov/services/payments-assistance-taxes/taxes/property-and-real-estate-taxes/get-real-estate-tax-relief/).

You can interact with the screener yourself at: https://phillypropertytaxrelief.org.

## Setup a Development Environment

The easiest way to get a feel for DMN Benefit Toolbox is to open it in Project IDX:

<a href="https://idx.google.com/import?url=https%3A%2F%2Fgithub.com%2Fprestoncabe%2Fdmn-benefit-toolbox">
  <picture>
    <source
      media="(prefers-color-scheme: dark)"
      srcset="https://cdn.idx.dev/btn/open_dark_32.svg">
    <source
      media="(prefers-color-scheme: light)"
      srcset="https://cdn.idx.dev/btn/open_light_32.svg">
    <img
      height="32"
      alt="Open in IDX"
      src="https://cdn.idx.dev/btn/open_purple_32.svg">
  </picture>
</a>

[Project IDX](https://idx.dev/#introduction) is a web-based development environment created by Google. When you open DMN Benefit Toolbox in IDX, a development machine will be created and configured for you in the cloud. (This will take a few minutes).

The second easiest way to get started is to use the included Devbox configuration:

[![Built with Devbox](https://www.jetify.com/img/devbox/shield_galaxy.svg)](https://www.jetify.com/devbox/docs/contributor-quickstart/)

Of course, you can also manually setup a laptop/desktop to work on DMN Benefit Toolbox, but it isn't recommended unless you really know what you're doing.

If you're planning to develop forms (the basis of eligibility screeners) in addition to eligibility rules, then you'll also need to download [Camunda Modeler](https://camunda.com/download/modeler/).

## How it Works

We use a combination of open-source tools ([Kogito](https://kogito.kie.org/) and [form-js](https://bpmn.io/toolkit/form-js/)) with some scaffolding to tie them together and make them easier to use.

Here are some high-level things to orient you...

### DMN Files

DMN files (`.dmn`) can be edited directly in VS Code via the [DMN Editor extension](https://marketplace.visualstudio.com/items?itemName=kie-group.dmn-vscode-extension). If you're using Project IDX as described above, then this extension is already installed for you. If you're using another dev environment, then you'll need to install the extension manually.

You may occasionally need to interact with the raw XML text of the DMN; this can be done via the "Reopen with Text Editor" feature of VS Code.

A good orientation on the basics of DMN can be found [here](https://learn-dmn-in-15-minutes.com/).

In this project, DMN (and its accompanying expression language FEEL) acts as the "source code" for a JSON web API. Kogito generates this API (with Java) when you run the Quarkus development server (automatic with Project IDX, or by running the `bin/dev` script).

### Form Files

Form files (`.form`) can be edited using [Camunda Modeler](https://camunda.com/download/modeler/). The modeler provides a UI for designing the form's layout and logic (which often incorporates FEEL expressions).

Behind the scenes,the form is saved in JSON format.

When it comes time to use the form in a screener, form-js interprets this JSON and uses it to display the form on a web page.

### Eligibility Screeners

To create the [Philadelphia Property Tax Relief Screener](https://phillypropertytaxrelief.org), we've written a [Qte](https://quarkus.io/guides/qute) which displays the form, posts data as it is collected to the eligibility API (the one that is built from the DMN), and receives back eligibility results for display on the form.

For future screeners, we envision packaging up this functionality somehow, allowing the entire screening and results process to be included as part of other websites and tools, the content of which is outside the scope of this project.

## License
See the [LICENSE](https://github.com/prestoncabe/dmn-benefit-toolbox/blob/main/LICENSE.md) file for license rights and limitations (MIT).
