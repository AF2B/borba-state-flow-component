# borba-state-flow-component

[![CI](https://github.com/AF2B/borba-state-flow-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-state-flow-component/actions/workflows/ci.yml)

[state-flow](https://github.com/nubank/state-flow) for the HTTP layer of a Borba service: flows that call the real handlers,
interceptors and routes, in process, with no server and no port. A test says which routes it wants and what the handlers are built
with, and writes steps; everything else is the chain the service runs.

## Install

Add it to the test alias of the service:

```clojure
:test {:extra-deps {io.github.af2b/borba-state-flow-component
                    {:git/url "https://github.com/AF2B/borba-state-flow-component"
                     :git/tag "v1.0.0"
                     :git/sha "<the commit of the tag, printed in the release notes>"}}}
```

It depends on state-flow 5.20.1, Pedestal 0.8.2, jsonista and Integrant, and on `borba-interceptors-component`,
`borba-handlers-component` and `borba-routes-component`, which it builds the service from.

## Use

```clojure
(ns com.example.orders.flows-test
  (:require
   [borba.flow :as sf]
   [clojure.test :refer [is]]
   [com.example.orders.handlers]          ; registers the handlers, as the service does
   [state-flow.api :as flow]))

(sf/defflow create-order
  {:routes     [["/v1/orders" :post :order/create]]
   :components {:orders (atom {})}}

  [response (sf/request :post "/v1/orders" {:body {:sku "a" :qty 2}})]
  (flow/return
   (do (is (= 201 (:status response)))
       (is (some? (:id (sf/json-body response)))))))
```

`defflow` defines a test, as the one of state-flow does, and runs its steps against a service built for it. `request` is a step that
sends a request to that service and returns the response, a map of `:status`, `:headers` and `:body`, which is text. `json-body` reads
it, with the keys as keywords, and returns nil when there is none.

```clojure
(sf/request :get "/v1/orders?status=open")
(sf/request :post "/v1/orders" {:body {:sku "a"}})                         ; a map is sent as JSON
(sf/request :post "/v1/orders" {:body "{not json"
                                :headers {"content-type" "application/json"}})  ; text is sent as it is
(sf/request :get "/v1/orders/7" {:headers {"authorization" "Bearer x"}})
```

The headers can be written as strings or keywords, in any case: they reach the handler in lower case, as they do from a client. A body that
is data gets the JSON content type, unless the headers say another.

## What the service is made of

The handlers and the interceptors are the ones the namespaces of the service register, so a test requires them, as the service does. The
chain is the one of the service. Nothing is a copy of it, so a flow shows what a client would get:

- every response has an `X-Request-Id`, and the error bodies the same id;
- a body that is not JSON is a `400` with `invalid-json`, one that is not `application/json` a `415`, and one that is too large a `413`;
- a path that no route matches is a typed `404`.

`defflow` takes the routes alone, or a map:

| Key | What it is |
|---|---|
| `:routes` | The routes, as `:http/routes` takes them: `[path method handler-key]`, with options |
| `:components` | The components the handlers and the interceptors are built with: where a test puts a map, an atom or a fake in the place of a database |
| `:interceptors` | Interceptors that take the place of the registered ones with the same keyword: where a test replaces an authentication |
| `:max-body-bytes` | The largest request body (default 1 MiB) |

```clojure
(sf/defflow reads-as-an-admin
  {:routes       [["/v1/admin" :get :admin/list]]
   :interceptors {:auth/admin {:name  :auth/admin
                               :enter (fn [ctx] (assoc-in ctx [:request :user] {:role :admin}))}}}

  [response (sf/request :get "/v1/admin")]
  (flow/return (is (= 200 (:status response)))))
```

A route that names a handler that is not registered, or an interceptor that is not, fails when the service is built, naming the route, as it
does when the service starts.

## Without a flow

`build-service` and `call` are what `defflow` and `request` are made of, for a test that does not want a flow:

```clojure
(def service (sf/build-service [["/echo" :post :order/echo]]))

(sf/call service :post "/echo" {:body {:a 1}})
;; => {:status 200, :headers {"X-Request-Id" "…", "Content-Type" "application/json; charset=utf-8"}, :body "{...}"}
```

## For the lint

`defflow` is a macro, and clj-kondo has to be told what it does. The library ships the configuration in
`resources/clj-kondo.exports/io.github.af2b/borba-state-flow-component/`. A project imports it, with that of state-flow, by running

```bash
clj-kondo --copy-configs --dependencies --lint "$(clojure -Spath -A:test)"
```

once, and its flows are linted as tests whose steps are bindings.

## API

| Name | What it does |
|---|---|
| `defflow` | Defines a test that runs a flow against a service built for it |
| `request` | A step of a flow that sends a request to that service |
| `json-body` | Reads the JSON body of a response |
| `build-service` | Builds the service, as a connector that answers in process |
| `call` | Sends a request to a connector, without a flow |

## Tests

The suite is made of flows, and of tests of `build-service`, `call` and `json-body`. They run against handlers and an interceptor that the test
registers, through the real components: the routes are checked, the chain is built, and the requests go through it.

## Design notes

- **The service under test is the service.** The routes, the handlers and the interceptors are built by the components that build them
  in production, so what a flow checks is what a client gets, and a mistake in a route is found by a test, as it is when the service starts.
- **A test replaces a collaborator, not the chain.** The components and the interceptors are the doors for it; the parsing, the limits, the
  errors and the request ids are not for sale.
- **No port.** The requests go through Pedestal's own test path, so the flows are fast and never collide with a port in use.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
