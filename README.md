# DMN Benefit Toolbox

**Use [DMN](https://www.omg.org/dmn/) and [FEEL](https://docs.camunda.io/docs/components/modeler/feel/what-is-feel/) to create APIs and Screeners for public benefit rules.**

## Motivation

Why spend hours troubleshooting low-level logic that is barely related to the benefit rules you are trying to model?

Why build benefit models and screeners from scratch when there are so many concepts that are shared between implementations?

***DMN Benefit Toolbox simplifies the management of rules and screeners for subject matter experts.***

## Example

As a proof of concept, we've built an API and a screener for several of the [tax relief benefits available in Philadelphia](https://www.phila.gov/services/payments-assistance-taxes/taxes/property-and-real-estate-taxes/get-real-estate-tax-relief/).

You can interact with the screener yourself at: https://phillypropertytaxrelief.org.

## Get Started

The easiest way to get started using DMN Benefit Toolbox is to open it in Project IDX:

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

[Project IDX](https://idx.dev/#introduction) is a web-based development environment created by Google. When you open DMN Benefit Toolbox in IDX, a development machine will be created and configured for you in the cloud.

(Of course, you can manually setup your laptop/desktop to work on DMN Benefit Toolbox locally, but it isn't recommended unless you really know what you're doing.)

## How it Works

We use a combination of open-source tools ([Kogito](https://kogito.kie.org/) and [form-js](https://bpmn.io/toolkit/form-js/)) with some scaffolding to tie them together and make them easier to use.

We also make use of pre-built rules and patterns (created in DMN) that can be composed into a full set of logic modeling benefit eligibility.

(more coming soon...)

## License
See the [LICENSE](https://github.com/prestoncabe/dmn-benefit-toolbox/blob/main/LICENSE.md) file for license rights and limitations (MIT).
