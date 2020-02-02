(ns tyro.core
  (:gen-class)
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts!! thread timeout dropping-buffer chan]]
            [clojure.tools.logging :as logging]
            [clojure.tools.cli :refer [parse-opts]]
            [net.async.tcp :refer [event-loop connect accept]]
            [clojure.edn :as edn]
            [tyro.index :as ind]
            [tyro.peer :as peer]
            [tyro.repl :as repl]))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-d" "--dir DIR" "File directory"
    ; :default "."
    :parse-fn #(String. %)]
   ;; A non-idempotent option (:default is applied first)
   [nil "--repl" "REPL Mode for Peer"]
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
  (let [exec-chan (chan (dropping-buffer 100))]

    (thread (executor exec-chan (flatten args)))

    (let [acceptor (accept (event-loop) {:port port})]
      (logging/info "SERVER RUNNING")
      (loop []
        (when-let [server (<!! (:accept-chan acceptor))]
          (go
            (loop []
              (when-let [msg (<! (:read-chan server))]
                (when-not (keyword? msg)
                  ; read the message as a clojure map
                  (let [msg-map (edn/read-string (String. msg))]
                    (logging/info (str "RECEIVED MESSAGE " msg-map))
                    ; ACK the msg received and send it back
                    ; (>! (:write-chan server) (.getBytes (prn-str (assoc msg-map :ack 1))))
                    ; Put the message on the execute channel and keep listening
                    (>! exec-chan (assoc msg-map :write-chan (:write-chan server)))))
                (recur))))
          (recur))))))

(defn -main
  [& args]
  (let [opts (parse-opts args cli-options)]
    (when (:errors opts)
      (logging/error (first (:errors opts)))
      (System/exit 0))

    (when (not (contains? (:options opts) :port))
      (logging/error "Provide a value for the option \"--port\".")
      (System/exit 0))

    (when (not= (count (:arguments opts)) 1)
      (logging/error "Provide only one arguemnt of either \"index\" or \"peer\".")
      (System/exit 0))

    (when (and (= (first (:arguments opts)) "peer")
               (not (contains? (:options opts) :dir)))
      (logging/error "Provide a value for the option \"--dir\" for argument \"peer\".")
      (System/exit 0))

    (let [node-type (first (:arguments opts))
          port (:port (:options opts))
          dir (:dir (:options opts))]
      (case node-type
        "index" (start-server ind/execute port)
        "peer" (do
                 (dosync (ref-set peer/dir dir))
                 (if (= (:repl (:options opts)) true)
                   (do
                     (thread (start-server peer/execute port dir))
                     (repl/start-repl))
                   (start-server peer/execute port dir)))))))