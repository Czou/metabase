(ns metabase.api.alert-test
  (:require [expectations :refer :all]
            [metabase
             [email-test :as et]
             [http-client :as http]
             [middleware :as middleware]]
            [metabase.models
             [card :refer [Card]]
             [pulse :as pulse :refer [Pulse]]
             [pulse-card :refer [PulseCard]]
             [pulse-channel :refer [PulseChannel]]
             [pulse-channel-recipient :refer [PulseChannelRecipient]]]
            [metabase.test
             [data :as data]
             [util :as tu]]
            [metabase.test.data
             [dataset-definitions :as defs]
             [users :refer :all]]
            [metabase.test.mock.util :refer [pulse-channel-defaults]]
            [toucan.db :as db]
            [toucan.util.test :as tt]))

(defn- user-details [user]
  (-> user
      (select-keys [:email :first_name :last_login :is_qbnewb :is_superuser :last_name :date_joined :common_name])
      (assoc :id true)))

(defn- pulse-card-details [card]
  (-> card
      (select-keys [:name :description :display])
      (update :display name)
      (assoc :id true)))

;; ## /api/alert/* AUTHENTICATION Tests
;; We assume that all endpoints for a given context are enforced by the same middleware, so we don't run the same
;; authentication test on every single individual endpoint

(expect (get middleware/response-unauthentic :body) (http/client :get 401 "alert"))
(expect (get middleware/response-unauthentic :body) (http/client :put 401 "alert/13"))


;; ## POST /api/alert

(expect
  {:errors {:alert_condition "value must be one of: `goal`, `rows`."}}
  ((user->client :rasta) :post 400 "alert" {:alert_condition "not rows"
                                            :card            "foobar"}))

(expect
  {:errors {:alert_first_only "value must be a boolean."}}
  ((user->client :rasta) :post 400 "alert" {:alert_condition   "rows"}))

(expect
  {:errors {:card "value must be a map."}}
  ((user->client :rasta) :post 400 "alert" {:alert_condition   "rows"
                                            :alert_first_only  false}))

(expect
  {:errors {:channels "value must be an array. Each value must be a map. The array cannot be empty."}}
  ((user->client :rasta) :post 400 "alert" {:alert_condition   "rows"
                                            :alert_first_only  false
                                            :card              {:id 100}}))

(expect
  {:errors {:channels "value must be an array. Each value must be a map. The array cannot be empty."}}
  ((user->client :rasta) :post 400 "alert" {:alert_condition   "rows"
                                            :alert_first_only  false
                                            :card              {:id 100}
                                            :channels          "foobar"}))

(expect
  {:errors {:channels "value must be an array. Each value must be a map. The array cannot be empty."}}
  ((user->client :rasta) :post 400 "alert" {:alert_condition   "rows"
                                            :alert_first_only  false
                                            :card              {:id 100}
                                            :channels          ["abc"]}))

(defmacro ^:private with-test-email [& body]
  `(tu/with-temporary-setting-values [~'site-url "https://metabase.com/testmb"]
     (et/with-fake-inbox
       ~@body)))

(defmacro ^:private create-alert-test-setup
  "Macro that will cleanup any created pulses and setups a fake-inbox to validate emails are sent for new alerts"
  [& body]
  `(tu/with-model-cleanup [Pulse]
     (with-test-email
       ~@body)))

