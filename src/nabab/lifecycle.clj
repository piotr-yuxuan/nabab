;; Generated from litterate programming file `README.org`.
(ns nabab.lifecycle
  "Manage lifecycle of a nabab graph"
  (:require [nabab.specs :as specs]
            [clojure.core.async :refer [close! chan go go-loop pub sub <! >! pipe pipeline]]
            [clojure.spec.alpha :as spec]))

(defn topics->subscriptions!
  "Subscribe a `subscriber` channel to topics of a `publication`."
  [publication subscriber topics-to-subscribe]
  (doseq [topic topics-to-subscribe]
    (sub publication topic subscriber)))

(defn transducer-pipeline
  [description subscriber transducer]
  (pipeline (:nabab/fixed-buffer-size description)
            (:nabab/publisher description)
            transducer
            subscriber))

(defn ->transition-block
  [description transition-name transition]
  (let [subscriber (or (:nabab/subscriber transition)
                       (chan (:nabab/fixed-buffer-size description)))]
    (topics->subscriptions! (:nabab/publication description)
                            subscriber
                            (:nabab/subscribed-topics transition))
    (merge {:nabab/subscriber subscriber
            :nabab/transition-name transition-name
            :nabab/block (or (:nabab/block transition)
                             (condp #(get %2 %1) transition
                               :nabab/block-fn :>> #(% description subscriber)
                               :nabab/pipeline-transducer :>> #(transducer-pipeline description subscriber %)
                               (comment "else, will invalidate spec")))}
           transition)))

(defn implement-transitions [description]
  (reduce (fn [acc [transition-name transition]]
            (assoc-in acc
                      [:nabab/transitions transition-name]
                      (->transition-block acc transition-name transition)))
          description
          (:nabab/transitions description)))

(defn bootstrap!
  [description]
  (let [publisher (or (:nabab/publisher description)
                      (chan (:nabab/fixed-buffer-size description)))
        publication (or (:nabab/publication description)
                        (pub publisher (:nabab/dispatch-ifn description)))
        description (-> description
                        (assoc :nabab/publisher publisher)
                        (assoc :nabab/publication publication)
                        implement-transitions
                        doall)]
    (if (spec/valid? ::specs/nabab-graph description)
      (throw (ex-info "description doesn't give a valid nabab graph"
                      {:spec/explanation (spec/explain ::specs/nabab-graph description)}))
      description)))

(defn shutdown!
  [implementation]
  (close! (:nabab/publisher implementation)))
