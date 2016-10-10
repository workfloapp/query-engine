(ns workflo.query-engine.data-layer.datascript
  (:require [datascript.core :as d]
            [workflo.macros.entity.schema :as es]
            [workflo.query-engine.cache :as c]
            [workflo.query-engine.data-layer :refer [DataLayer]]
            [workflo.query-engine.data-layer.util :as util]))

(defn authorized?
  [db entity e viewer]
  (if-let [auth-rules (some-> (:auth entity) (apply [{}]))]
    (let [results (mapv (fn [auth-rule]
                          (d/q '[:find ?e
                                 :in $ ?e ?viewer %
                                 :where (auth ?e ?viewer)]
                               db e viewer [auth-rule]))
                        auth-rules)]
      (not (empty? (apply concat results))))
    true))

(defn- fetch-entity
  [{:keys [db cache viewer]} entity id]
  (letfn [(fetch* [id]
            (d/q '[:find (pull ?e [*]) .
                   :in $ ?e ?entity ?viewer ?authorized
                   :where [(?authorized $ ?entity ?e ?viewer)]]
                 db id entity viewer authorized?))]
    (if cache
      (c/get-one cache id fetch*)
      (fetch* id))))

(defn- fetch-entities
  ([{:keys [db viewer] :as env} entity]
   (let [ids (d/q '[:find [?e ...]
                    :in $ [?a ...] ?entity ?viewer ?authorized
                    :where [?e ?a]
                           [(?authorized $ ?entity ?e ?viewer)]]
                  db (es/required-keys entity) entity viewer
                  authorized?)]
     (fetch-entities env entity ids)))
  ([{:keys [db cache viewer]} entity ids]
   (letfn [(fetch* [ids]
             (d/q '[:find [(pull ?e [*]) ...]
                    :in $ [?e ...] ?entity ?viewer ?authorized
                    :where [(?authorized $ ?entity ?e ?viewer)]]
                  db ids entity viewer authorized?))]
     (if cache
       (c/get-many cache ids
                   (fn [missing-ids]
                     (into {} (map (fn [e] [(:db/id e) e]))
                           (fetch* missing-ids))))
       (fetch* ids)))))

(defn data-layer []
  (reify DataLayer
    (fetch-one [_ env entity id params attrs]
      (some-> (fetch-entity env entity id)
              (util/filter-entity entity params)
              (util/select-attrs attrs)
              (with-meta {:entity entity})))
    (fetch-many [_ env entity ids params attrs]
      (some->> (fetch-entities env entity ids)
               (keep #(util/filter-entity % entity params))
               (map #(util/select-attrs % attrs))
               (map #(with-meta % {:entity entity}))
               (util/sort params)
               (util/paginate params)))
    (fetch-all [_ env entity params attrs]
      (some->> (fetch-entities env entity)
               (keep #(util/filter-entity % entity params))
               (map #(util/select-attrs % attrs))
               (map #(with-meta % {:entity entity}))
               (util/sort params)
               (util/paginate params)))))
