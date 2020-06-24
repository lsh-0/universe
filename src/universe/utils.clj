(ns universe.utils)


(defn seq-to-map
  "creates a map from an even, sequential, collection of values"
  [s]
  (if-not (even? (count s))
    (error "expected an even number of elements:" s)
    (->> s (partition 2) (map vec) (into {}))))


(defn in?
  [needle haystack]
  (not (nil? (some #{needle} haystack))))

(defn mk-id
  []
  (java.util.UUID/randomUUID))


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
