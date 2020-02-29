(defproject universe "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.7.559"]
                 [org.clojure/tools.namespace "0.3.1"]
                 [com.taoensso/timbre "4.10.0"]
                 [clj-commons/fs "1.5.0"]

                 [clojurewerkz/neocons "3.2.0"] ;; communicate with neo4k
                 ]
  :repl-options {:init-ns universe.main})
