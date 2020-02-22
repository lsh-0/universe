(ns universe.cli
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   [clojure.core.async :as async :refer [<! >! >!! <!! go]]
   [universe.core :as core]))

;;

;; https://stackoverflow.com/questions/4334897/functionally-split-a-string-by-whitespace-group-by-quotes
(declare parse*)

(defn slurp-word [words xs terminator]
  (loop [res "" xs xs]
    (condp = (first xs)
      nil  ;; end of string after this word
      (conj words res)

      terminator ;; end of word
      #(parse* (conj words res) (rest xs))

      ;; else
      (recur (str res (first xs)) (rest xs)))))

(defn parse* [words xs]
  (condp = (first xs)
    nil ;; end of string
    words

    \space  ;; skip leading spaces
    (parse* words (rest xs))

    \" ;; start quoted part
    #(slurp-word words (rest xs) \")

    ;; else slurp until space
    #(slurp-word words xs \space)))

(defn parse [s]
  (trampoline #(parse* [] s)))

;;

(defn respond
  [message]
  (if (clojure.string/starts-with? message ":")
    ;; assume a command is to be run and the first argument is the topic
    (let [tokens (parse message)
          topic-kw (-> tokens first (subs 1) keyword) ;; [":echo" ...] => :echo
          request (apply core/request (into [topic-kw] (rest tokens)))
          ]
      (core/emit! request)
      (<!! (:response-chan request)))
    
    ;; just text, don't wait for a response
    (core/emit! (core/message message))))

(defn retprn
  [x]
  (println (str "=> " x))
  x)

(defn read-many-lines
  "this is good for free-form text. input is returned a list of strings"
  []
  (take-while identity (repeatedly #(read-line))))

(defn read-single-line
  []
  (read-line))

(defn start
  [& [{:keys [prompt? command-list]
       :or {prompt? true, command-list []}}]]
  (when prompt?
    (println "(ctrl-d to exit, double quotes if you must)"))
  (loop [command (first command-list)
         command-history []]
    (let [cmd (or command
                  ;; for now, read a single line and expect it to be a command or off-topic nonsense
                  (when prompt?
                    (read-single-line)))]
      (if-not cmd
        ;; no commands left and no prompting allowed, return command history
        {:success? true, :command-history command-history, :options {:prompt? prompt? :command-list command-list}}
        ;; ask for a response to given command (which may have come from user
        (recur (next command-list)
               (conj command-history [cmd (retprn (respond cmd))]))))))
