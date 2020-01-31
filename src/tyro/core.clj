(ns tyro.core
  (:gen-class)
  (:require [clojure.core.async :refer [<!! <! >! >!! go]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.tools.logging :as logging]
            [clojure.tools.cli :refer [parse-opts]]
            [net.async.tcp :refer [accept event-loop connect]]
            [tyro.index :as ind]))

(def cli-options
  ;; An option with a required argument
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ;; A non-idempotent option (:default is applied first)
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc] ; Prior to 0.4.1, you would have to use:
                   ;; :assoc-fn (fn [m k _] (update-in m [k] inc))
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])

(defn echo-server
  [port]
  (let [acceptor (accept (event-loop) {:port port})]
    (loop []
      (when-let [server (<!! (:accept-chan acceptor))]
        (go
          (loop []
            (when-let [msg (<! (:read-chan server))]
              (when-not (keyword? msg)
                ; Receive a message from a peer and convert it to a clojure map
                (println (edn/read-string (String. msg)))
                ; ACK the msg received
                (>! (:write-chan server) (.getBytes (str "ECHO/" (String. msg))))
                ; Put the message on the execution channel and keep listening
                )
              (recur))))
        (recur)))))

(defn echo-client
  [host port]
  (let [client (connect (event-loop) {:host host :port port})]
    (loop []
      (go (>! (:write-chan client) (.getBytes (prn-str {:type 9}))))
      (loop []
        (let [read (<!! (:read-chan client))]
          (when (and (keyword? read)
                     (not= :connected read))
            (recur))))
      ; (Thread/sleep (rand-int 3000))
      (recur))))

(defn -main
  [& args]
  (def opts (parse-opts args cli-options))

  (when (opts :errors)
    (logging/error (first (opts :errors)))
    (System/exit 0))

  (when (not (contains? (opts :options) :port))
    (logging/error "Provide a value for the option \"--port\".")
    (System/exit 0))

  (when (not= (count (opts :arguments)) 1)
    (logging/error "Provide only one arguemnt of either \"index\" or \"peer\".")
    (System/exit 0))

  (def node-type (first (opts :arguments)))
  (def port ((opts :options) :port))

  (case node-type
    "index" (echo-server port)
    "peer" (echo-client "127.0.0.1" port)))