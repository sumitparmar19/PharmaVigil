# ADR-009: WS-Security UsernameToken + Timestamp for SOAP partner intake

<!-- Records how licensing partners and CROs authenticate to the SOAP SubmitCase/GetCaseStatus service in
     intake-gateway, and why mutual TLS was not chosen. -->

- **Status:** accepted
- **Milestone:** M7

## Context

Partners send E2B(R3) case reports to `intake-gateway` over SOAP (`/ws`, contract in
`services/intake-gateway/src/main/resources/xsd/partner-intake.xsd`). The build instructions ask for either
a WS-Security UsernameToken or mutual TLS. Constraints: the demo runs in docker-compose, kind and (owner
step) Azure behind an ingress that terminates TLS; secrets may only come from environment variables; the
platform has one partner in the demo; reports carry adverse-event data, so a replayed or forged message must
not create cases.

## Options considered

1. **WS-Security UsernameToken (password digest) + Timestamp, inside the SOAP message, over TLS.**
   Pros: works through any TLS-terminating proxy or ingress, testable end to end with plain HTTP in tests,
   standard in pharmacovigilance gateways, credentials are two environment variables. Cons: a shared
   password per partner (rotation is manual); the message itself is not signed.
2. **Mutual TLS.** Pros: strong client identity, no password. Cons: the TLS session ends at the ingress, so
   the service only sees a forwarded header that it must trust; certificate issuance and rotation for each
   partner; much harder to exercise in docker-compose and kind; nothing to show in the SOAP contract.
3. **WS-Security X.509 signature.** Pros: message-level integrity. Cons: certificate management as in 2,
   plus XML signature processing on every request; more than the demo needs.

## Decision

Option 1. Spring-WS `Wss4jSecurityInterceptor` requires a `Timestamp` and a `UsernameToken` on every request
(`WebServiceConfig`). The only accepted user and password are `SOAP_PARTNER_USER` and
`SOAP_PARTNER_PASSWORD` (environment variables, at least 12 characters); with either missing every request
is refused. Timestamps older than 300 seconds are rejected. Partners are told to use the password digest
form; transport is TLS at the ingress (M10).

## Consequences

- A captured message could be replayed only within the 5-minute timestamp window, and intake is idempotent:
  a byte-identical resend returns the original receipt and writes no event, so a replay cannot create a
  second case.
- Authentication happens before the body is checked against the contract schema, so unauthenticated
  callers learn nothing about validation.
- The WSDL (`/ws/partner-intake.wsdl`) is public by design; it contains no secrets.
- Tests (`SoapIntakeContractTest`) cover: signed requests accepted, wrong password, unknown user and missing
  security header refused.
- If several partners are onboarded, the in-memory user map should move to a credential store with per-
  partner accounts; if a regulator requires message integrity, add option 3 on top.
