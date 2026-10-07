# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-10-06

### Added

- `request` takes `{:headers ... :body ...}`: a map or a collection is sent as JSON, with the JSON content type, and text as it is. The
  documentation showed it, and the function took only a method and a path.
- `call`, which sends a request to a connector without a flow, and `build-service`, which builds the service of a test as a connector.
- `:components`, for what the handlers and the interceptors are built with, `:interceptors`, to replace registered ones, such as an
  authentication, and `:max-body-bytes`, in the service of a flow.
- The names of the headers are written in lower case, whatever the caller wrote, as they reach a handler.
- The configuration and the hook of clj-kondo for `defflow`, in `resources/clj-kondo.exports`, so that its flows are linted as tests.
- A test suite of flows and tests, with 100% of the code covered.

### Changed

- **Breaking:** the service of a flow is built from the components of the HTTP layer 2: the interceptors, the handlers and the routes
  of Borba, through Pedestal 0.8.2 and its connector. It was built on the routes component 0.1.0 and Pedestal 0.7.0.
- **Breaking:** the state of a flow is `{:connector ...}` where it was `{:service-fn ...}`, and `defflow` is `state-flow.api/defflow`
  underneath, so a flow reports as the ones of state-flow do.
- **Breaking:** `build-service` returns a connector.
- JSON is read and written with jsonista, and Cheshire is no longer a dependency.
- The published library is named `io.github.af2b/borba-state-flow-component`.

### Security

- Pins Jackson to 2.22.3. The 2.21.1 that Pedestal brings through transit-java, and the 2.22.2 that jsonista 1.0.1 brings, have high
  advisories (GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-r7wm-3cxj-wff9, GHSA-cxp5-3px4-pw24 and GHSA-wv8q-qhhj-9h54).

## [0.1.0] - 2026-03-14

First release: `build-service`, `request`, `json-body` and `defflow` for Pedestal and state-flow.

[Unreleased]: https://github.com/AF2B/borba-state-flow-component/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/AF2B/borba-state-flow-component/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/AF2B/borba-state-flow-component/releases/tag/v0.1.0
