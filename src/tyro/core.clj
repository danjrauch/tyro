(ns tyro.core
  (:gen-class)
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts! alts!! poll! close! thread timeout dropping-buffer chan]]
            [clojure.edn :as edn]
            [clojure.set :refer :all]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [net.async.tcp :refer [event-loop connect accept]]
            [trptcolin.versioneer.core :as version]
            [tyro.index :as ind]
            [tyro.peer :as peer]
            [tyro.repl :as repl]
            [tyro.tool :as tool]
            [taoensso.timbre.appenders.core :as appenders]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]])
  (:use clojure.java.shell))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 8000
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-d" "--dir DIR" "File directory"
    ; :default "."
    :parse-fn #(String. %)]
   ;; A non-idempotent option (:default is applied first)
   [nil "--repl" "REPL Mode for Peer"]
   [nil "--path PATH" "Path to the resource"]
   [nil "--policy POLICY" "Consistency policy"]
   ["-i" "--id ID" "ID of the node"
    :parse-fn #(Integer/parseInt %)]
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc]
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])

(defn start-server
  "Starts the index server."
  {:added "0.1.0"}
  [executor port & args]
  (let [exec-chan (chan (dropping-buffer 1000))]

    (thread (apply executor (conj (flatten args) port exec-chan)))

    (let [acceptor (accept (event-loop) {:port port})]
      (timbre/debug "SERVER RUNNING")
      (loop []
        (when-let [server (<!! (:accept-chan acceptor))]
          (go
            (loop []
              (when-let [msg (<! (:read-chan server))]
                (when-not (keyword? msg)
                  ; read the message as a clojure map
                  (let [msg-map (edn/read-string (String. msg))]
                    (timbre/debug (str "RECEIVED MESSAGE " msg-map))
                    ; ACK the msg received and send it back
                    ; (>! (:write-chan server) (.getBytes (prn-str (assoc msg-map :ack 1))))
                    ; Put the message on the execute channel and keep listening
                    (>! exec-chan (assoc msg-map :write-chan (:write-chan server)))))
                (recur))))
          (recur))))))

(defn print-help
  "Print the help message"
  [opts]
  (println (:summary opts))
  (println)
  (println "index -p PORT [--repl]")
  (println "peer -p PORT -d DIR --policy POLICY [--repl]")
  (println "run-config --path PATH --id ID")
  (println))

(defn -main
  [& args]
  (when (not= (version/get-version "GROUP-ID" "ARTIFACT-ID") ""))

  (timbre/merge-config! {:appenders {:println {:enabled? false}
                                     :spit (appenders/spit-appender {:fname "timbre.log"})}})

  (let [opts (parse-opts args cli-options)
        required-args {:index [:port]
                       :peer [:port :dir :policy]
                       :simulation [:path]
                       :run-config [:path :id]}]
    (cond
      (:errors opts) (do
                       (timbre/error (first (:errors opts)))
                       (System/exit 0))
      (contains? (:options opts) :help) (do
                                          (println)
                                          (print-help opts))
      (== (count (:arguments opts)) 1) (let [req-arg-pass (empty? (difference (set ((keyword (first (:arguments opts))) required-args))
                                                                              (set (keys (:options opts)))))
                                             port (:port (:options opts))
                                             dir (:dir (:options opts))
                                             policy (:policy (:options opts))]
                                         (case (first (:arguments opts))
                                           "index" (if req-arg-pass
                                                     (if (= (:repl (:options opts)) true)
                                                       (do
                                                         (thread (start-server ind/execute port))
                                                         (repl/start-repl "index"))
                                                       (start-server ind/execute port))
                                                     (System/exit 0))
                                           "peer" (if req-arg-pass
                                                    (if (= (:repl (:options opts)) true)
                                                      (do
                                                        (thread (start-server peer/execute port dir policy))
                                                        (repl/start-repl "peer"))
                                                      (start-server peer/execute port dir policy))
                                                    (System/exit 0))
                                           "simulation" (if req-arg-pass
                                                          (let [config (tool/load-edn (:path (:options opts)))]
                                                            (println "Starting simulation.")
                                                            (doseq [index-config (:nodes config)]
                                                              (println "Starting index with ID: " (str (:id index-config)))
                                                              (thread (sh "lein" "run" "run-config" "--path" (:path (:options opts)) "--id" (str (:id index-config))))
                                                              (doseq [peer-config (:leaves index-config)]
                                                                (println "Starting peer with ID: " (str (:id peer-config)))
                                                                (thread (sh "lein" "run" "run-config" "--path" (:path (:options opts)) "--id" (str (:id peer-config))))))
                                                            (println "Going to sleep...")
                                                            (Thread/sleep 200000)
                                                            (println "Woke up. Killing stragglers...")
                                                            (sh "pkill" "-f" "tyro")
                                                            (println "Ending simulation."))
                                                          (System/exit 0))
                                           "run-config" (if req-arg-pass
                                                          (let [config (tool/load-edn (:path (:options opts)))
                                                                id (:id (:options opts))]
                                                            (doseq [index-config (:nodes config)]
                                                              (when (== (:id index-config) id)
                                                                (doseq [con-id (get (:connections config) id)]
                                                                  (doseq [con-map (filter #(== (:id %) con-id) (:nodes config))]
                                                                    ; (timbre/debug (str "ADDED ID: " con-id " TO ID: " id "'s PEER LIST"))
                                                                    (ind/add (:host con-map) (:port con-map))))
                                                                (thread (start-server ind/execute (:port index-config)))
                                                                (Thread/sleep 170000)
                                                                (System/exit 0))

                                                              (doseq [peer-config (:leaves index-config)]
                                                                (when (== (:id peer-config) id)
                                                                  (peer/set-index (:host index-config) (:port index-config))
                                                                  (thread (start-server peer/execute (:port peer-config) (:dir peer-config) (:policy peer-config)))
                                                                  (Thread/sleep 10000)
                                                                  (repl/run-script (:script peer-config) (:host peer-config) (:port peer-config))
                                                                  (Thread/sleep 170000)
                                                                  (System/exit 0)))))
                                                          (System/exit 0))
                                           (println " Command doesn't exist")))
      :else (do (println)
                (print-help opts)))))