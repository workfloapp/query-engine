(ns workflo.query-engine.query
  (:require [clojure.string :as string]
            [clojure.zip :as zip]
            [inflections.core :as inflections]
            [workflo.macros.entity :as e]
            [workflo.macros.query.util :as query.util]
            [workflo.query-engine.data-layer :as data-layer]
            [workflo.query-engine.query.data-zip :as dz]
            [workflo.query-engine.query.zip :as qz]
            [workflo.query-engine.util :as util]))

;;;; Helpers

(defn id-attr [env]
  (get env :id-attr :db/id))

(defn ref-id-attr [env]
  (get env :ref-id-attr :db/id))

;;;; Working with entities

(defn ref-entity
  [source attr attr-ref]
  (when (and attr-ref (:entity attr-ref))
    (try
      (e/resolve-entity (:entity attr-ref))
      (catch #?(:cljs js/Error :clj Exception) e
          (let [err-msg (str "Failed to resolve entity for attribute "
                             attr " in data: " source)]
            (throw #?(:cljs (js/Error. err-msg)
                      :clj (Exception. err-msg))))))))

(defn backref-attr->forward-attrs
  [k]
  {:pre [(query.util/backref-attr? k)]}
  (let [ns (namespace k)
        nm (name k)]
    [(keyword ns (subs nm 1))
     (keyword (inflections/singular ns) (subs nm 1))]))

(defn backref
  "Returns the target entity definition, the forward ref attribute
   name and the cardinality of the backref for a source entity and
   a backref attribute."
  [source attr]
  (let [source-entity (:entity (meta source))
        forward-attrs (backref-attr->forward-attrs attr)
        attr-refs     (zipmap forward-attrs
                              (map (fn [forward-attr]
                                     (get (e/entity-backrefs (:name source-entity))
                                          forward-attr))
                                   forward-attrs))
        attr-ref      (first (filter (comp (complement nil?) second) attr-refs))
        entity        (ref-entity source attr (second attr-ref))]
    {:entity entity
     :forward-attr (first attr-ref)
     :many? (:many? (second attr-ref))}))

(defn entity-ref
  "Returns the target entity definition for a source entity and
   a ref attribute."
  [source attr]
  (let [source-entity (:entity (meta source))
        attr-ref      (get (util/memoized-entity-refs (:name source-entity)) attr)
        entity        (ref-entity source attr attr-ref)]
    {:entity entity
     :many? (:many? attr-ref)}))


(defn singular-key?
  "Returns whether or not a key is singular (e.g. :user,
   not :users)."
  [k]
  (= (util/qualified-name k)
     (inflections/singular (util/qualified-name k))))

;;;; Data fetching

