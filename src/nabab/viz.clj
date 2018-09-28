(ns nabab.viz)

(def xf-edges
  (comp (map (fn retrieve-edge-name [[transition-name transition]]
               (assoc transition :edge/name transition-name)))
        (mapcat (fn retrieve-edge-source [transition]
                  (->> (:nabab/doc-published-topics transition)
                       keys
                       (map (fn [node]
                              (assoc transition :edge/from node))))))
        (mapcat (fn retrieve-edge-target [transition]
                  (->> (:edge/from transition)
                       (get (:nabab/doc-published-topics transition))
                       (map (fn [node]
                              (assoc transition :edge/to node))))))
        (map #(select-keys % #{:edge/name
                               :edge/from
                               :edge/to}))))

(defn graph-edges
  [description]
  (->> description
       :nabab/transitions
       (eduction xf-edges)))

(defn graph-nodes
  [description]
  (->> (graph-edges description)
       (mapcat (comp concat
                     (juxt :edge/from
                           :edge/to)))
       set))
