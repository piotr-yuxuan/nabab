(ns nabab.nabab-test
  (:require [nabab.lifecycle :as nabab]
            [nabab.viz :as viz]
            [clojure.core.async :refer [close! chan go go-loop pub sub <! >! >!! <!! pipe pipeline alts!! timeout]]
            [clojure.test :refer :all]))

(def test-description
  {:nabab/fixed-buffer-size 2
   :context/addition-value 2
   :context/addition-timeout 80
   :nabab/dispatch-ifn (fn [message]
                         (:message/topic message))
   :nabab/transitions {:input
                       {:nabab/subscribed-topics #{:topic/input}
                        :nabab/doc-published-topics {:topic/input #{::1}}
                        :nabab/pipeline-transducer (map #(-> %
                                                             (update :message/content inc)
                                                             (assoc :message/topic ::1)))}

                       :simple-processing
                       {:nabab/subscribed-topics #{::1}
                        :nabab/doc-published-topics {::1 #{::2}}
                        :nabab/pipeline-transducer (map #(-> %
                                                             (update :message/content (partial * 2))
                                                             (assoc :message/topic ::2)))}

                       :fork
                       {:nabab/subscribed-topics #{::2}
                        :nabab/doc-published-topics {::2 #{::3 :topic/output}}
                        :nabab/pipeline-transducer (mapcat (juxt #(assoc % :message/topic :topic/output)
                                                                 #(-> %
                                                                      (assoc :message/topic ::3)
                                                                      (update :message/content (partial * 2)))
                                                                 #(-> %
                                                                      (assoc :message/topic ::3)
                                                                      (update :message/content (partial * 2)))))}

                       :output
                       {:nabab/subscribed-topics #{::3}
                        :nabab/doc-published-topics {::3 #{:topic/output}}
                        :nabab/block-fn (fn [description subscriber]
                                          (go-loop []
                                            (when-let [message (<! subscriber)]
                                              (let [addition-value (:context/addition-value description)
                                                    addition-timeout (:context/addition-timeout description)
                                                    publisher (:nabab/publisher description)]
                                                (<! (timeout addition-timeout))
                                                (>! publisher {:message/topic :topic/output
                                                               :message/content (+ (:message/content message)
                                                                                   addition-value)})))
                                            (recur)))}}})

(deftest nabab-test
  (let [implementation (nabab/bootstrap! test-description)
        output-chan (chan)
        addition-value (-> implementation :context/addition-value)
        input-chan (:nabab/publisher implementation)
        n (rand-int 100)]
    (sub (:nabab/publication implementation)
         :topic/output
         output-chan)

    (>!! input-chan
         {:message/topic :topic/input
          :message/content n})

    (is (= (<!! output-chan) {:message/topic :topic/output
                              :message/content (* 2 (inc n))}))

    (is (= (<!! output-chan) {:message/topic :topic/output
                              :message/content (+ addition-value
                                                  (* 4 (inc n)))}))

    (is (= (<!! output-chan) {:message/topic :topic/output
                              :message/content (+ addition-value
                                                  (* 4 (inc n)))}))

    (testing "doesn't output more messages"
      (when-let [[maybe-message _] (alts!! [output-chan (timeout 500)] :priority true)]
        (is (nil? maybe-message))))

    (nabab/shutdown! implementation)))

(deftest viz-test
  (testing "edges"
    (is (= (set (viz/graph-edges test-description))
           #{#:edge{:name :input
                    :from :topic/input
                    :to ::1}
             #:edge{:name :simple-processing
                    :from ::1
                    :to ::2}
             #:edge{:name :fork
                    :from ::2
                    :to :topic/output}
             #:edge{:name :fork
                    :from ::2
                    :to ::3}
             #:edge{:name :output
                    :from ::3
                    :to :topic/output}})))
  (testing "nodes"
    (is (= (set (viz/graph-nodes test-description))
           #{:topic/input ::1 ::2 ::3 :topic/output}))))
