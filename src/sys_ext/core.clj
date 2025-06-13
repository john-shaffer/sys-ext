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
   if there are no cycles."
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
