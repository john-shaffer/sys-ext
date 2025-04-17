(ns sys-ext.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.system :as ds]
   [sys-ext.core :as se]))

(deftest test-expand-inline-defs
  (testing "expand-inline-defs"
    (is (= {:service {:a 1 :b 2}}
          (-> {::ds/defs
               {:service
                {:a 1
                 :b (se/call inc (ds/local-ref [:a]))}}}
            se/expand-inline-defs
            ds/start ::ds/instances))
      "leaves components without inline defs unchanged")
    (is (= #{1 2 {:x 2}}
          (-> {::ds/defs
               {:service
                {:a 1
                 :b {:x (se/call inc (ds/local-ref [:a]))}}}}
            se/expand-inline-defs
            ds/start ::ds/instances :service vals set))
      "expands inline defs")
    (is (= #{1 2 3 {:x 3}}
          (-> {::ds/defs
               {:service
                {:a 1
                 :b {:x (se/call inc (se/call inc (ds/local-ref [:a])))}}}}
            se/expand-inline-defs
            ds/start ::ds/instances :service vals set))
      "expands inline defs inside of other inline defs")))
