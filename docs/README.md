# Documentation For Axon Framework

This folder contains the docs related to the Axon Framework project. The docs in this folder are written as part of
the [AxonIQ Docs](https://docs.axoniq.io), and are written in AsciiDoc and built with Antora.

This repository holds the sources for the
following [Antora Components](https://docs.antora.org/antora/latest/component-name-and-version/) in the corresponding
folders:

- [
  `./getting-started/`](getting-started): [A tutorial covering Axon Framework 5's fundamental components and features (dcb-focused).](https://docs.axoniq.io/axon-framework-5-getting-started/)
- [
  `./identifier-generation-guide/`](identifier-generation-guide) : [Guide that covers several considerations with regard to identifier generation in Axon Framework-based applications.](https://docs.axoniq.io/identifier-generation-guide/latest)
- [
  `./message-handler-tunning-guide/`](message-handler-customization-guide) : [Guide that covers the message handler tuning in your Axon Framework applications.](https://docs.axoniq.io/message-handler-customization-guide/latest)
- [
  `./meta-annotations-guide/`](meta-annotations-guide) : [Guide that covers several considerations with regard to creating Meta Annotations for Axon Framework-based applications.](https://docs.axoniq.io/meta-annotations-guide/latest)
- [
  `./reference-guide/`](reference-guide) : [The Axon Framework former reference guide migrated from former docs.axoniq.io](https://docs.axoniq.io/axon-framework-reference/latest)


## Contributing to the docs

You are welcome to contribute to these docs. Whether you want to fix a typo, or you find something missing, something
that it's not clear or can be improved, or even if you want to write an entire piece of docs to illustrate something
that could help others to understand the use of Axon Framework, you are more than welcome to send a Pull Request to this
github repository.

## Building and previewing these docs locally

If you want to build and explore the docs locally (because you have made changes or before contributing), you can use
the Antora build file in the [`./_playbook`](_playbook) folder following these steps:

### Install dependencies

1. Make sure you have [Node](https://nodejs.org/en/download) (a LTS version is preferred)
   and [Vale](https://vale.sh/docs/install) installed in your system.
2. CD to the [`./_playbook`](_playbook) folder.
3. Run `npm install.` to install Antora

### Build and preview

1. Run `node watch.js`. Antora will generate the set of static html files under [
   `./_playbook/build/site`](_playbook/build/site), which will be served using [Express.js}(https://expressjs.com/) via http://localhost:3000
2. Each of the existing Antora Components will be available under a different folder in the webserver's root (named
   after the component name in the corresponding `antora.yml`).
3. Antora determines the component
   version [based on the git refname](https://docs.antora.org/antora/latest/component-version-key/#refname) according to
   the configuration in the `antory.yml`. The rendered pages are available inside a subdirectory of the component's
   version directory, named after the component's version (depending on the git branch you have checked out).
4. rebuilds will trigger automatically when changing documentation files
