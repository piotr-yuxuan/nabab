(ns nabab.nabab-test
  (:require [nabab.lifecycle :as nabab]
            [nabab.viz :as viz]
            [clojure.core.async :refer [close! chan go go-loop pub sub <! >! >!! <!! pipe pipeline alts!! timeout]]
            [clojure.spec.alpha :as spec]
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

(spec/def :message/topic #{::input ::next ::output})
(spec/def :message/hop nat-int?)

(spec/def ::message
  (spec/keys :req [:message/topic
                   :message/hop]))

(defn ring-graph
  [graph-name]
  (let [fixed-buffer-size 1]
    {:nabab/fixed-buffer-size fixed-buffer-size
     :context/next-publisher nil
     :nabab/publisher (chan fixed-buffer-size ;; created for each function call
                            (map #(assoc %
                                    :message/graph graph-name)))
     :nabab/dispatch-ifn (fn [message]
                           (assert (spec/valid? ::message message))
                           (:message/topic message))
     :nabab/transitions {::input {:nabab/subscribed-topics #{::input}
                                  :nabab/pipeline-transducer (map #(assoc %
                                                                     :message/previous % ;; only from ::input
                                                                     :message/topic (if (zero? (:message/hop %))
                                                                                      ::output
                                                                                      ::next)))}
                         ::next {:nabab/subscribed-topics #{::next}
                                 :nabab/block-fn (fn [description subscriber]
                                                   (pipeline (:nabab/fixed-buffer-size description)
                                                             (-> description :context/next-publisher)
                                                             (map #(-> %
                                                                       (update :message/hop dec)
                                                                       (assoc :message/topic ::input)))
                                                             subscriber))}
                         ::output {:nabab/subscribed-topics #{::output}
                                   :nabab/pipeline-transducer (take 0)}}}))

(deftest compose-ring-graphs
  (testing "compose graph in a ring pattern"
    (let [graphs-ring (as-> {:a (ring-graph :a)
                             :b (ring-graph :b)
                             :c (ring-graph :c)} graphs
                            (assoc-in graphs [:a :context/next-publisher] (-> graphs :b :nabab/publisher))
                            (assoc-in graphs [:b :context/next-publisher] (-> graphs :c :nabab/publisher))
                            (assoc-in graphs [:c :context/next-publisher] (-> graphs :a :nabab/publisher))
                            (map (fn [[k v]] [k (nabab/bootstrap! v)]) graphs)
                            (into {} graphs))
          n 3 ;; why not?
          input-chan (:nabab/publisher (:a graphs-ring))
          output-chan (chan)]
      (doseq [graph (vals graphs-ring)]
        (sub (:nabab/publication graph)
             ::output
             output-chan))

      (testing "tolopogy"
        (is (= (-> graphs-ring :a :context/next-publisher) (-> graphs-ring :b :nabab/publisher)))
        (is (= (-> graphs-ring :b :context/next-publisher) (-> graphs-ring :c :nabab/publisher)))
        (is (= (-> graphs-ring :c :context/next-publisher) (-> graphs-ring :a :nabab/publisher))))

      (testing "message path"
        (>!! input-chan
             {:message/topic ::input
              :message/hop n})

        (is (= (<!! output-chan)
               ;; only from ::input
               {:message/topic ::output
                :message/hop 0
                :message/graph :a
                :message/previous
                {:message/topic ::input
                 :message/hop 0
                 :message/graph :a
                 :message/previous
                 {:message/topic ::input
                  :message/hop 1
                  :message/graph :c
                  :message/previous
                  {:message/topic ::input
                   :message/hop 2
                   :message/graph :b
                   :message/previous
                   {:message/topic ::input
                    :message/hop 3
                    :message/graph :a}}}}}))))))

(defn star-graph-a
  [skeleton graph-name]
  (merge skeleton
         {:nabab/fixed-buffer-size (:nabab/fixed-buffer-size skeleton)
          :nabab/dispatch-ifn (fn [message]
                                (assert (spec/valid? ::message message))
                                (:message/topic message))
          :nabab/transitions {::input {:nabab/subscribed-topics #{::input}
                                       :nabab/pipeline-transducer (map #(assoc %
                                                                          :message/graph graph-name
                                                                          :message/topic (if (zero? (:message/hop %))
                                                                                           ::output
                                                                                           ::next)))}}}))

(defn star-graph-b
  [skeleton graph-name]
  (merge skeleton
         {:nabab/fixed-buffer-size (:nabab/fixed-buffer-size skeleton)
          :nabab/dispatch-ifn (fn [message]
                                (assert (spec/valid? ::message message))
                                (:message/topic message))
          :nabab/transitions {::next {:nabab/subscribed-topics #{::next}
                                      :nabab/pipeline-transducer (map #(-> %
                                                                           (update :message/hop dec)
                                                                           (assoc :message/topic ::input
                                                                                  :message/graph graph-name)))}}}))

