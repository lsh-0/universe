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
  [user-input]
  (cond
    ;; you won't get a useful response if the app hasn't been started
    (not (core/started?)) "(the application hasn't been started yet, sorry)"

    ;; assume a command is to be run and the first argument is the topic
    (clojure.string/starts-with? user-input ":")
    (let [tokens (parse user-input)
          topic-kw (-> tokens first (subs 1) keyword) ;; [":echo" ...] => :echo
          args (rest tokens)

          ;; a single argument after a topic is allowed, otherwise it has to be an even number of forms
          kwargs {:message (first args) ;; nil if no args other than topic-kw
                  :args (vec args) ;; order of args are preserved
                  :kwargs (core/seq-to-map args)}
          
          request (core/request topic-kw kwargs)]
      (cond
        ;; couldn't parse rest of tokens, fail rather than persevere
        (and (not (empty? (rest tokens)))
             (not kwargs)) "(I need an even number of arguments following the topic keyword)"
        
        ;; given topic doesn't exist
        ;; rather than rely on core/safe-message to redirect our message, return early now
        (not (core/known-topic? topic-kw))
        (format "(the application isn't listening to '%s' commands)" topic-kw)

        ;; all good :) send message, wait for response
        :else
        (core/emit-and-wait request)))

    ;; app *has* been started but input is just regular text
    ;; emit as a message and don't wait for a response
    :else (core/emit (core/message user-input))))

(defn retprn
  [x]
  (println (str "=> " x))
  x)

(defn read-many-lines
  "this is good for free-form text. input is returned as a list of strings"
  []
  (take-while identity (repeatedly #(read-line))))

(defn read-single-line
  []
  (read-line))

(defn start
  [& [{:keys [prompt? command-list]
       :or {prompt? true, command-list []}}]]
  (when prompt?
    (println "(ctrl-d to exit, double quotes for quoting)"))
  (loop [cmd-list command-list
         command-history []]
    (let [cmd (or (first cmd-list)
                  ;; for now, read a single line and expect it to be a command or off-topic nonsense
                  (when prompt?
                    (read-single-line)))]
      (if-not cmd
        ;; no commands left and no prompting allowed, return command history
        {:command-history command-history, :options {:prompt? prompt? :command-list command-list}}
        ;; ask for a response to given command (which may have come from user
        (recur (rest cmd-list)
               (conj command-history [cmd (retprn (respond cmd))]))))))