(tt/expect-with-temp [Card [card1 {:name "My question"}]]
  [{:id                true
    :name              nil
    :creator_id        true
    :creator           (user-details (fetch-user :rasta))
    :created_at        true
    :updated_at        true
    :card              (pulse-card-details card1)
    :alert_condition   "rows"
    :alert_first_only  false
    :alert_above_goal  nil
    :channels          [(merge pulse-channel-defaults
                               {:channel_type  "email"
                                :schedule_type "daily"
                                :schedule_hour 12
                                :recipients    []
                                :updated_at    true,
                                :pulse_id      true,
                                :id            true,
                                :created_at    true})]
    :skip_if_empty     true}
   {"rasta@metabase.com"
    [{:from "notifications@metabase.com",
      :to ["rasta@metabase.com"],
      :subject "You setup an alert",
      :body {"https://metabase.com/testmb" true,
             "has any results" true,
             "My question" true}}]}]
  (create-alert-test-setup
   [(tu/boolean-ids-and-timestamps ((user->client :rasta) :post 200 "alert"
                                    {:card              {:id (:id card1)}
                                     :alert_condition   "rows"
                                     :alert_first_only  false
                                     :channels          [{:enabled       true
                                                          :channel_type  "email"
                                                          :schedule_type "daily"
                                                          :schedule_hour 12
                                                          :schedule_day  nil
                                                          :recipients    []}]}))
    (et/regex-email-bodies #"https://metabase.com/testmb"
                           #"has any results"
                           #"My question")]))

(tt/expect-with-temp [Card [card1 {:name "My question"
                                   :display "line"}]]
  {"rasta@metabase.com"
   [{:from "notifications@metabase.com",
     :to ["rasta@metabase.com"],
     :subject "You setup an alert",
     :body {"https://metabase.com/testmb" true,
            "goes below its goal" true,
            "My question" true}}]}
  (create-alert-test-setup
   ((user->client :rasta) :post 200 "alert"
    {:card              {:id (:id card1)}
     :alert_condition   "goal"
     :alert_above_goal  false
     :alert_first_only  false
     :channels          [{:enabled       true
                          :channel_type  "email"
                          :schedule_type "daily"
                          :schedule_hour 12
                          :schedule_day  nil
                          :recipients    []}]})
   (et/regex-email-bodies #"https://metabase.com/testmb"
                          #"goes below its goal"
                          #"My question")))

(tt/expect-with-temp [Card [card1 {:name "My question"
                                   :display "bar"}]]
  {"rasta@metabase.com"
   [{:from "notifications@metabase.com",
     :to ["rasta@metabase.com"],
     :subject "You setup an alert",
     :body {"https://metabase.com/testmb" true,
            "meets its goal" true,
            "My question" true}}]}
  (create-alert-test-setup
   ((user->client :rasta) :post 200 "alert"
    {:card              {:id (:id card1)}
     :alert_condition   "goal"
     :alert_above_goal  true
     :alert_first_only  false
     :channels          [{:enabled       true
                          :channel_type  "email"
                          :schedule_type "daily"
                          :schedule_hour 12
                          :schedule_day  nil
                          :recipients    []}]})
   (et/regex-email-bodies #"https://metabase.com/testmb"
                          #"meets its goal"
                          #"My question")))

;; ## PUT /api/alert

(expect
  {:errors {:alert_condition "value must be one of: `goal`, `rows`."}}
  ((user->client :rasta) :put 400 "alert/1" {}))

(expect
  {:errors {:alert_condition "value must be one of: `goal`, `rows`."}}
  ((user->client :rasta) :put 400 "alert/1" {:alert_condition "not rows"}))

(expect
  {:errors {:alert_first_only "value must be a boolean."}}
  ((user->client :rasta) :put 400 "alert/1" {:alert_condition   "rows"}))
(expect
  {:errors {:card "value must be a map."}}
  ((user->client :rasta) :put 400 "alert/1" {:alert_condition   "rows"
                                             :alert_first_only  false}))

(expect
  {:errors {:card "value must be a map."}}
  ((user->client :rasta) :put 400 "alert/1" {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :card              "foobar"}))

(expect
  {:errors {:channels "value must be an array. Each value must be a map. The array cannot be empty."}}
  ((user->client :rasta) :put 400 "alert/1" {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :card              {:id 100}}))

(expect
  {:errors {:channels "value must be an array. Each value must be a map. The array cannot be empty."}}
  ((user->client :rasta) :put 400 "alert/1" {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :card              {:id 100}
                                             :channels          "foobar"}))

(expect
  {:errors {:channels "value must be an array. Each value must be a map. The array cannot be empty."}}
  ((user->client :rasta) :put 400 "alert/1" {:name              "abc"
                                             :alert_condition   "rows"
                                             :alert_first_only  false
                                             :card              {:id 100}
                                             :channels          ["abc"]}))

;; Non-admin users can update alerts they created
(tt/expect-with-temp [Pulse [{pulse-id :id} {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :creator_id        (user->id :rasta)}]
                      Card  [{card-id :id :as card}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                      PulseChannelRecipient [{pcr-id :id}  {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id}]]
  {:id                true
   :name              nil
   :creator_id        true
   :creator           (user-details (fetch-user :rasta))
   :created_at        true
   :updated_at        true
   :alert_condition   "rows"
   :alert_first_only  false
   :alert_above_goal  nil
   :card              (pulse-card-details card)
   :channels          [(merge pulse-channel-defaults
                              {:channel_type  "email"
                               :schedule_type "hourly"
                               :recipients    [(dissoc (user-details (fetch-user :rasta))
                                                       :last_login :is_qbnewb :is_superuser :date_joined)]
                               :updated_at    true,
                               :pulse_id      true,
                               :id            true,
                               :created_at    true})]
   :skip_if_empty     true}
  (tu/with-model-cleanup [Pulse]
    (tu/boolean-ids-and-timestamps ((user->client :rasta) :put 200 (format "alert/%d" pulse-id)
                                    {:name              "Updated Pulse"
                                     :card              {:id (:id card)}
                                     :alert_condition   "rows"
                                     :alert_first_only  false
                                     :channels          [{:id            pc-id
                                                          :enabled       true
                                                          :channel_type  "email"
                                                          :schedule_type "hourly"
                                                          :schedule_hour 12
                                                          :schedule_day  "mon"
                                                          :recipients    [(fetch-user :rasta)]
                                                          :details       {}}]
                                     :skip_if_empty     false}))))

;; Admin users can update any alert
(tt/expect-with-temp [Pulse [{pulse-id :id} {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :creator_id        (user->id :rasta)
                                             :name              "Original Alert Name"}]
                      Card  [{card-id :id :as card}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                      PulseChannelRecipient [{pcr-id :id}  {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id}]]
  {:id                true
   :name              nil
   :creator_id        true
   :creator           (user-details (fetch-user :rasta))
   :created_at        true
   :updated_at        true
   :alert_condition   "rows"
   :alert_first_only  false
   :alert_above_goal  nil
   :card              (pulse-card-details card)
   :channels          [(merge pulse-channel-defaults
                              {:channel_type  "email"
                               :schedule_type "hourly"
                               :recipients    [(dissoc (user-details (fetch-user :rasta))
                                                       :last_login :is_qbnewb :is_superuser :date_joined)]
                               :updated_at    true,
                               :pulse_id      true,
                               :id            true,
                               :created_at    true})]
   :skip_if_empty     true}
  (tu/with-model-cleanup [Pulse]
    (tu/boolean-ids-and-timestamps ((user->client :crowberto) :put 200 (format "alert/%d" pulse-id)
                                    {:card              {:id (:id card)}
                                     :alert_condition   "rows"
                                     :alert_first_only  false
                                     :channels          [{:id            pc-id
                                                          :enabled       true
                                                          :channel_type  "email"
                                                          :schedule_type "hourly"
                                                          :schedule_hour 12
                                                          :schedule_day  "mon"
                                                          :recipients    [(fetch-user :rasta)]
                                                          :details       {}}]
                                     :skip_if_empty     false}))))

(defn- setify-recipient-emails [results]
  (update results :channels (fn [channels]
                              (map #(update % :recipients set) channels))))

;; Admin users can add a recipieint, that recipient should be notified
(tt/expect-with-temp [Pulse [{pulse-id :id} {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :creator_id        (user->id :rasta)
                                             :name              "Original Alert Name"}]
                      Card  [{card-id :id :as card}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                      PulseChannelRecipient [{pcr-id :id}  {:user_id          (user->id :crowberto)
                                                            :pulse_channel_id pc-id}]]
  [{:id                true
    :name              nil
    :creator_id        true
    :creator           (user-details (fetch-user :rasta))
    :created_at        true
    :updated_at        true
    :alert_condition   "rows"
    :alert_first_only  false
    :alert_above_goal  nil
    :card              (pulse-card-details card)
    :channels          [(merge pulse-channel-defaults
                               {:channel_type  "email"
                                :schedule_type "hourly"
                                :recipients    (set (map (comp #(dissoc % :last_login :is_qbnewb :is_superuser :date_joined)
                                                               user-details
                                                               fetch-user)
                                                         [:crowberto :rasta]))
                                :updated_at    true,
                                :pulse_id      true,
                                :id            true,
                                :created_at    true})]
    :skip_if_empty     true}
   {"rasta@metabase.com"
    [{:from "notifications@metabase.com",
      :to ["rasta@metabase.com"],
      :subject "Crowberto Corv added you to an alert",
      :body {"https://metabase.com/testmb" true, "now getting alerts" true}}]}]
  (tu/with-model-cleanup [Pulse]
    (with-test-email
      [(-> ((user->client :crowberto) :put 200 (format "alert/%d" pulse-id)
                                       {:card              {:id (:id card)}
                                        :alert_condition   "rows"
                                        :alert_first_only  false
                                        :channels          [{:id            pc-id
                                                             :enabled       true
                                                             :channel_type  "email"
                                                             :schedule_type "hourly"
                                                             :schedule_hour 12
                                                             :schedule_day  "mon"
                                                             :recipients    [(fetch-user :crowberto)
                                                                             (fetch-user :rasta)]
                                                             :details       {}}]
                                        :skip_if_empty     false})
           tu/boolean-ids-and-timestamps
           setify-recipient-emails)
       (et/regex-email-bodies #"https://metabase.com/testmb"
                              #"now getting alerts")])))

;; Admin users can remove a recipieint, that recipient should be notified
(tt/expect-with-temp [Pulse [{pulse-id :id} {:alert_condition  "rows"
                                             :alert_first_only false
                                             :creator_id       (user->id :rasta)
                                             :name             "Original Alert Name"}]
                      Card  [{card-id :id :as card}]
                      PulseCard             [_              {:pulse_id pulse-id
                                                             :card_id  card-id
                                                             :position 0}]
                      PulseChannel          [{pc-id :id}    {:pulse_id pulse-id}]
                      PulseChannelRecipient [{pcr-id-1 :id} {:user_id          (user->id :crowberto)
                                                             :pulse_channel_id pc-id}]
                      PulseChannelRecipient [{pcr-id-2 :id} {:user_id         (user->id :rasta)
                                                             :pulse_channel_id pc-id}]]
  [{:id               true
    :name             nil
    :creator_id       true
    :creator          (user-details (fetch-user :rasta))
    :created_at       true
    :updated_at       true
    :alert_condition  "rows"
    :alert_first_only false
    :alert_above_goal nil
    :card             (pulse-card-details card)
    :channels         [(merge pulse-channel-defaults
                              {:channel_type  "email"
                               :schedule_type "hourly"
                               :recipients    (mapv (comp #(dissoc % :last_login :is_qbnewb :is_superuser :date_joined)
                                                          user-details
                                                          fetch-user)
                                                    [:crowberto])
                               :updated_at    true,
                               :pulse_id      true,
                               :id            true,
                               :created_at    true})]
    :skip_if_empty    true}
   {"rasta@metabase.com"
    [{:from    "notifications@metabase.com",
      :to      ["rasta@metabase.com"],
      :subject "You’ve been unsubscribed from an alert",
      :body    {"https://metabase.com/testmb" true,
                "letting you know that Crowberto Corv" true}}]}]
  (tu/with-model-cleanup [Pulse]
    (with-test-email
      [(tu/boolean-ids-and-timestamps ((user->client :crowberto) :put 200 (format "alert/%d" pulse-id)
                                       {:card             {:id (:id card)}
                                        :alert_condition  "rows"
                                        :alert_first_only false
                                        :channels         [{:id            pc-id
                                                            :enabled       true
                                                            :channel_type  "email"
                                                            :schedule_type "hourly"
                                                            :schedule_hour 12
                                                            :schedule_day  "mon"
                                                            :recipients    [(fetch-user :crowberto)]
                                                            :details       {}}]
                                        :skip_if_empty    false}))
       (et/regex-email-bodies #"https://metabase.com/testmb"
                              #"letting you know that Crowberto Corv")])))

;; Non-admin users can't edit alerts they didn't create
(tt/expect-with-temp [Pulse [{pulse-id :id} {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :creator_id        (user->id :crowberto)
                                             :name              "Original Alert Name"}]
                      Card  [{card-id :id :as card}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                      PulseChannelRecipient [{pcr-id :id}  {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id}]]
  "You don't have permissions to do that."
  (tu/with-model-cleanup [Pulse]
    (tu/boolean-ids-and-timestamps ((user->client :rasta) :put 403 (format "alert/%d" pulse-id)
                                    {:card              {:id (:id card)}
                                     :alert_condition   "rows"
                                     :alert_first_only  false
                                     :channels          [{:id            pc-id
                                                          :enabled       true
                                                          :channel_type  "email"
                                                          :schedule_type "hourly"
                                                          :schedule_hour 12
                                                          :schedule_day  "mon"
                                                          :recipients    [(fetch-user :rasta)]
                                                          :details       {}}]
                                     :skip_if_empty     false}))))

;; Non-admin users can't edit alerts if they're not in the recipient list
(tt/expect-with-temp [Pulse [{pulse-id :id} {:alert_condition   "rows"
                                             :alert_first_only  false
                                             :creator_id        (user->id :rasta)
                                             :name              "Original Alert Name"}]
                      Card  [{card-id :id :as card}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                      PulseChannelRecipient [{pcr-id :id}  {:user_id          (user->id :crowberto)
                                                            :pulse_channel_id pc-id}]]
  "You don't have permissions to do that."
  (tu/with-model-cleanup [Pulse]
    (tu/boolean-ids-and-timestamps ((user->client :rasta) :put 403 (format "alert/%d" pulse-id)
                                    {:card              {:id (:id card)}
                                     :alert_condition   "rows"
                                     :alert_first_only  false
                                     :channels          [{:id            pc-id
                                                          :enabled       true
                                                          :channel_type  "email"
                                                          :schedule_type "hourly"
                                                          :schedule_hour 12
                                                          :schedule_day  "mon"
                                                          :recipients    [(fetch-user :rasta)]
                                                          :details       {}}]
                                     :skip_if_empty     false}))))

(defn- basic-alert-query []
  {:name "Foo"
   :dataset_query {:database (data/id)
                   :type     :query
                   :query {:source_table (data/id :checkins)
                           :aggregation [["count"]]
                           :breakout [["datetime-field" (data/id :checkins :date) "hour"]]}}})

;; Basic test covering the /alert/question/:id call for a user
(expect
  [{:alert_condition "rows",
    :id true
    :name nil
    :creator_id true
    :updated_at true,
    :alert_first_only false,
    :card {:name "Foo", :description nil, :display "table", :id true},
    :skip_if_empty false,
    :created_at true,
    :alert_above_goal true
    :creator (user-details (fetch-user :rasta))
    :read_only false
    :channels
    [{:schedule_type "daily",
      :schedule_hour 15,
      :channel_type "email",
      :schedule_frame nil,
      :recipients [{:id true, :email "rasta@metabase.com", :first_name "Rasta", :last_name "Toucan", :common_name "Rasta Toucan"}],
      :schedule_day nil,
      :enabled true
      :updated_at true,
      :pulse_id true,
      :id true,
      :created_at true}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                          :alert_first_only  false
                                                          :alert_above_goal  true
                                                          :name              nil}]
                    PulseCard             [_             {:pulse_id pulse-id
                                                          :card_id  card-id
                                                          :position 0}]
                    PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                          :pulse_channel_id pc-id}]]
      (tu/boolean-ids-and-timestamps ((user->client :rasta) :get 200 (format "alert/question/%d" card-id))))))

;; Non-admin users shouldn't see alerts they created if they're no longer recipients
(expect
  [1 0]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                          :alert_first_only  false
                                                          :alert_above_goal  true
                                                          :creator_id        (user->id :rasta)}]
                    PulseCard             [_             {:pulse_id pulse-id
                                                          :card_id  card-id
                                                          :position 0}]
                    PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                    PulseChannelRecipient [{pcr-id :id}  {:user_id          (user->id :rasta)
                                                          :pulse_channel_id pc-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :crowberto)
                                                          :pulse_channel_id pc-id}]]

      [(count (tu/boolean-ids-and-timestamps ((user->client :rasta) :get 200 (format "alert/question/%d" card-id))))
       (do
         (db/delete! PulseChannelRecipient :id pcr-id)
         (count (tu/boolean-ids-and-timestamps ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))))])))

;; Non-admin users should not see others alerts, admins see all alerts
(expect
  [1 2]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id-1 :id} {:alert_condition   "rows"
                                                            :alert_first_only  false
                                                            :alert_above_goal  false
                                                            :creator_id        (user->id :rasta)}]
                    PulseCard             [_               {:pulse_id pulse-id-1
                                                            :card_id  card-id
                                                            :position 0}]
                    PulseChannel          [{pc-id-1 :id}   {:pulse_id pulse-id-1}]
                    PulseChannelRecipient [_               {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id-1}]
                    ;; A separate admin created alert
                    Pulse                [{pulse-id-2 :id} {:alert_condition   "rows"
                                                            :alert_first_only  false
                                                            :alert_above_goal  false
                                                            :creator_id        (user->id :crowberto)}]
                    PulseCard             [_               {:pulse_id pulse-id-2
                                                            :card_id  card-id
                                                            :position 0}]
                    PulseChannel          [{pc-id-2 :id}   {:pulse_id pulse-id-2}]
                    PulseChannelRecipient [_               {:user_id          (user->id :crowberto)
                                                            :pulse_channel_id pc-id-2}]
                    PulseChannel          [{pc-id-3 :id}   {:pulse_id     pulse-id-2
                                                            :channel_type "slack"}]]
      [(count (tu/boolean-ids-and-timestamps ((user->client :rasta) :get 200 (format "alert/question/%d" card-id))))
       (count (tu/boolean-ids-and-timestamps ((user->client :crowberto) :get 200 (format "alert/question/%d" card-id))))])))

(defn- recipient-emails [results]
  (->> results
       first
       :channels
       first
       :recipients
       (map :email)
       set))

(expect
  "Admin user are not allowed to unsubscribe from alerts"
  ((user->client :crowberto) :put 400 (format "alert/1/unsubscribe")))

;; Alert has two recipients, remove one
(expect
  [#{"crowberto@metabase.com" "rasta@metabase.com"}
   #{"crowberto@metabase.com"}
   {"rasta@metabase.com" [{:from "notifications@metabase.com",
                           :to ["rasta@metabase.com"],
                           :subject "You unsubscribed from an alert",
                           :body {"https://metabase.com/testmb" true,
                                  "Foo" true}}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (with-test-email
      (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                      Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                            :alert_first_only  false}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                      PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id}]
                      PulseChannelRecipient [_             {:user_id          (user->id :crowberto)
                                                            :pulse_channel_id pc-id}]]

        [(recipient-emails ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         (do
           ((user->client :rasta) :put 204 (format "alert/%d/unsubscribe" pulse-id))
           (recipient-emails ((user->client :crowberto) :get 200 (format "alert/question/%d" card-id))))
         (et/regex-email-bodies #"https://metabase.com/testmb"
                                #"Foo")]))))

;; Alert should be deleted if the creator unsubscribes and there's no one left
(expect
  [1
   0
   {"rasta@metabase.com" [{:from    "notifications@metabase.com",
                           :to      ["rasta@metabase.com"],
                           :subject "You unsubscribed from an alert",
                           :body    {"https://metabase.com/testmb" true,
                                     "Foo"                         true}}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (with-test-email
      (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                      Pulse                [{pulse-id :id} {:alert_condition  "rows"
                                                            :alert_first_only false
                                                            :creator_id       (user->id :rasta)}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id :id}   {:pulse_id     pulse-id
                                                            :channel_type :email}]
                      PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id}]]

        [(count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         (do
           ((user->client :rasta) :put 204 (format "alert/%d/unsubscribe" pulse-id))
           (count ((user->client :crowberto) :get 200 (format "alert/question/%d" card-id))))
         (et/regex-email-bodies #"https://metabase.com/testmb"
                                #"Foo")]))))

;; Alert should not be deleted if there is a slack channel
(expect
  [1
   1 ;<-- Alert should not be deleted
   {"rasta@metabase.com" [{:from    "notifications@metabase.com",
                           :to      ["rasta@metabase.com"],
                           :subject "You unsubscribed from an alert",
                           :body    {"https://metabase.com/testmb" true,
                                     "Foo"                         true}}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (with-test-email
      (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                      Pulse                [{pulse-id :id} {:alert_condition  "rows"
                                                            :alert_first_only false
                                                            :creator_id       (user->id :rasta)}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id-1 :id} {:pulse_id     pulse-id
                                                            :channel_type :email}]
                      PulseChannel          [{pc-id-2 :id} {:pulse_id     pulse-id
                                                            :channel_type :slack}]
                      PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id-1}]]

        [(count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         (do
           ((user->client :rasta) :put 204 (format "alert/%d/unsubscribe" pulse-id))
           (count ((user->client :crowberto) :get 200 (format "alert/question/%d" card-id))))
         (et/regex-email-bodies #"https://metabase.com/testmb"
                                #"Foo")]))))

;; Alert should not be deleted if the unsubscriber isn't the creator
(expect
  [1
   1 ;<-- Alert should not be deleted
   {"rasta@metabase.com" [{:from    "notifications@metabase.com",
                           :to      ["rasta@metabase.com"],
                           :subject "You unsubscribed from an alert",
                           :body    {"https://metabase.com/testmb" true,
                                     "Foo"                         true}}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (with-test-email
      (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                      Pulse                [{pulse-id :id} {:alert_condition  "rows"
                                                            :alert_first_only false
                                                            :creator_id       (user->id :crowberto)}]
                      PulseCard             [_             {:pulse_id pulse-id
                                                            :card_id  card-id
                                                            :position 0}]
                      PulseChannel          [{pc-id-1 :id} {:pulse_id     pulse-id
                                                            :channel_type :email}]
                      PulseChannel          [{pc-id-2 :id} {:pulse_id     pulse-id
                                                            :channel_type :slack}]
                      PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                            :pulse_channel_id pc-id-1}]]

        [(count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         (do
           ((user->client :rasta) :put 204 (format "alert/%d/unsubscribe" pulse-id))
           (count ((user->client :crowberto) :get 200 (format "alert/question/%d" card-id))))
         (et/regex-email-bodies #"https://metabase.com/testmb"
                                #"Foo")]))))

;; Only admins can delete an alert
(expect
  [1 "You don't have permissions to do that."]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                          :alert_first_only  false
                                                          :creator_id        (user->id :rasta)}]
                    PulseCard             [_             {:pulse_id pulse-id
                                                          :card_id  card-id
                                                          :position 0}]
                    PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                          :pulse_channel_id pc-id}]]

      [(count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
       ((user->client :rasta) :delete 403 (format "alert/%d" pulse-id))])))

;; Testing a user can't delete an admin's alert
(expect
  [1 nil 0]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                          :alert_first_only  false
                                                          :creator_id        (user->id :crowberto)}]
                    PulseCard             [_             {:pulse_id pulse-id
                                                          :card_id  card-id
                                                          :position 0}]
                    PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                          :pulse_channel_id pc-id}]]
      (let [original-alert-response ((user->client :crowberto) :get 200 (format "alert/question/%d" card-id))]

        ;; A user can't delete an admin's alert
        ((user->client :rasta) :delete 403 (format "alert/%d" pulse-id))

        [(count original-alert-response)
         ((user->client :crowberto) :delete 204 (format "alert/%d" pulse-id))
         (count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))]))))

;; An admin can delete a user's alert
(expect
  [1 nil 0
   {"rasta@metabase.com"
    [{:from "notifications@metabase.com",
      :to ["rasta@metabase.com"],
      :subject "Crowberto Corv deleted an alert you created",
      :body {"Crowberto Corv deleted an alert" true}}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                          :alert_first_only  false
                                                          :creator_id        (user->id :rasta)}]
                    PulseCard             [_             {:pulse_id pulse-id
                                                          :card_id  card-id
                                                          :position 0}]
                    PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                          :pulse_channel_id pc-id}]]
      (with-test-email
        [(count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         ((user->client :crowberto) :delete 204 (format "alert/%d" pulse-id))
         (count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         (et/regex-email-bodies #"Crowberto Corv deleted an alert")]))))

;; A deleted alert should notify the creator and any recipients
(expect
  [1 nil 0
   {"rasta@metabase.com"
    [{:from "notifications@metabase.com",
      :to ["rasta@metabase.com"],
      :subject "Crowberto Corv deleted an alert you created",
      :body {"Crowberto Corv deleted an alert" true
             "Crowberto Corv unsubscribed you from alerts" false}}]
    "lucky@metabase.com"
    [{:from "notifications@metabase.com",
      :to ["lucky@metabase.com"],
      :subject "You’ve been unsubscribed from an alert",
      :body {"Crowberto Corv deleted an alert" false
             "Crowberto Corv unsubscribed you from alerts" true}}]}]
  (data/with-db (data/get-or-create-database! defs/test-data)
    (tt/with-temp* [Card                 [{card-id :id}  (basic-alert-query)]
                    Pulse                [{pulse-id :id} {:alert_condition   "rows"
                                                          :alert_first_only  false
                                                          :creator_id        (user->id :rasta)}]
                    PulseCard             [_             {:pulse_id pulse-id
                                                          :card_id  card-id
                                                          :position 0}]
                    PulseChannel          [{pc-id :id}   {:pulse_id pulse-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :rasta)
                                                          :pulse_channel_id pc-id}]
                    PulseChannelRecipient [_             {:user_id          (user->id :lucky)
                                                          :pulse_channel_id pc-id}]]
      (with-test-email
        [(count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         ((user->client :crowberto) :delete 204 (format "alert/%d" pulse-id))
         (count ((user->client :rasta) :get 200 (format "alert/question/%d" card-id)))
         (et/regex-email-bodies #"Crowberto Corv deleted an alert"
                                #"Crowberto Corv unsubscribed you from alerts")]))))
