(ns sys-ext.graph
  "Graph algorithms used by sys-ext that are not
   provided by loom."
  (:require
   [clojure.string :as str]
   [loom.graph :as lg]))

(defn root-nodes
  "Returns a seq of the nodes of the graph which have no predecessors."
  [graph]
  (->> (lg/nodes graph)
    (filter #(empty? (lg/predecessors graph %)))))

(defn first-cycle
  "Returns the first cycle discovered in the graph, or nil
   if there are no cycles.

   Performs a depth-first search from each root node and checks
   for edges pointing back to nodes that have already been visited."
  [graph]
  (let [all-nodes (set (lg/nodes graph))
        start-nodes (set (root-nodes graph))
        ; In case the entire graph is a cycle, we can start from
        ; any node to find a cycle path.
        start-nodes (if (empty? start-nodes)
                      all-nodes
                      start-nodes)
        checked (atom #{})
        visited (atom #{})
        visit! (fn visit! [node]
                 (if (contains? @visited node)
                   [node]
                   (let [_ (swap! checked conj node)
                         _ (swap! visited conj node)
                         path (some visit! (lg/successors graph node))
                         end (first path)]
                     (swap! visited disj node)
                     (cond
                       (not path)
                       nil

                       (= node end)
                       (conj path node)

                       (contains? @visited end)
                       (conj path node)

                       :else
                       path))))]
    (or (some visit! start-nodes)
      ; If we traversed from the roots but didn't visit all nodes, then
      ; the unvisited nodes must contain at least one cycle.
      (when (not= @checked all-nodes)
        (recur
          (apply lg/remove-nodes graph @checked))))))

(defn cycle-error
  "Returns a [[clojure.lang.ExceptionInfo]] with a descriptive
   message.

   The error message will contain at most [[max-paths]] paths."
  [path & {:keys [max-paths] :or {max-paths 10}}]
  (let [n (count path)
        trimmed-path (if (> n max-paths)
                       (concat (take max-paths path) ["..." (first path)])
                       path)]
    (ex-info
      (if (pos? max-paths)
        (str "Cycle detected: "
          (str/join " -> " trimmed-path))
        "Cycle detected")
      {:path path})))
