# CLAUDE.md

This file provides guidance to AI Agents (Claude Code, Codex, Gemini, Cursor etc.) when working with code in this repository.

## Project Overview

This directory contains the **Axon Framework 5 Getting Started Guide**, a comprehensive tutorial for building applications with Axon Framework 5. This documentation is built using Antora for Axon Framework 5.

### Documentation Structure

This is an **Antora documentation component** with the following structure:
- `antora.yml` - Component configuration defining title, version, and navigation
- `modules/ROOT/` - Main content module containing:
  - `nav.adoc` - Navigation structure
  - `pages/` - Individual tutorial pages in AsciiDoc format

### Key Tutorial Content

**Tutorial Focus**: Building an "Axon University Registration Application" using:
- Vertical Slice Architecture 
- Test-First approach
- Dynamic Consistency Boundary (DCB) patterns
- Axon Server 2025 for event persistence

**Major Axon Framework 5 Features Covered**:
- Dynamic Consistency Boundary (DCB) - Revolutionary event sourcing patterns
- Revisited Configuration - New flexible configuration model  
- Improved Testing Support - Enhanced testing fixtures
- Java 21 Baseline - Latest Java features
- Async Native Architecture - Composable async functions, no ThreadLocals

### Tutorial Pages

The guide includes these main sections:
- `index.adoc` - Introduction and overview of AF5 features
- `project-structure.adoc` - Setting up the project structure
- `configure-axon-server.adoc` - Axon Server configuration
- `sample-domain-event-modeling.adoc` - Domain modeling with events
- `implement-command-*.adoc` - Command implementation examples
- `implement-stateless-event-handler.adoc` - Event handler patterns

## Documentation Standards

### AsciiDoc Conventions
- Use `.adoc` extension for all content files
- Include navigation metadata: `:navtitle:` and `:reftext:`
- Use proper AsciiDoc syntax for headings, links, code blocks
- External links should include `role=external,window=_blank`

### Content Guidelines
1. **Version Awareness**: Reference specific version (5.0.0) and milestone nature
2. **Migration Focus**: Address differences from Axon Framework 4
3. **Practical Examples**: Include working code samples and GitHub repository links
4. **Feedback Orientation**: Encourage user feedback for milestone version

### Antora Integration
- Component name: `axon-framework-5-getting-started`
- Title: `Getting Started with Axon Framework 5`
- Version: `master`
- Type: `tutorial` (beginner group)
- Navigation defined in `modules/ROOT/nav.adoc`

## Development Workflow

### Building Documentation
- This documentation is part of a larger Antora site
- Build process handled by parent documentation system
- Preview changes through Antora build pipeline

### Content Updates
- Update individual `.adoc` files in `modules/ROOT/pages/`
- Maintain navigation structure in `nav.adoc`
- Ensure cross-references and links remain valid
- Test tutorial steps with actual Axon Framework 5.0.0

### Repository References
- Main tutorial GitHub repo: https://github.com/AxonIQ/AxonFramework/tree/main/examples/university-demo
- Feedback collection: https://discuss.axoniq.io/t/feedback-template/6034

## Writing Guidelines

### Tutorial Content
1. **Progressive Complexity**: Build from simple concepts to advanced patterns
2. **Hands-on Approach**: Include executable code examples
3. **Framework Integration**: Show real-world configuration and usage
4. **Testing Emphasis**: Demonstrate testing patterns throughout
5. **Business Context First**: Always explain the business purpose before diving into technical implementation
6. **Concise Explanations**: Keep business context brief and focused - avoid lengthy descriptions
7. **Step-by-Step Structure**: Use numbered steps with clear, actionable instructions

### Content Structure Pattern
Each implementation guide should follow this pattern:
1. **Event Modeling Diagram**: Start with the Event Modeling visual representation when available
2. **Business Context**: Brief explanation of why this feature matters (2-3 short paragraphs max)
3. **Infrastructure Setup**: Supporting services and configurations needed
4. **Step-by-Step Implementation**: Numbered steps with code examples
5. **Configuration**: How to wire everything together
6. **Testing**: Complete test implementation with async patterns
7. **Integration**: How to register in main application
8. **Summary**: Key takeaways and applicable patterns

### Code Examples
- Use realistic domain examples (University Registration)
- Show both declarative and programmatic configuration
- Include proper imports and package structure
- Demonstrate Axon Framework 5 specific features (DCB, async patterns)
- **Always use actual code from university-demo repository** - don't create fictional examples
- Include numbered callouts (`<1>`, `<2>`, etc.) to explain key parts of code
- **Use simplified implementations for tutorial purposes** with notes about production alternatives

### Event Modeling First Approach
- **Start with diagrams**: Use Event Modeling diagrams when available to introduce features visually
- **Explain diagram elements**: Describe what each sticky note represents (events, automations, external systems, TODO lists)
- **Connect to implementation**: Show how Event Modeling concepts map to Axon Framework components
- **Framework benefits**: Explain what Axon Framework provides automatically (e.g., tracking tokens vs manual TODO lists)

### Business Context Guidelines
- **Keep it brief**: 2-3 short paragraphs maximum
- **Focus on practical value**: Explain concrete business scenarios
- **Multiple use cases**: Show how the same technical solution serves different business needs
- **Avoid unnecessary detail**: Don't create elaborate business scenarios that distract from the technical content

### Technical Considerations to Highlight
- **Idempotency concerns**: Always mention when operations might be retried
- **Asynchronous nature**: Explain timing considerations and testing approaches
- **Production vs Tutorial**: Distinguish between simplified tutorial code and real-world implementations
- **Framework limitations**: Mention workarounds needed (e.g., InMemoryEventStore token initialization)

### External References
- Link to official Axon documentation
- Reference GitHub repositories for complete examples  
- Point to community resources (AxonIQ Discuss)
- Include relevant architectural pattern links (Vertical Slice Architecture)

## Target Audience

**Primary**: Developers new to Axon Framework or migrating from version 4
**Secondary**: Experienced Axon developers exploring new AF5 features

**Assumed Knowledge**:
- Java development experience
- Basic understanding of DDD, CQRS, Event Sourcing concepts
- Familiarity with modern Java frameworks