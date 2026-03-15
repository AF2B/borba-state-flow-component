(ns borba.flow
  "Utilities for writing Pedestal + state-flow integration tests.

  Domain Context
  --------------
  This namespace abstracts the boilerplate required to run HTTP integration
  tests against a Pedestal service using state-flow. Each microservice in the
  Borba ecosystem should depend on this component and only define test logic —
  never infrastructure setup.

  Provided API
  ------------
  - `build-service-fn` : builds a Pedestal test service from a routes vector
  - `request`          : issues an HTTP request within a state-flow context
  - `json-body`        : parses the JSON body of a response map
  - `defflow`          : macro that wires deftest + flow/run + flow/flow

  Usage
  -----
  Add to :test alias in deps.edn:

    borba/borba-state-flow-component
    {:git/url \"https://github.com/AF2B/borba-state-flow-component\"
     :git/tag \"v0.1.0\"
     :git/sha \"<sha>\"}

  Example
  -------
  (ns flows.sum-flow-test
    (:require [borba.flow :as sf]
              [clojure.test :refer [is]]
              [com.borba.sum-service.handlers.http.routes]
              [borba.routes.component]))

  (sf/defflow run-integration-tests
    [[\"/v1/sum\" :get :sum/compute]]

    [resp (sf/request :get \"/v1/sum?a=2&b=3\")]
    (flow/return
      (is (= 200 (:status resp))          \"status 200\")
      (is (= {:result 5} (sf/json-body resp)) \"correct result\")))
  "
  (:require
   [cheshire.core :as json]
   [integrant.core :as ig]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.test :as ptest]
   [state-flow.api :as flow]))

(defn build-service-fn
  "Builds a Pedestal in-process service function for integration testing.

  Contract
  --------
  Input  : routes — vector of route tuples e.g. [[\"/v1/sum\" :get :sum/compute]]
  Output : a Pedestal service-fn suitable for use with ptest/response-for

  Invariants
  ----------
  - Requires all ig/init-key handlers to be registered before calling
    (i.e. the service and routes namespaces must be required)
  - Does NOT start a real HTTP server — uses Pedestal's servlet directly
  "
  [routes]
  (let [handlers  (ig/init-key :service/handlers {})
        route-set (ig/init-key :http/routes {:routes routes :handlers handlers})]
    (-> {::http/routes (route/expand-routes route-set)
         ::http/type   :jetty
         ::http/join?  false}
        http/default-interceptors
        http/create-servlet
        ::http/service-fn)))

(defn request
  "Issues an HTTP request within a state-flow context.

  Contract
  --------
  Input  : method — keyword (:get :post :put :delete)
           path   — string with optional query params e.g. \"/v1/sum?a=1&b=2\"
  Output : state-flow step that yields a Pedestal response map

  Invariants
  ----------
  - State must contain :service-fn (set by defflow or flow/run)
  "
  [method path]
  (flow/fmap
   #(ptest/response-for (:service-fn %) method path)
   (flow/get-state)))

(defn json-body
  "Parses the JSON body of a Pedestal response map into a Clojure map.

  Contract
  --------
  Input  : resp — Pedestal response map with a :body string key
  Output : Clojure map with keyword keys

  Example
  -------
  (json-body {:status 200 :body \"{\\\"result\\\":5}\"})
  ;; => {:result 5}
  "
  [resp]
  (json/parse-string (:body resp) true))

(defmacro defflow
  "Defines an integration test that runs a state-flow against a Pedestal service.

  Contract
  --------
  Input  : test-name — symbol, becomes the deftest name
           routes    — vector of route tuples
           body      — state-flow steps (bindings and flow/return forms)

  Expands to
  ----------
  (deftest test-name
    (flow/run
      (flow/flow \"<test-name>\"
        <body>)
      {:service-fn (build-service-fn routes)}))

  Example
  -------
  (defflow run-integration-tests
    [[\"/v1/sum\" :get :sum/compute]]

    [resp (request :get \"/v1/sum?a=2&b=3\")]
    (flow/return
      (is (= 200 (:status resp)) \"status 200\")))
  "
  [test-name routes & body]
  `(clojure.test/deftest ~test-name
     (flow/run
      (flow/flow ~(str test-name)
                 ~@body)
      {:service-fn (build-service-fn ~routes)})))