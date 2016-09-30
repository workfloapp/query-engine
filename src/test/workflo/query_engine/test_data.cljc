(ns workflo.query-engine.test-data
  (:require [datomic.api :as d]))

;;;; Accounts

(def accounts
  [{:db/id -1
    :account/name "Company A"
    :account/users [-10 -11]
    :account/libraries [-100 -101]}
   {:db/id -2
    :account/name "Company B"
    :account/users [-12]
    :account/libraries [-102]}])

;;;; Users

(def users
  [{:db/id -10
    :user/name "Joe"
    :user/email "joe@email.com"
    :user/password "abcdef"
    :user/account -1}
   {:db/id -11
    :user/name "Jeff"
    :user/email "jeff@email.com"
    :user/password "ghijkl"
    :user/account -1}
   {:db/id -12
    :user/name "Linda"
    :user/email "linda@email.com"
    :user/password "mnopqr"
    :user/account -2}])

;;;; Component libraries

(def component-libraries
  [{:db/id -100
    :component-library/name "Shop Components"
    :component-library/account -1
    :component-library/creator -10
    :component-library/components [-1000 -1001]}
   {:db/id -101
    :component-library/name "Social Network Components"
    :component-library/account -1
    :component-library/creator -10
    :component-library/components [-1002 -1003]}
   {:db/id -102
    :component-library/name "Event Site Components"
    :component-library/account -2
    :component-library/creator -11
    :component-library/components [-1004]}])

;;;; Components

(def components
  [{:db/id -1000
    :component/name "Shop Item"
    :component/account -1
    :component/creator -10
    :component/states [-10000 -10001]}
   {:db/id -1001
    :component/name "Cart Info"
    :component/account -1
    :component/creator -11}
   {:db/id -1002
    :component/name "Like Button"
    :component/account -1
    :component/creator -10
    :component/states [-10002 -10003]}
   {:db/id -1003
    :component/name "Dislike Button"
    :component/account -1
    :component/creator -11}
   {:db/id -1004
    :component/name "Seat Picker"
    :component/account -2
    :component/creator -12}])

;;;; Component states

(def component-states
  [{:db/id -10000
    :component-state/name "Shop Item Regular"
    :component-state/component -1000}
   {:db/id -10001
    :component-state/name "Shop Item Selected"
    :component-state/component -1000}
   {:db/id -10002
    :component-state/name "Like Button Regular"
    :component-state/component -1002}
   {:db/id -10003
    :component-state/name "Like Button Active"
    :component-state/component -1002}])

;;;; All test data

(def combined
  (concat accounts
          users
          component-libraries
          components
          component-states))

;;;; Datomic test data

(def datomic
  (letfn [(mktempid [id]
            (d/tempid :db.part/user id))
          (mktempids [ids]
            (map mktempid ids))]
    (concat (map (fn [account]
                   (-> account
                       (update :db/id mktempid)
                       (update :account/users mktempids)
                       (update :account/libraries mktempids)))
                 accounts)
            (map (fn [user]
                   (-> user
                       (update :db/id mktempid)
                       (update :user/account mktempid)))
                 users)
            (map (fn [library]
                   (-> library
                       (update :db/id mktempid)
                       (update :component-library/account mktempid)
                       (update :component-library/creator mktempid)
                       (update :component-library/components mktempids)))
                 component-libraries)
            (map (fn [component]
                   (-> component
                       (update :db/id mktempid)
                       (update :component/account mktempid)
                       (update :component/creator mktempid)
                       (update :component/states mktempids)))
                 components)
            (map (fn [state]
                   (-> state
                       (update :db/id mktempid)
                       (update :component-state/component mktempid)))
                 component-states))))