# Dediren Fixture Realism Design

Date: 2026-05-20

## Purpose

Fixtures are part of the public contract for Dediren. They should either be
small contract smoke examples or realistic, named use cases that exercise a
profile without inventing misleading architecture semantics.

## Fixture Classes

- Contract smoke fixtures may stay minimal. `valid-basic.json`, invalid source
  fixtures, plugin manifests, and simple policy fixtures exist to prove schema,
  envelope, and command behavior with the smallest useful data.
- Scenario fixtures must be based on a coherent domain story. Source fixtures,
  generated render metadata, deterministic layout results, and expected exports
  should preserve that story instead of using labels that only satisfy tests.
- Derived fixtures must trace to a source fixture or explicitly say that they
  are hand-authored geometry fixtures. When a source label or relationship
  changes, dependent layout, render metadata, export, and test expectations must
  move with it.

## Current Scenario Stories

- `valid-pipeline-rich.json` is a generic commerce order-processing scenario:
  a client uses a web app, the web app calls an orders API, the API writes order
  records, requests payment authorization, and publishes work for fulfillment.
- `valid-pipeline-archimate.json` is the ArchiMate profile version of the same
  commerce order-processing scenario. It should use ArchiMate relationship
  types that match the visible claim. The payment interaction is a request flow
  from the order capability to payment authorization, not a provider-to-consumer
  service relationship hidden behind a call-like label.
- `valid-archimate-oef.json` remains a minimal OEF export contract fixture. It
  is intentionally small and does not try to be the full commerce scenario.
- `valid-uml-basic.json` and `valid-uml-complex.json` model order and
  fulfillment domain behavior. They are realistic enough to keep unless a later
  UML-specific review finds notation or export drift.

## Required Corrections

- Align the order-processing payment label across generic and ArchiMate
  fixtures as `requests payment authorization`.
- Use a payment service label that names the capability, such as
  `Payment Authorization Service`, instead of the vague `Payments Service` or
  provider-only wording.
- In the ArchiMate order-processing fixture, classify the payment interaction
  as `Flow` from the order-side service to the payment authorization service.
  This preserves the real order-processing claim and avoids using a reverse
  `Serving` relationship to describe an API call.
- Keep the existing reverse-service routing issue as an ELK test concern when a
  real fixture intentionally models provider-to-consumer service semantics. Do
  not use unrealistic source semantics to manufacture routing stress.

## Verification

Every corrected fixture must pass:

- JSON schema validation through the existing schema contract tests;
- profile validation for ArchiMate and UML source fixtures;
- the fixture-mode CLI pipeline tests that render checked-in layout fixtures;
- the real ELK ArchiMate render lane for the order-processing scenario.
