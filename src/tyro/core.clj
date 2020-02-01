(ns tyro.core
  (:gen-class)
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts!! thread timeout dropping-buffer chan]]
            [clojure.tools.logging :as logging]
            [clojure.tools.cli :refer [parse-opts]]
            [net.async.tcp :refer [event-loop connect accept]]
            [clojure.edn :as edn]
            [tyro.index :as ind]
            [tyro.peer :as peer]))

(def cli-options
  ;; An option with a required argument
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-d" "--dir DIR" "File directory"
    :default "."
    :parse-fn #(String. %)]
   ;; A non-idempotent option (:default is applied first)
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc] ; Prior to 0.4.1, you would have to use:
                   ;; :assoc-fn (fn [m k _] (update-in m [k] inc))
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])

(defn start-server
  "Starts the index server."
  {:added "0.1.0"}
  [executor port & args]
  (let [exec-chan (chan (dropping-buffer 100))]

    (thread (executor exec-chan args))

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

    (when (opts :errors)
      (logging/error (first (opts :errors)))
      (System/exit 0))

    (when (not (contains? (opts :options) :port))
      (logging/error "Provide a value for the option \"--port\".")
      (System/exit 0))

    (when (not= (count (opts :arguments)) 1)
      (logging/error "Provide only one arguemnt of either \"index\" or \"peer\".")
      (System/exit 0))

    (when (and (= (first (opts :arguments)) "peer")
               (not (contains? (opts :options) :dir)))
      (logging/error "Provide a value for the option \"--dir\" for argument \"peer\".")
      (System/exit 0))

    (let [node-type (first (opts :arguments))
          port (:port (opts :options))
          dir (:dir (opts :options))]
      (case node-type
        "index" (start-server ind/execute port)
        "peer" (do
                 (thread (peer/execute-shadow-client))
                 (start-server peer/execute port dir))))))