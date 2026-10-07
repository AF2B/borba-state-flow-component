(ns borba.flow-hooks
  "The hook that teaches clj-kondo what borba.flow/defflow does:

     (defflow name service step ...)

   defines `name`, evaluates the service, and runs the steps as a flow. A step
   that is a vector holds bindings, a symbol and the step whose result it names,
   as in the flows of state-flow; any other form is a step of its own."
  (:require
   [clj-kondo.hooks-api :as hooks]))

(defn- flow-bindings
  "Returns the bindings of the steps: those of a vector, and a throwaway name
   for the other steps."
  [steps]
  (vec (mapcat (fn [step]
                 (if (hooks/vector-node? step)
                   (:children step)
                   [(hooks/token-node '_) step]))
               steps)))

(defn defflow
  "Rewrites a call of borba.flow/defflow into a definition whose value is a let
   of the service and the steps.
   - node: the call, as clj-kondo gives it"
  [{:keys [node]}]
  (let [[test-name service & steps] (rest (:children node))
        bindings (into [(hooks/token-node '_) service]
                       (flow-bindings steps))]
    {:node       (with-meta
                   (hooks/list-node
                    [(hooks/token-node 'clojure.test/deftest)
                     test-name
                     (hooks/list-node
                      [(hooks/token-node 'let)
                       (hooks/vector-node bindings)])])
                   (meta node))
     :defined-by 'borba.flow/defflow}))
