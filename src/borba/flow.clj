(ns borba.flow
  "state-flow for the HTTP layer of a Borba service: flows that call the real
   handlers, interceptors and routes, in process, with no server and no port.

   A test says which routes it wants, and gives the components the handlers
   need, which is where a test replaces a database with a map:

     (ns com.example.orders.flows-test
       (:require
        [borba.flow :as sf]
        [clojure.test :refer [is]]
        [com.example.orders.handlers]
        [state-flow.api :as flow]))

     (sf/defflow create-order
       {:routes     [[\"/v1/orders\" :post :order/create]]
        :components {:orders (atom {})}}

       [resp (sf/request :post \"/v1/orders\" {:body {:sku \"a\" :qty 2}})]
       (flow/return
        (do (is (= 201 (:status resp)))
            (is (some? (:id (sf/json-body resp)))))))

   The handlers and the interceptors of the service are the ones its namespaces
   register, so a test requires them, as the service does. The chain is the one
   of the service: a request gets an id, a body that is not JSON is a 400, and a
   path that no route matches is a typed 404."
  (:require
   [borba.handlers.component]
   [borba.interceptors.component]
   [borba.routes.component]
   [clojure.string :as str]
   [integrant.core :as ig]
   [io.pedestal.connector :as conn]
   [io.pedestal.connector.test :as test]
   [io.pedestal.http.jetty :as jetty]
   [jsonista.core :as jsonista]
   [state-flow.api :as flow]))

(def ^:private json-content-type "application/json")

(defn build-service
  "Builds the handlers, the interceptors and the routes of a service, as the
   components that start them do, and returns a connector that answers requests
   in process, without listening on a port.
   - service: the routes, a vector of [path method handler-key], or a map of
     :routes and, optionally, :components, :interceptors and :max-body-bytes
   - routes: (in the map) the routes, as `:http/routes` takes them
   - components: (in the map) the components the handlers and the interceptors
     are built with, where a test puts what replaces a database
   - interceptors: (in the map) interceptors that take the place of the
     registered ones with the same keyword, where a test replaces an
     authentication
   - max-body-bytes: (in the map) the largest request body (default 1 MiB)"
  [service]
  (let [{:keys [routes components interceptors max-body-bytes]}
        (if (map? service) service {:routes service})
        registered (ig/init-key :service/interceptors
                                {:components components})
        available  (merge registered interceptors)
        handlers   (ig/init-key :service/handlers
                                (cond-> {:components   components
                                         :interceptors available}
                                  max-body-bytes
                                  (assoc :max-body-bytes max-body-bytes)))
        fragment   (ig/init-key :http/routes
                                {:routes       routes
                                 :handlers     handlers
                                 :interceptors available})]
    (jetty/create-connector
     (conn/with-routes (conn/default-connector-map "localhost" 0) fragment)
     {:join? false})))

(defn- request-body
  "Returns the body of a request as text: JSON for a map or a collection, the
   text itself, or nil."
  [body]
  (cond
    (nil? body)    nil
    (string? body) body
    :else          (jsonista/write-value-as-string body)))

(defn- request-headers
  "Returns the headers of a request, with their names in lower case as they
   reach a handler, and the JSON content type when the body is data and the
   headers do not say another."
  [headers
   body]
  (let [named (into {}
                    (map (fn [[header-name value]]
                           [(str/lower-case (name header-name)) value]))
                    headers)]
    (cond-> named
      (and (coll? body) (not (contains? named "content-type")))
      (assoc "content-type" json-content-type))))

(defn call
  "Sends a request to a connector, in process, and returns the response, a map
   of :status, :headers and :body, which is text.
   - connector: what `build-service` returns
   - method: the method of the request, a keyword such as :get or :post
   - path: the path, with its query string
   - headers: (optional, in a map) the headers, with their names as strings
   - body: (optional, in a map) a map or a collection, which is sent as JSON,
     or text, which is sent as it is"
  ([connector
    method
    path]
   (call connector method path {}))
  ([connector
    method
    path
    {:keys [headers body]}]
   (test/response-for connector
                      method
                      path
                      :headers (request-headers headers body)
                      :body    (request-body body))))

(defn request
  "A step of a flow that sends a request to the service of the flow, and
   returns the response, a map of :status, :headers and :body, which is text:
   `json-body` reads it.
   - method: the method of the request, a keyword such as :get or :post
   - path: the path, with its query string
   - opts: a map of :headers, with their names as strings, and :body, a map or
     a collection that is sent as JSON, or text that is sent as it is
     (optional)"
  ([method
    path]
   (request method path {}))
  ([method
    path
    opts]
   (flow/get-state
    (fn [{:keys [connector]}]
      (call connector method path opts)))))

(defn json-body
  "Reads the JSON body of a response, with its keys as keywords, or returns nil
   when it has none.
   - response: a map with a :body that is JSON text"
  [response]
  (let [body (:body response)]
    (when (seq body)
      (jsonista/read-value body jsonista/keyword-keys-object-mapper))))

(defmacro defflow
  "Defines a test that runs a flow against a service built for it, as
   `state-flow.api/defflow` does, with the state `{:connector ...}` that
   `request` uses.

     (sf/defflow health-flow
       [[\"/health\" :get :health/check]]

       [resp (sf/request :get \"/health\")]
       (flow/return (is (= 200 (:status resp)))))

   - test-name: the symbol of the test
   - service: what `build-service` takes: the routes, or a map of the routes and
     the components
   - body: the steps of the flow"
  [test-name
   service
   & body]
  `(flow/defflow ~test-name
     {:init (fn [] {:connector (build-service ~service)})}
     ~@body))
