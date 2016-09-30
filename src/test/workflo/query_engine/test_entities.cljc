(ns workflo.query-engine.test-entities
  (:require [clojure.spec :as s]
            [workflo.macros.entity :refer [defentity]]
            [workflo.macros.specs.types :as t]))

;;;; Common

(s/def :db/id ::t/id)

;;;; Account

(s/def :account/name ::t/string)
(s/def :account/users (t/entity-ref 'user :many? true))
(s/def :account/libraries (t/entity-ref 'component-library :many? true))

(defentity account
  (spec (s/keys :req [:db/id
                      :account/name
                      :account/users]
                :opt [:account/libraries])))

;;;; User

(s/def :user/name ::t/string)
(s/def :user/email ::t/string)
(s/def :user/password ::t/string)
(s/def :user/account (t/entity-ref 'account))

(defentity user
  (spec (s/keys :req [:db/id
                      :user/name
                      :user/email
                      :user/password
                      :user/account])))

;;;; Component library

(s/def :component-library/name ::t/string)
(s/def :component-library/account (t/entity-ref 'account))
(s/def :component-library/creator (t/entity-ref 'user))
(s/def :component-library/components
  (t/entity-ref 'component :many? true))

(defentity component-library
  (spec (s/keys :req [:db/id
                      :component-library/name
                      :component-library/account
                      :component-library/creator]
                :opt [:component-library/components])))

;;;; Component

(s/def :component/name ::t/string)
(s/def :component/account (t/entity-ref 'account))
(s/def :component/creator (t/entity-ref 'user))
(s/def :component/states (t/entity-ref 'component-state :many? true))

(defentity component
  (spec (s/keys :req [:db/id
                      :component/name
                      :component/account
                      :component/creator]
                :opt [:component/states])))

;;;; Component state

(s/def :component-state/name ::t/string)
(s/def :component-state/component (t/entity-ref 'component))

(defentity component-state
  (spec (s/keys :req [:db/id
                      :component-state/name
                      :component-state/component])))