(ns universe.cli)

(defn respond
  [cmd]
  "?")

(defn retprn
  [x]
  (println (str "=> " x))
  x)

(defn start
  [& [{:keys [prompt? command-list]
       :or {prompt? true, command-list []}}]]
  (when prompt?
    (println "(ctrl-d to exit prompt)"))
  (loop [command (first command-list)
         command-history []]
    (let [cmd (or command
                  (when prompt? (read-line)))]
      (if-not cmd
        ;; no commands left and no prompting allowed, return command history
        {:success? true, :command-history command-history, :options {:prompt? prompt? :command-list command-list}}
        ;; ask for a response to given command (which may have come from user
        (recur (next command-list)
               (conj command-history [cmd (retprn (respond cmd))]))))))
