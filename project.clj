(defproject tyro "0.1.0-SNAPSHOT"
  :description "P2P File Sharing System"
  :url "https://github.com/danjrauch/tyro"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.7.559"]
                 [com.taoensso/timbre "4.10.0"]
                 [org.clojure/tools.cli "0.4.2"]
                 [net.async/async "0.1.0"]
                 [trptcolin/versioneer "0.2.0"]
                 [clj-time "0.15.0"]
                 [clj-commons/spinner "0.6.0"]]
  :main ^:skip-aot tyro.core
  :target-path "target/%s"
  ; :env-vars {:TIMBRE_LEVEL ':warn'}
  :plugins [[lein-with-env-vars "0.2.0"]
            [lein-binplus "0.6.5"]
            [lein-annotations "0.1.0"]]
  :profiles {:uberjar {:aot :all}})
