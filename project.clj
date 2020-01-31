(defproject tyro "0.1.0-SNAPSHOT"
  :description "P2P File Sharing System"
  :url "https://github.com/danjrauch/tyro"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.7.559"]
                 [net.async/async "0.1.0"]
                 [org.clojure/tools.logging "0.5.0"]
                 [org.clojure/tools.cli "0.4.2"]]
  :main ^:skip-aot tyro.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
