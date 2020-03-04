(ns universe.utils)

(defn string-filter
  [pred string]
  (clojure.string/join "" (map #(pred (str %)) (vec string))))
  
