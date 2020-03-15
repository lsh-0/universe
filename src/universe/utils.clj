(ns universe.utils)

(defn string-filter
  [pred string]
  (clojure.string/join "" (map #(pred (str %)) (vec string))))

;; not actually using this anymore. rm?
(defn safe-subvec
  [v start end]
  (if (empty? v)
    v
    (subvec v
            (if (neg-int? start) 0 start)
            (min (if (neg-int? end) 0 end) (count v)))))