(defn fetch-entity-data
  "Fetches one or more items of an entity from the data layer."
  [env entity singular? id-or-ids attrs params]
  (let [env   (select-keys env [:cache :data-layer :db :id-attr :ref-id-attr
                                :following-ref? :viewer :skip-authorization?])
        attrs (if (seq attrs) attrs [(id-attr env)])]
    (if singular?
      (if-let [id (or id-or-ids (get params (id-attr env)))]
        (data-layer/fetch-one (:data-layer env) env entity
                              id params attrs)
        (first (data-layer/fetch-all (:data-layer env) env entity
                                     params attrs)))
      (if id-or-ids
        (if (seq id-or-ids)
          (data-layer/fetch-many (:data-layer env) env entity
                                 id-or-ids params attrs)
          #{})
        (or (data-layer/fetch-all (:data-layer env) env entity params attrs)
            #{})))))

;;;; Query processing with data fetching

(defn resolve-keyword
  "Resolves a toplevel keyword query into data."
  [env opts parent-data z params]
  (let [key (qz/dispatch-key z)]
    (if-let [hook (get (:query-hooks opts) key)]
      (hook env parent-data z params)
      (when (qz/toplevel? z)
        (let [key (qz/dispatch-key z)
              entity (util/memoized-entity-from-query-key key)]
          (fetch-entity-data env entity
                             (singular-key? key)
                             nil [] params))))))

(defn resolve-ident
  "Resolves an ident query into data."
  [env opts z attrs params]
  (let [key (zip/node (qz/ident-name z))
        value (zip/node (qz/ident-value z))
        entity (util/memoized-entity-from-query-key key)]
    (fetch-entity-data env entity true
                       (when (not= value '_) value)
                       attrs params)))

(defn attrs-from-query-root
  "Extract attributes from a query root expression."
  [z]
  (loop [subz (qz/first-subquery z) attrs []]
    (let [new-attrs (conj attrs (qz/dispatch-key subz))]
      (if (qz/last-query? subz)
        new-attrs
        (recur (qz/next-query subz) new-attrs)))))

(defn resolve-toplevel-join
  "Resolves a toplevel join query (where no parent entity
   exists and our convention is to perform the join on
   all items of an entity (denoted by the query join
   source key)."
  [env opts join-source join-query params]
  (let [key (qz/dispatch-key join-source)]
    (if-let [hook (get (:query-hooks opts) key)]
      (hook env nil join-query params)
      (let [entity (util/memoized-entity-from-query-key key)
            attrs (attrs-from-query-root join-query)]
        (fetch-entity-data env entity (singular-key? key)
                           (get params (id-attr env))
                           attrs params)))))

(defn resolve-nested-join
  "Resolves a nested join query (starting from a parent entity
   map) into data."
  [env opts parent-data join-source join-query params]
  (let [key (qz/dispatch-key join-source)]
    (if-let [hook (get (:query-hooks opts) key)]
      (hook env parent-data join-query params)
      (if (query.util/backref-attr? key)
        (do
          (assert (not (nil? (get (zip/node parent-data) (ref-id-attr env))))
                  (str "Cannot query backref " key " without a " (ref-id-attr env)
                       " in parent data: " (zip/node parent-data)))
          (let [backref   (backref (zip/node parent-data) key)
                parent-id (get (zip/node parent-data) (ref-id-attr env))
                params    (assoc params [(:forward-attr backref) (ref-id-attr env)] parent-id)
                singular? (not (:many? backref))
                attrs     (attrs-from-query-root join-query)]
            (fetch-entity-data (assoc env :following-ref? true)
                               (:entity backref) singular? nil attrs params)))
        (let [entity-ref  (entity-ref (zip/node parent-data) key)
              singular?   (not (:many? entity-ref))
              ref-or-refs (get (zip/node parent-data) key)
              id-or-ids   (if (map? ref-or-refs)
                            (get ref-or-refs (ref-id-attr env))
                            (if singular?
                              ref-or-refs
                              (map #(get % (ref-id-attr env)) ref-or-refs)))
              attrs       (attrs-from-query-root join-query)]
          (cond
            (not entity-ref) ref-or-refs
            (and singular? (not id-or-ids)) nil
            (and (not singular?) (empty? id-or-ids)) #{}
            :else (fetch-entity-data (assoc env :following-ref? true)
                                     (:entity entity-ref) singular?
                                     id-or-ids attrs params)))))))

(defn resolve-join
  "Resolves a join query into data given a parent data node
   (an entity map) and optional parameters."
  [env opts parent-data z params]
  (let [join-source (qz/join-source z)
        join-query (qz/join-query z)]
    (cond
      (qz/ident-expr? join-source)
      (let [attrs (attrs-from-query-root join-query)]
        (resolve-ident env opts join-source attrs params))

      (keyword? (zip/node join-source))
      (if-not parent-data
        (resolve-toplevel-join env opts
                               join-source join-query
                               params)
        (resolve-nested-join env opts parent-data
                             join-source join-query
                             params)))))

(defn resolve-query-node
  "Resolves a query node z into data given a parent data node
   and optional parameters."
  [env opts parent-data z params]
  (cond
    (keyword? (zip/node z))
    (resolve-keyword env opts parent-data z params)

    (qz/ident-expr? z)
    (resolve-ident env opts z [] params)

    (qz/join-expr? z)
    (resolve-join env opts parent-data z params)

    :else
    parent-data))

(defn process
  "Processes an Om Next query given a data layer, an environment
   that is passed through to the data layer, and optional
   processing options."
  ([query data-layer env]
   (process query data-layer env {}))
  ([query data-layer env opts]
   (let [env' (assoc env :data-layer data-layer)]
     (zip/node (qz/process (qz/query-zipper query)
                           (partial resolve-query-node env' opts)
                           (dz/data-zipper))))))
