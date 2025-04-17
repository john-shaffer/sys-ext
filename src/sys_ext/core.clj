(ns sys-ext.core
  (:refer-clojure :exclude [merge])
  (:require
   [donut.system :as-alias ds]))

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
  "Returns a component that merges m when started.

   Differs from [[clojure.core/merge]] by always returning a map
   and never nil. This makes it easy to distinguish between a started
   but empty component and a component that was not yet started.

   Always becomes nil when stopped."
  [& ms]
  (apply call clojure.core/merge {} ms))