(defn star-graph-c
  [skeleton _]
  (merge skeleton
         {:nabab/fixed-buffer-size (:nabab/fixed-buffer-size skeleton)
          :nabab/dispatch-ifn (fn [message]
                                (assert (spec/valid? ::message message))
                                (:message/topic message))
          :nabab/transitions {::output {:nabab/subscribed-topics #{::output}
                                        :nabab/pipeline-transducer (take 0)}}}))

(deftest compose-star-graph
  (testing "compose graphs in a star pattern"
    (let [fixed-buffer-size 1
          ;; bootstrap graph-core so :nabab/publication is set
          graph-core (nabab/bootstrap! {:nabab/fixed-buffer-size fixed-buffer-size
                                        :nabab/publisher (chan fixed-buffer-size (map #(assoc % :message/previous %)))
                                        :nabab/dispatch-ifn (fn [message]
                                                              (assert (spec/valid? ::message message))
                                                              (:message/topic message))})
          star-graphs {:a (nabab/bootstrap! (star-graph-a graph-core :a))
                       :b (nabab/bootstrap! (star-graph-b graph-core :b))
                       :c (nabab/bootstrap! (star-graph-c graph-core :c))}
          n 3 ;; why not?
          input-chan (:nabab/publisher (rand-nth (vals star-graphs)))
          output-chan (chan)]
      (sub (:nabab/publication (:a star-graphs))
           ::output
           output-chan)

      (testing "tolopogy"
        (is (= (:nabab/publisher graph-core)
               (-> star-graphs :a :nabab/publisher)
               (-> star-graphs :b :nabab/publisher)
               (-> star-graphs :c :nabab/publisher)))

        ;; graphs share the same publication and respond to topics.
        (is (= (:nabab/publication graph-core)
               (-> star-graphs :a :nabab/publication)
               (-> star-graphs :b :nabab/publication)
               (-> star-graphs :c :nabab/publication))))

      (testing "message path"
        (>!! input-chan
             {:message/topic ::input
              :message/hop n})

        (is (= (<!! output-chan)
               {:message/topic ::output
                :message/hop 0
                :message/graph :a
                :message/previous
                {:message/topic ::output
                 :message/hop 0
                 :message/graph :a
                 :message/previous
                 {:message/topic ::input
                  :message/hop 0
                  :message/graph :b
                  :message/previous
                  {:message/topic ::next
                   :message/hop 1
                   :message/graph :a
                   :message/previous
                   {:message/topic ::input
                    :message/hop 1
                    :message/graph :b
                    :message/previous
                    {:message/topic ::next
                     :message/hop 2
                     :message/graph :a
                     :message/previous
                     {:message/topic ::input
                      :message/hop 2
                      :message/graph :b
                      :message/previous
                      {:message/topic ::next
                       :message/hop 3
                       :message/graph :a
                       :message/previous
                       {:message/topic ::input
                        :message/hop 3}}}}}}}}}))))))

(def input-transitions
  {;; here add any transition specifically related to input
   ::input {:nabab/subscribed-topics #{::input}
            :nabab/pipeline-transducer (map #(assoc %
                                               :message/previous % ;; only from ::input
                                               :message/topic (if (zero? (:message/hop %))
                                                                ::output
                                                                ::next)))}})

(def next-transitions
  {;; here add any transition specifically related to message passing
   ::next {:nabab/subscribed-topics #{::next}
           :nabab/pipeline-transducer (map #(-> %
                                                (update :message/hop dec)
                                                (assoc :message/topic ::input)))}})

(def output-transitions
  {;; here add any transition specifically related to output
   ::output {:nabab/subscribed-topics #{::output}
             :nabab/pipeline-transducer (take 0)}})

(defn transition-merge-graph
  []
  (let [fixed-buffer-size 1]
    {:nabab/fixed-buffer-size fixed-buffer-size
     :nabab/publisher (chan fixed-buffer-size)
     :nabab/dispatch-ifn (fn [message]
                           (assert (spec/valid? ::message message))
                           (:message/topic message))
     :nabab/transitions (merge input-transitions
                               next-transitions
                               output-transitions)}))

(deftest merge-graph-transitions
  (testing "transition map as merge result domain-specific maps"
    (let [implementation (nabab/bootstrap! (transition-merge-graph))
          n 3 ;; why not?
          input-chan (:nabab/publisher implementation)
          output-chan (chan)]
      (println "input-chan" input-chan)
      (sub (:nabab/publication implementation)
           ::output
           output-chan)

      (testing "message path"
        (>!! input-chan
             {:message/topic ::input
              :message/hop n})

        (is (= (<!! output-chan)
               ;; only from ::input
               {:message/topic ::output
                :message/hop 0
                :message/previous
                {:message/topic ::input
                 :message/hop 0
                 :message/previous
                 {:message/topic ::input
                  :message/hop 1
                  :message/previous
                  {:message/topic ::input
                   :message/hop 2
                   :message/previous
                   {:message/topic ::input
                    :message/hop 3}}}}}))))))
