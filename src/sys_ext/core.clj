(ns sys-ext.core
  (:refer-clojure :exclude [merge])
  (:require
   [com.rpl.specter :as sp]
   [donut.system :as ds]
   [sys-ext.graph :as seg]))

(defn call
  "Returns a component that calls `(apply f args)` when started.

   The behaviour when receiving a start signal more than once
   depends on the return value. If the last return value was nil,
   then f will be called again. Otherwise, f will not be called.
   It's recommended to return an empty map rather than nil
   so that start signals are idempotent.

   Always becomes nil when stopped."
  [f & args]
  {::ds/config {:args args :f f}
   ::ds/start
   (fn [{::ds/keys [instance] {:keys [args f]} ::ds/config}]
     (if (nil? instance)
       (apply f args)
       instance))
   ::ds/stop (constantly nil)})

(defn merge
  "Returns a component that merges ms when started.

   Differs from [[clojure.core/merge]] by always returning a map
   and never nil. This makes it easy to distinguish between a started
   but empty component and a component that was not yet started.

   Always becomes nil when stopped."
  [& ms]
  (apply call clojure.core/merge {} ms))

(defn first-cycle
  "Returns the first cycle discovered in the system, or nil
   if there are no cycles.

   The returned path can be passed directly to
   [[sys-ext.graph/cycle-error]]."
  [system]
  (-> (ds/init-system system (::ds/last-signal system ::ds/status))
    ::ds/graphs
    :topsort
    seg/first-cycle))

;;; Inline component definitions

(defn- add-inline-metadata* [component-def]
  (sp/transform
    (sp/walker
      #(and (map? %)
         (not= % component-def)
         (contains? % ::ds/start)))
    #(assoc % :sys-ext.inline/id (random-uuid))
    component-def))

(defn- add-inline-metadata
  [{:as system ::ds/keys [instances]}]
  (reduce
    (fn [system [component-group component-name]]
      (if (-> instances (get component-group)
            (contains? component-name))
        system
        (update-in system [::ds/defs component-group component-name]
          add-inline-metadata*)))
    system
    (ds/component-ids system)))

(defn inline-defs
  "Returns a seq of inline component definitions found inside of a
   given component definition.

   Inline component definitions are maps with a :donut.system/start
   key which are inside of another component's :donut.system/config map."
  [component-def]
  (sp/select
    (sp/walker
      #(and (map? %)
         (not= % component-def)
         (contains? % ::ds/start)))
    component-def))

(defn- expand-changes-seq
  [{:as system ::ds/keys [defs instances]}]
  (for [[component-group component-name :as component-id]
        #__ (ds/component-ids system)
        :when (-> instances (get component-group)
                (contains? component-name) not)
        [i inlined] (->> (get-in defs component-id)
                      inline-defs
                      (map vector (range)))
        :let [ns (namespace component-name)
              inline-component-id
              #__ [component-group
                   (keyword "sys-ext.inline"
                     (str (when (and ns (not= "sys-ext.inline" ns))
                            (str ns "-"))
                       (name component-name) "-" i))]]]
    {:expanded-component-def (dissoc inlined :sys-ext.inline/id)
     :expanded-component-id inline-component-id
     :inline-component-id (:sys-ext.inline/id inlined)}))

(defn- add-expanded-component
  [system
   {:keys [expanded-component-id expanded-component-def]}]
  (-> system
    (assoc-in (into [::ds/defs] expanded-component-id) expanded-component-def)))

