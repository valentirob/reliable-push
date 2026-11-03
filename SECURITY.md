# Security Policy

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Report vulnerabilities privately through GitHub Security Advisories: open the
repository's **Security** tab and choose **Report a vulnerability**
(`Security` > `Advisories` > `Report a vulnerability`).

Include a description, affected component and version, reproduction steps, and
the impact you expect. Please do not include real credentials or personal data.

We will acknowledge your report, work with you on a fix, and credit you in the
advisory unless you prefer to stay anonymous. Please allow reasonable time for a
fix before any public disclosure.

## Scope

In scope: the backend service, the Android and iOS SDKs, the Java client and
the deployment artifacts in this repository. Design details for authentication,
webhook signing and data handling are documented in `docs/security.md` and the
design spec.

## Supported versions

The project is pre-release; security fixes are made on the latest `develop`
and, once released, on the latest released version.
