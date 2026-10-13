<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/d26a8869-970a-43b2-89b0-b4266754ae21">
  <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/62871a49-324f-4b3b-9ab6-b2513fbee842">
  <img alt="BDT logo, consists of three blue chevrons containing the letters BDT" src="https://github.com/user-attachments/assets/d26a8869-970a-43b2-89b0-b4266754ae21" width="400">
</picture>
<br>
<br>

**Use [DMN](https://www.omg.org/dmn/) and [FEEL](https://docs.camunda.io/docs/components/modeler/feel/what-is-feel/) to create APIs and Screeners for public benefit rules.**

## Motivation

Why hire a team of software engineers to codify rules that your benefit experts already know inside and out?

Why design a screening tool from scratch when your goal is simply to deploy a functionally accurate service as quickly as possible?

**_Benefit Decision Toolkit simplifies the management of eligibility rules and screeners so motivated subject matter experts can create useful tools with less hand-holding from traditional software teams._**

## Links

Try out an [example eligibility screener](https://phillypropertytaxrelief.org/) that was built with BDT, and directly interact with the [API of the screener](https://phillypropertytaxrelief.org/q/swagger-ui/). Read the [screener builder docs](https://bdt-docs.web.app/).

If you are interested in getting involved with the project, check out [our page on the Code For Philly website](https://codeforphilly.org/projects/dmn_benefit_toolbox-including_the_philly_property_tax_relief_screener)

## Testing Pull Requests

Want to test changes from a pull request without setting up a local development environment? See our [Codespaces Testing Guide](docs/src/content/docs/dev/testing-prs-with-codespaces.md) for step-by-step instructions.

## User-Facing Technologies

[Decision Model and Notation (DMN)](https://learn-dmn-in-15-minutes.com/) is used to define the logic of the screener forms.

[Form JS](https://bpmn.io/toolkit/form-js) is used to define the user interface of the screener forms.

## Developer-Facing Technologies

[Quarkus](https://quarkus.io/) is used to serve APIs.

[Solid JS](https://docs.solidjs.com/) is used to build the frontend of the web app.

## Navigating the Codebase

[builder-api](/builder-api) and [builder-frontend](/builder-frontend) comprise the web app used to build eligibility screeners, and the screener experience that the builder app deploys.

[library-api](/library-api) contains a library of pre-built benefits and eligibility rules, suitable for including in custom screeners and for standing up a standalone eligibility API.

## Development Setup

### In the Cloud (Github Codespaces)

This is the easiest way to get started with development if you like having a cloud-based development environment. Just click the badge:

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/CodeForPhilly/benefit-decision-toolkit)

Once you create a new codespace, the included Devcontainer will build and configure the project. This takes several minutes the first time, so be patient!

### On Your Local Machine

Clone this repository:

```bash
git clone https://github.com/CodeForPhilly/benefit-decision-toolkit.git
```

Go to the project's root directory:

```bash
cd benefit-decision-toolkit
```

From here, you have 3 options:

- Use [Devbox](https://www.jetify.com/docs/devbox/) (recommended),
- Use [Devcontainer in VS Code](https://code.visualstudio.com/docs/devcontainers/containers), or
- DIY (not recommended)

#### Option 1: Devbox (recommended)

We use [Jetify Devbox](https://www.jetify.com/docs/devbox/) to declare and manage project development dependencies. This makes it easy to setup the project and reduces _"works on my machine"_ issues. It uses the Nix Package Manager under the hood, which results in higher performance compared to container-based solutions.

To install devbox (and Nix) and configure your machine for development:

```bash
bin/install-devbox && devbox run setup
```

To install and build the dependencies without running the test suites, use:

```bash
devbox run setup -- --skip-tests
```

Then, to startup all the services/apps with the default configuration, run:

```bash
# uses process-compose under the hood
devbox services up
```

You can also run a shell in the context of Devbox with:

```bash
devbox shell
```

Or run a single command in the context of Devbox with:

```bash
# export data from the firebase emulators (they must be running!)
devbox run -- firebase emulators:export ./dir
```

Tips:

- Use the [Devbox direnv integration](https://www.jetify.com/docs/devbox/ide-configuration/direnv) to automatically start a Devbox shell whenever you navigate to the project directory.
- If you develop in VS Code, then consider installing the [Devbox and Direnv extensions](https://www.jetify.com/docs/devbox/ide-configuration/vscode) to automatically start `devbox shell` in VS Code Terminal and otherwise manage Devbox via the Command Palette.
- Edit the `.devboxrc` file (not in source control) to run custom commands every time you start a devbox environment. You can use this for things like disabling conflicting tools or adding project-specific shell aliases.

#### Option 2: Devcontainer in VS Code

This project comes with a devcontainer configuration that runs the Devbox/Nix environment inside the container itself. This is a good option for those used to a container-based workflow and don't mind the corresponding performance degradation as compared to native Devbox.

To use the provided devcontainer, first open VS Code and install the `Dev Containers` extension.

Then, use the command palette (Cmd+Shift+P or Ctrl+Shift+P) to run _"Dev Containers: Open Folder in Container..."_ and select this project. It will take several minutes to build the container the first time.

You should be prompted to install Docker if it isn't already installed.

Once the container builds, startup all the services/apps in the default configuration by opening a Terminal in VS Code and running:

```bash
devbox services up
```

Note: the devcontainer automatically uses the equivalent of `devbox shell` in VS Code Terminal, so there is no need to run it manually.

#### Option 3: DIY (not recommended)

It is recommended to use Devbox, Devcontainer, or Codespaces to develop with this project because each of those methods draw from a single source of truth to build the development environment (devbox.json).

If you insist on going your own way then you can install system dependencies (e.g. JDK, Node, Maven) manually (see devbox.json for the list) and run:

```bash
bin/setup
```

Then to startup all the services/apps in the default configuration, install [process-compose](https://f1bonacc1.github.io/process-compose/) and run:

```bash
process-compose
```

Or to run services manually (without `process-compose`):

```bash
# start the firebase emulators
firebase emulators:start --project demo-bdt-dev --only auth,firestore,storage
```

```bash
# then start up services one by one in new shells, e.g.:
source .rootenvrc
cd builder-api
quarkus dev
```
