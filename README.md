<p align="center">
  <a href="https://www.axoniq.io/products/axon-framework">
    <img src="https://raw.githubusercontent.com/AxonFramework/.github/main/images/AxonFrameworkLogo-2025.png" alt="Axon Framework logo" width="500"/>
  </a>
</p>

<p align="center">
  Build modern event-driven systems with AxonIQ technology.
  <br>
  <a href="https://www.axoniq.io/products/axon-framework"><strong>Product Description »</strong></a>
  <br>
  <br>
  <a href="https://github.com/AxonIQ/code-samples">Code Samples Repo</a>
  ·
  <a href="https://developer.axoniq.io/axon-framework/overview">Technical Overview</a>
  ·
  <a href="https://github.com/AxonFramework/AxonFramework/issues">Feature / Bug Request</a>


</p>

> [!NOTE]
> This repository has moved from the [Axon Framework GitHub organization](https://github.com/AxonFramework/) to the [AxonIQ GitHub organization](https://github.com/AxonIQ) to streamline project management.
> All existing links, clones, forks, stars, and bookmarks will continue to work seamlessly thanks to GitHub's automatic redirects.
> For more details, read [here](#repository-transferred) 

# Axon Framework

[![Maven Central](https://img.shields.io/maven-central/v/org.axonframework/axon-framework-bom)](https://central.sonatype.com/artifact/org.axonframework/axon-framework-bom)
[![Build Status](https://github.com/AxonFramework/AxonFramework/actions/workflows/main.yml/badge.svg)](https://github.com/AxonFramework/AxonFramework/actions/workflows/main.yml)
[![SonarCloud Status](https://sonarcloud.io/api/project_badges/measure?project=AxonFramework_AxonFramework&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=AxonFramework_AxonFramework)

Axon Framework is a framework for building evolutionary, event-driven microservice systems based on the principles of Domain-Driven Design (DDD), Command-Query Responsibility Separation (CQRS), and Event Sourcing.

Axon Framework provides you with the necessary building blocks to follow these principles.
Examples of building blocks are aggregate design handles, aggregate repositories, command buses, saga design handles, event stores, query buses, and more.
The framework provides sensible defaults for all of these components out of the box.

The messaging support for commands, events, and queries is at the core of these building blocks. 
It is the messaging basics that enable an evolutionary approach towards microservices through the [location transparency](https://en.wikipedia.org/wiki/Location_transparency) they provide.

Axon will also assist in distributing applications to support scalability or fault tolerance, for example.
The most accessible and quick road forward would be to use [Axon Server](https://developer.axoniq.io/axon-server/overview) to seamlessly adjust message buses to distributed implementations.
Axon Server provides a distributed command bus, event bus, query bus, and an efficient event store implementation for scalable event sourcing.
Additionally, the [Axon Framework organization](https://github.com/AxonFramework) has several extensions that can help in this space.

All this helps to create a well-structured application without worrying about the infrastructure.
Hence, your focus can shift from non-functional requirements to your business functionality.

For more information on anything Axon, please visit our website, [http://axoniq.io](http://axoniq.io).

## Getting started

Numerous resources can help you on your journey in using Axon Framework.
A good starting point is [AxonIQ Developer Portal](https://developer.axoniq.io/), which provides links to resources like blogs, videos, and descriptions.

Furthermore, below are several other helpful resources:
* The [quickstart page](https://docs.axoniq.io/reference-guide/getting-started/quick-start) of the documentation provides a simplified entry point into the framework with the [quickstart project](https://download.axoniq.io/quickstart/AxonQuickStart.zip).
* We have our very own [academy](https://academy.axoniq.io/)! 
  The introductory courses are free, followed by more in-depth (paid) courses.
* The [reference guide](https://docs.axoniq.io) explains all of the components maintained within Axon Framework's products.
* If the guide doesn't help, our [forum](https://discuss.axoniq.io/) provides a place to ask questions you have during development.
* The [hotel demo](https://github.com/AxonIQ/hotel-demo) shows a fleshed-out example of using Axon Framework.
* The [code samples repository](https://github.com/AxonIQ/code-samples) contains more in-depth samples you can benefit from.
* You can [Ask Axon Guru](https://gurubase.io/g/axon-framework), it is an Axon-focused AI to answer your questions.

## Receiving help

Are you having trouble using any of our libraries or products?
Know that we want to help you out the best we can!
There are a couple of things to consider when you're traversing anything Axon:

* Checking the [reference guide](https://docs.axoniq.io) should be your first stop.
* When the reference guide does not cover your predicament, we would greatly appreciate it if you could file an [issue](https://github.com/AxonIQ/reference-guide/issues) for it.
* Our [forum](https://discuss.axoniq.io/) provides a space to communicate with the Axon community to help you out. 
  AxonIQ developers will help you out on a best-effort basis. 
  And if you know how to help someone else, we greatly appreciate your contributions!
* We also monitor Stack Overflow for any question tagged with [**axon**](https://stackoverflow.com/questions/tagged/axon). 
  Similarly to the forum, AxonIQ developers help out on a best-effort basis.

## Feature requests and issue reporting

We use GitHub's [issue tracking system](https://github.com/AxonFramework/AxonFramework/issues)) for new feature requests, framework enhancements, and bugs.
Before filing an issue, please verify that it's not already reported by someone else. 
Furthermore, make sure you are adding the issue to the correct repository!

When filing bugs:
* A description of your setup and what's happening helps us figure out what the issue might be.
* Do not forget to provide the versions of the Axon products you're using, as well as the language and version.
* If possible, share a stack trace. 
  Please use Markdown semantics by starting and ending the trace with three backticks (```).

When filing a feature or enhancement:
* Please provide a description of the feature or enhancement at hand. 
  Adding why you think this would be beneficial is also a great help to us.
* (Pseudo-)Code snippets showing what it might look like will help us understand your suggestion better.
  Similarly as with bugs, please use Markdown semantics for code snippets, starting and ending with three backticks (```).
* If you have any thoughts on where to plug this into the framework, that would be very helpful too.
* Lastly, we value contributions to the framework highly. 
  So please provide a Pull Request as well!

## Update Checker and Anonymous Usage Data Collection

The update checker is a new feature included in the upcoming Axon Framework 5, which ensures the security of the Axon
Framework application and its modules and provides useful information to its maintainers.

It does so by retrieving available updates and known vulnerabilities for the Axon modules in use. Furthermore, to
detect updates and vulnerabilities, the checker collects anonymous data about your Axon Framework installation. This
data is sent to AxonIQ and includes technical information about your environment.

Please read [this page](https://docs.axoniq.io/axon-framework-update-checker/) of our documentation for more details on
why we collect this information, what you get in return, how to opt out, and why this matters. Please check out
our [Privacy Policy](https://www.axoniq.io/privacy-policy) for any privacy concerns.

<img referrerpolicy="no-referrer-when-downgrade" src="https://static.scarf.sh/a.png?x-pxid=31ffe27e-667c-48ff-8a14-8029d44dfb66" />

## Repository Transferred

The Axon Framework team has decided to move the Axon Framework repository from the [Axon Framework GitHub organization](https://github.com/AxonFramework/) to the [Axoniq GitHub organization](https://github.com/AxonIQ).
This switch makes project management easier for the team, due to our active use of GitHub Project boards.

GitHub Project boards do not allow merging repositories between different organizations in a single board.
The team is actively constructing several open-core licensed modules for Axon Framework.
These are best placed under the AxonIQ GitHub organization due to their licensing.

Users of the Axon Framework repository will not experience any disruptions from this transfer.
GitHub automatically redirects all links, clones, and references from the old location to the new one.
Your existing forks, stars, and bookmarks will continue to work seamlessly, regardless of the transfer.

However, it is recommended to update existing local clones to the new repository URL.
You can do this by using `git remote` on the command line:

```shell
git remote set-url origin https://github.com/AxonIQ/AxonFramework.git
```