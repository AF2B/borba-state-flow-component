(ns borba.flow-test
  (:require
   [borba.flow :as sf]
   [borba.handlers.registry :as handlers]
   [borba.interceptors.registry :as interceptors]
   [clojure.test :refer [deftest is testing]]
   [state-flow.api :as flow]))

(set! *warn-on-reflection* true)

;; What a service registers: a handler that says what it was given, one that
;; says what an interceptor did to the request, and the interceptor.

(defmethod handlers/handler :demo/echo
  [_ {:keys [prefix]}]
  (fn [{:keys [body-params query-params header-params request-id]}]
    {:status 200
     :body   {:prefix     prefix
              :body       body-params
              :query      query-params
              :agent      (get header-params "x-agent")
              :request-id request-id}}))

(defmethod handlers/handler :demo/stamped
  [_ _components]
  (fn [{:keys [request]}]
    {:status 200 :body {:stamp (:stamp request)}}))

(defmethod handlers/handler-interceptors :demo/stamped
  [_]
  [:demo/stamp])

(defmethod interceptors/interceptor :demo/stamp
  [_ _components]
  {:enter (fn [ctx] (assoc-in ctx [:request :stamp] "registered"))})

;; Flows, which are tests of their own: the library tests itself with itself.

(sf/defflow echoes-what-it-is-given
  {:routes     [["/echo" :post :demo/echo]]
   :components {:prefix "p"}}

  [response (sf/request :post "/echo?x=1" {:headers {"x-agent" "tests"}
                                           :body    {:a 1}})]
  (flow/return
   (do (is (= 200 (:status response)))
       (is (= {:prefix "p" :body {:a 1} :query {:x "1"} :agent "tests"}
              (dissoc (sf/json-body response) :request-id)))
       (is (= (:request-id (sf/json-body response))
              (get-in response [:headers "X-Request-Id"]))))))

(sf/defflow takes-the-routes-alone
  [["/echo" :post :demo/echo]]

  [response (sf/request :post "/echo" {:body {:b 2}})]
  (flow/return
   (do (is (= 200 (:status response)))
       (is (= {:b 2} (:body (sf/json-body response)))))))

(sf/defflow answers-a-path-no-route-matches
  [["/echo" :post :demo/echo]]

  [response (sf/request :get "/nowhere")]
  (flow/return
   (do (is (= 404 (:status response)))
       (is (= "not-found" (:error (sf/json-body response)))))))

(sf/defflow steps-follow-one-another
  [["/echo" :post :demo/echo]]

  [first-response (sf/request :post "/echo" {:body {:n 1}})
   second-response (sf/request :post "/echo" {:body {:n 2}})]
  (flow/return
   (is (not= (:request-id (sf/json-body first-response))
             (:request-id (sf/json-body second-response))))))

(deftest build-service-test
  (testing "takes the routes alone, or with what the handlers need"
    (is (= 200 (:status (sf/call (sf/build-service [["/echo" :post :demo/echo]])
                                 :post
                                 "/echo"))))
    (is (= "c" (-> (sf/call (sf/build-service
                             {:routes     [["/echo" :post :demo/echo]]
                              :components {:prefix "c"}})
                            :post
                            "/echo")
                   sf/json-body
                   :prefix))))

  (testing "uses the interceptors the service registers, on the handlers that
            ask for them"
    (let [service (sf/build-service [["/stamped" :get :demo/stamped]])]
      (is (= "registered" (:stamp (sf/json-body
                                   (sf/call service :get "/stamped")))))))

  (testing "takes interceptors that replace the registered ones, which is how
            a test replaces an authentication"
    (let [service (sf/build-service
                   {:routes       [["/stamped" :get :demo/stamped]]
                    :interceptors {:demo/stamp
                                   {:name  :demo/stamp
                                    :enter (fn [ctx]
                                             (assoc-in ctx
                                                       [:request :stamp]
                                                       "replaced"))}}})]
      (is (= "replaced" (:stamp (sf/json-body
                                 (sf/call service :get "/stamped")))))))

  (testing "limits the body as the service does"
    (let [service (sf/build-service
                   {:routes         [["/echo" :post :demo/echo]]
                    :max-body-bytes 8})]
      (is (= 413 (:status (sf/call service :post "/echo"
                                   {:body {:a "a body that is too large"}}))))))

  (testing "fails naming the route that has no handler"
    (is (= :borba.routes.component/unknown-handler
           (try (sf/build-service [["/x" :get :demo/nobody]])
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))

(deftest call-test
  (let [service (sf/build-service [["/echo" :post :demo/echo]])]
    (testing "sends data as JSON, with the content type"
      (let [response (sf/call service :post "/echo" {:body {:a [1 2]}})]
        (is (= 200 (:status response)))
        (is (= {:a [1 2]} (:body (sf/json-body response))))))

    (testing "sends text as it is, and a body that is not JSON is a 400"
      (let [response (sf/call service :post "/echo"
                              {:headers {"Content-Type" "application/json"}
                               :body    "{not json"})]
        (is (= 400 (:status response)))
        (is (= "invalid-json" (:error (sf/json-body response))))))

    (testing "text with no content type is not JSON, which is a 415"
      (is (= 415 (:status (sf/call service :post "/echo" {:body "hello"})))))

    (testing "names the headers in lower case, whatever the caller wrote"
      (doseq [header-name ["X-Agent" "x-agent" :X-Agent]]
        (is (= "me" (-> (sf/call service :post "/echo"
                                 {:headers {header-name "me"}})
                        sf/json-body
                        :agent))
            (str header-name))))

    (testing "sends no body, and the handler has an empty map"
      (is (= {} (:body (sf/json-body (sf/call service :post "/echo"))))))

    (testing "leaves a content type that was given"
      (is (= 200 (:status (sf/call service :post "/echo"
                                   {:headers {"content-type"
                                              "application/vnd.api+json"}
                                    :body    {:a 1}})))))))

(deftest json-body-test
  (testing "reads the body with keys as keywords"
    (is (= {:a 1 :b {:c [1 2]}}
           (sf/json-body {:body "{\"a\":1,\"b\":{\"c\":[1,2]}}"}))))

  (testing "is nil when there is no body"
    (is (nil? (sf/json-body {:status 204})))
    (is (nil? (sf/json-body {:status 204 :body ""})))
    (is (nil? (sf/json-body {:status 204 :body nil})))))
