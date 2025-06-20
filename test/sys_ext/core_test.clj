(ns sys-ext.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.system :as ds]
   [sys-ext.core :as se])
  (:import
   (clojure.lang ExceptionInfo)))

(deftest test-call
  (testing "call component"
    (is (= {:group {:a 1}}
          (-> {::ds/defs {:group {:a (se/call (constantly 1))}}}
            ds/start ::ds/instances))
      "works with no args")
    (is (= {:group {:a 2}}
          (-> {::ds/defs {:group {:a (se/call inc 1)}}}
            ds/start ::ds/instances))
      "works with 1 arg")
    (is (= {:group {:a 6}}
          (-> {::ds/defs {:group {:a (se/call + 1 2 3)}}}
            ds/start ::ds/instances))
      "works with several args")))

(deftest test-first-cycle
  ; The cycle detection logic is best tested in the tests
  ; for sys-ext.graph/first-cycle. These tests are mostly
  ; to test the translation of the system to a graph.
  (testing "first-cycle"
    (is (empty? (se/first-cycle {})))
    (is (empty? (se/first-cycle
                  {::ds/defs
                   {:service
                    {:a 1
                     :b (se/call inc (ds/local-ref [:a]))}}})))
    (is (= [[:service :a] [:service :a]]
          (se/first-cycle
            {::ds/defs
             {:service
              {:a (ds/local-ref [:a])}}})))
    (is (contains?
          #{[[:service :a] [:service :b] [:service :a]]
            [[:service :b] [:service :a] [:service :b]]}
          (se/first-cycle
            {::ds/defs
             {:service
              {:a (ds/local-ref [:b])
               :b (ds/local-ref [:a])}}})))))

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
      "expands inline defs inside of other inline defs")
    (is (= #{1 2 [2 3] {:x [[2 3]]}}
          (-> {::ds/defs
               {:service
                {:a 1
                 :b {:x [(se/call conj [(se/call inc (ds/local-ref [:a]))] 3)]}}}}
            se/expand-inline-defs
            ds/start ::ds/instances :service vals set))
      "expands inline defs inside of sequences")))

(deftest test-merge
  (testing "merge component"
    (is (= {:group {:a {}}}
          (-> {::ds/defs {:group {:a (se/merge)}}}
            ds/start ::ds/instances))
      "works with no args")
    (is (= {:group {:a {:A 1}}}
          (-> {::ds/defs {:group {:a (se/merge {:A 1})}}}
            ds/start ::ds/instances))
      "works with 1 arg")
    (is (= {:group {:a {:A 2 :B 4}}}
          (-> {::ds/defs {:group {:a (se/merge {:A 1} {:A 2 :B 3} {:B 4})}}}
            ds/start ::ds/instances))
      "works with several args")))

(deftest test-select-targets
  (let [sysdef {::ds/defs
                {:a {:A {:v 1}
                     :target {:v (ds/local-ref [:A :v])}}
                 :b {:B {:v 2}
                     :target (ds/alias-component [:b :B])}
                 :c {:C {:v 3}}}}]
    (is (= #{[:a :target]}
          (-> sysdef (se/select-targets [:a])
            ::ds/selected-component-ids))
      "Selecting one target works")
    (is (= #{[:a :target] [:b :target]}
          (-> sysdef (se/select-targets [:a :b])
            ::ds/selected-component-ids))
      "Selecting multiple targets works")
    (is (= #{[:a :target] [:b :target]}
          (-> sysdef (se/select-targets [:a :b :c] :throw-on-missing-target? false)
            ::ds/selected-component-ids))
      "Can ignore missing targets and select the rest")
    (is (= #{}
          (-> sysdef (se/select-targets [:c] :throw-on-missing-target? false)
            ::ds/selected-component-ids))
      "Can ignore missing targets and select nothing")
    (is (= #{[:c :C]}
          (-> sysdef (se/select-targets [:c] :target-component-name :C)
            ::ds/selected-component-ids))
      "Can provide a custom target component name")
    (testing "Exceptions are thrown when a target is not found"
      (is (thrown? ExceptionInfo
            (se/select-targets sysdef [:c])))
      (is (thrown? ExceptionInfo
            (se/select-targets sysdef [:c] :target-component-name :does-not-exist)))
      (is (thrown? ExceptionInfo
            (se/select-targets sysdef [:does-not-exist])))
      (is (thrown? ExceptionInfo
            (se/select-targets sysdef [:a :b :c])))
      (is (thrown? ExceptionInfo
            (se/select-targets sysdef [:a] :target-component-name :does-not-exist))))))