(defn expand-inline-defs
  "Expands inline component definitions into full definitions.
   Replaces the inline definitions with references to the
   full definitions.

   Inline component definitions are maps with a :donut.system/start
   key which are inside of another component's :donut.system/config map."
  [system]
  (let [system (add-inline-metadata system)
        changes (expand-changes-seq system)
        inline-ids->expanded-ids (->> changes
                                   (map #(do [(:inline-component-id %) (:expanded-component-id %)]))
                                   (into {}))
        system (reduce add-expanded-component system changes)
        system (sp/transform
                 (sp/walker :sys-ext.inline/id)
                 (fn [{id :sys-ext.inline/id}]
                   (ds/ref (inline-ids->expanded-ids id)))
                 system)]
    (if (seq changes)
      (recur system)
      ;; call init-system to merge base and rebuild ::ds/graphs
      (ds/init-system system (::ds/last-signal system ::ds/status)))))

(defn select-targets
  "Returns the system with each `target-component-name` in each
   group in `group-names` selected.

   Note that if nothing is selected, [[donut.system]] will
   behave as if all components are selected. You can check
   whether `(::ds/selected-component-ids system)` is empty
   to determine if this is the case.

   Example: `(select-targets system [:nrepl :http-server])`
   would select the `[:nrepl :target]` and `[:http-server :target]`
   components. Then calls to `[[donut.system/signal]]` will only
   send signals to those components and their dependencies.
   This can be useful to start a subset of components.

   Options:

   `target-component-name` Default: `:target`
   The name of the component to select in each group.

   `throw-on-missing-target?` Default: true
   If true and a target component is not found in one of the groups,
   a [[clojure.lang.ExceptionInfo]] is thrown. If false, a missing
   target is simply ignored."
  [{:as system ::ds/keys [defs]}
   group-names
   & {:keys [target-component-name throw-on-missing-target?]
      :or {target-component-name :target
           throw-on-missing-target? true}}]
  (let [component-ids
        #__ (keep
              (fn [group-name]
                (cond
                  (-> defs (get group-name) (get target-component-name))
                  [group-name target-component-name]

                  throw-on-missing-target?
                  (throw (ex-info (str "Target component " target-component-name " not found in group " group-name)
                           {:group-name group-name
                            :target-component-name target-component-name}))))
              group-names)]
    (if (seq component-ids)
      (ds/select-components system component-ids)
      (assoc system ::ds/selected-component-ids #{}))))

(def
  ^{:doc "Indicates [[weak-ref]] status when present in
          the metadata of a [[donut.system/ref]]."}
  weak-ref-kw
  :sys-ext/weak-ref?)

(defn weak-ref?
  "Returns true if [[ref]] is a [[weak-ref]] or [[weak-local-ref]]."
  [ref]
  (boolean
    (and (ds/ref? ref)
      (some-> ref meta (get weak-ref-kw)))))

(defn weak-ref
  "A [[donut.system/ref]] that can be replaced by nil if the
   referenced component is not selected.
   See [[transform-weak-refs]] for details.

   [[ks-or-ref]] may be either an existing ref or a vector of keys
   to pass to [[donut.system/ref]]."
  [ks-or-ref]
  (with-meta
    (if (ds/ref? ks-or-ref)
      ks-or-ref
      (ds/ref ks-or-ref))
    {weak-ref-kw true}))

(defn weak-local-ref
  "A [[donut.system/local-ref]] that can be replaced by nil if the
   referenced component is not selected.
   See [[transform-weak-refs]] for details.

   [[ks-or-ref]] may be either an existing ref or a vector of keys
   to pass to [[donut.system/local-ref]]. Both local and
   non-local refs are accepted."
  [ks-or-ref]
  (weak-ref
    (if (ds/ref? ks-or-ref)
      ks-or-ref
      (ds/local-ref ks-or-ref))))

(defn remove-dead-refs
  "Removes [[weak-ref]]s and [[weak-local-ref]]s if the
   components they reference are not needed by the system's
   selected component ids. Removed refs are replaced with nil.
   See [[donut.system/select-components]].

   This is intended to be used after calling
   [[donut.system/select-components]] or [[select-targets]].

   This is useful in the case that one component has a side
   effect that a second component wants to wait for, but the
   second component can start without the first component.
   For example, you could have one component that updates
   a database schema and another component that
   retrieves some data from the database.
   When the schema needs to be updated, the first component
   can be selected by [[donut.system/select-components]] and
   the second component will wait for it to complete. But when
   the schema does not need to be updated and the first
   component is not selected, the second component can still
   start up without it."
  [{:as system ::ds/keys [defs]}]
  (let [without-weak-refs (assoc system ::ds/defs
                            (sp/transform
                              (sp/walker weak-ref?)
                              (constantly nil)
                              defs))
        {::ds/keys [graphs]}
        ;; call init-system to merge base and rebuild ::ds/graphs
        (ds/init-system without-weak-refs (::ds/last-signal system ::ds/status))
        {:keys [nodeset]} (:topsort graphs)]
    (->>
      (for [group-name (keys defs)
            :let [components (get defs group-name)]]
        [group-name
         (sp/transform
           (sp/walker weak-ref?)
           (fn [ref]
             (let [[ref-type ref-path] ref
                   [component-group component-name]
                   #__ (if (= ::ds/local-ref ref-type)
                         [group-name (first ref-path)]
                         ref-path)]
               (when (contains? nodeset [component-group component-name])
                 ref)))
           components)])
      (into {})
      (assoc system ::ds/defs))))
