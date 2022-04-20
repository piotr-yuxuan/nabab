;; Generated from litterate programming file `README.org`.
(ns nabab.specs
  "Formal definitions of nabab domain concepts"
  (:require [clojure.spec.alpha :as spec]
            [clojure.core.async.impl.channels :refer [chan]]))

(def chan-type
  (type (chan 0)))

(defn chan? [c] (instance? chan-type c))

(spec/def ::nabab-graph (spec/keys :req [:nabab/fixed-buffer-size
                                         :nabab/publication
                                         :nabab/dispatch-ifn
                                         :nabab/transitions
                                         :nabab/publisher]))

(spec/def :nabab/transitions (spec/map-of :nabab/transition-name ::transition))
(spec/def :nabab/transition-name any?)

(spec/def ::transition (spec/keys :req [:nabab/subscriber
                                        :nabab/block
                                        :nabab/transition-name]
                                  :opt [:nabab/subscribed-topics
                                        :nabab/doc-published-topics
                                        :nabab/block-fn]))
(spec/def :nabab/subscriber chan?)
(spec/def :nabab/block chan?)

(spec/def :nabab/subscribed-topics (spec/coll-of any? :kind set?))
(spec/def :nabab/block-fn ifn?)

(spec/def :nabab/doc-published-topics (spec/map-of any? (spec/coll-of any? :kind set?)))

(spec/def ::message (spec/keys :req [:message/topic]))
(spec/def :message/topic any?)

(spec/def :nabab/dispatch-ifn ifn?)

(spec/def :nabab/publisher chan?)
(spec/def :nabab/publication chan?)
