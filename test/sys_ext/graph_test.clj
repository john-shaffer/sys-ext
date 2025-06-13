(ns sys-ext.graph-test
  (:require
   [clojure.test :refer [are deftest is]]
   [loom.graph :as lg]
   [sys-ext.graph :as seg]))

(deftest test-root-nodes
  (is (= [] (seg/root-nodes (lg/digraph))))
  (are [expected graph] (= expected (set (seg/root-nodes (lg/digraph graph))))
    #{} {:a #{:b}, :b #{:a}}
    #{} {:a #{:b} :b #{:c} :c #{:a}}
    #{} {:a #{:b} :b #{:c} :c #{:a} :d #{:e} :e #{:d}}
    #{:a} {:a #{}}
    #{:a :b} {:a #{}, :b #{}}
    #{:a} {:a #{:b} :b #{}}
    #{:d :e} {:a #{:b} :b #{:c} :c #{:a} :d #{} :e #{}}))

(deftest test-first-cycle
  (is (nil? (seg/first-cycle (lg/digraph))))
  (are [expected graph] (contains? expected (seg/first-cycle (lg/digraph graph)))
    #{[:a :a]}
    {:a #{:a}}

    #{[:a :b :a] [:b :a :b]}
    {:a #{:b}
     :b #{:a}}

    #{[:a :b :a] [:b :a :b]}
    {:a #{:b}
     :b #{:a}
     :c #{}}

    #{[:a :b :a] [:b :a :b]}
    {:a #{:b}
     :b #{:a}
     :c #{:a}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}
     :d #{:a}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}
     :d #{}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c] [:d :d]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}
     :d #{:d}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c] [:d :e :d] [:e :d :e]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}
     :d #{:e}
     :e #{:d}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c] [:d :e :d] [:e :d :e]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}
     :d #{:e}
     :e #{:d}
     :f #{}}

    #{[:a :c :b :a] [:b :a :c :b] [:c :b :a :c] [:d :e :d] [:e :d :e]}
    {:a #{:b}
     :b #{:c}
     :c #{:a}
     :d #{:e}
     :e #{:d}
     :f #{:e}}))

(deftest test-cycle-error
  (are [message path] (is (= message (ex-message (seg/cycle-error path))))
    "Cycle detected: :a -> :a" [:a :a]
    "Cycle detected: :a -> :b -> :a" [:a :b :a]
    "Cycle detected: :a -> :b -> :c -> :a" [:a :b :c :a]
    "Cycle detected: :a -> :b -> :c -> :a" [:a :b :c :a])
  (is (= "Cycle detected"
        (ex-message (seg/cycle-error [:a :a] :max-paths 0))))
  (is (= "Cycle detected: :a -> :b -> ... -> :a"
        (ex-message (seg/cycle-error [:a :b :c :a] :max-paths 2)))))
