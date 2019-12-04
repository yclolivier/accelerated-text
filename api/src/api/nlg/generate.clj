(ns api.nlg.generate
  (:require [acc-text.nlg.utils.nlp :as nlp]
            [acc-text.nlg.core :as nlg]
            [api.nlg.context :as context]
            [api.nlg.parser :as parser]
            [api.utils :as utils]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [data.entities.data-files :as data-files]
            [data.entities.document-plan :as dp]
            [data.entities.result :as results]))

(s/def ::documentPlanId string?)
(s/def ::key string?)
(s/def ::dataId string?)
(s/def ::dataRow (s/map-of string? string?))
(s/def ::dataRows (s/map-of ::key ::dataRow))
(s/def ::readerFlagValues (s/map-of string? boolean?))
(s/def ::generate-req (s/keys :req-un [::documentPlanId ::readerFlagValues ::dataId]))
(s/def ::generate-bulk (s/keys :req-un [::documentPlanId ::readerFlagValues ::dataRows]))

(defn get-data [data-id]
  (doall (utils/csv-to-map (data-files/read-data-file-content "example-user" data-id))))

(defn get-reader-profiles [reader-model]
  (or
    (seq
      (reduce-kv (fn [acc k v]
                   (cond-> acc
                           (true? v) (conj k)))
                 []
                 reader-model))
    [:default]))

(defn generate-row [semantic-graph contexts [row-key data]]
  "Results in tuple (key, results), because datomic needs in this way"
  (row-key (->> contexts
                (mapcat #(nlg/generate-text semantic-graph % data))
                (map :text)
                (sort)
                (dedupe))))

(defn generation-process [document-plan rows reader-model]
  (try
    {:ready   true
     :results (let [semantic-graph (parser/document-plan->semantic-graph document-plan)
                    contexts (->> reader-model
                                  (get-reader-profiles)
                                  (map #(context/build-context semantic-graph %)))]
                (map #(generate-row semantic-graph contexts %) rows))}
    (catch Exception e
      (log/errorf "Failed to generate text: %s" (utils/get-stack-trace e))
      {:error true :ready true :message (.getMessage e)})))

(defn generate-request [{document-plan-id :documentPlanId data-id :dataId reader-model :readerFlagValues}]
  (let [result-id (utils/gen-uuid)
        {document-plan :documentPlan data-sample-row :dataSampleRow} (dp/get-document-plan document-plan-id)
        row (nth (get-data data-id) (or data-sample-row 0))]
    (results/store-status result-id {:ready false})
    (results/rewrite result-id (generation-process document-plan {:sample row} reader-model))
    {:status 200
     :body   {:resultId result-id}}))

(defn generate-bulk [{document-plan-id :documentPlanId reader-model :readerFlagValues rows :dataRow}]
  (let [result-id (utils/gen-uuid)]
    (results/store-status result-id {:ready false})
    {:status 200
     :body   {:resultId result-id}}))

(defn wrap-to-annotated-text
  [results]
  (map (fn [r]
         {:type        "ANNOTATED_TEXT"
          :id          (utils/gen-uuid)
          :annotations []
          :references  []
          :children    [{:type     "PARAGRAPH"
                         :id       (utils/gen-uuid)
                         :children (vec
                                    (for [sentence (nlp/split-into-sentences r)]
                                      {:type     "SENTENCE"
                                       :id       (utils/gen-uuid)
                                       :children (vec
                                                  (for [token (nlp/tokenize sentence)]
                                                    {:type (nlp/token-type token)
                                                     :id   (utils/gen-uuid)
                                                     :text token}))}))}]})
       results))

(defn read-result [{:keys [path-params]}]
  (let [request-id (:id path-params)]
    (try
      (if-let [{:keys [results ready updatedAt]} (results/fetch request-id)]
        {:status 200
         :body   {:offset     0
                  :totalCount (count (flatten results)) ;; Each key has N results. So flatten and count total
                  :ready      ready
                  :updatedAt  updatedAt
                  :variants   (wrap-to-annotated-text (flatten ;; Don't care about any bulk keys at the moment
                                                       (map second results)))}}
        {:status 404})
      (catch Exception e
        (log/errorf "Failed to read result with id `%s`: %s"
                    request-id (utils/get-stack-trace e))
        {:status 500
         :body   {:error   true
                  :message (.getMessage e)}}))))

(defn delete-result [{:keys [path-params]}]
  (let [request-id (:id path-params)]
    (try
      (if-let [item (results/fetch request-id)]
        (do
          (results/delete request-id)
          {:status 200
           :body   item})
        {:status 404})
      (catch Exception e
        (log/errorf "Failed to delete result with id `%s`: %s"
                    request-id (utils/get-stack-trace e))
        {:status 500
         :body   {:error   true
                  :message (.getMessage e)}}))))
