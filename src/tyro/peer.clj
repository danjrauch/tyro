(ns tyro.peer
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts! alts!! close! go-loop thread timeout dropping-buffer chan]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.tools.logging :as logging]
            [net.async.tcp :refer [event-loop connect accept]]))

(defn make-request
  [host port message-ch]
  (let [client (connect (event-loop) {:host host :port port})
        n (loop [i 0]
            (let [[v _] (alts!! [message-ch (timeout 100)]
                                :default {:type -1})]
              (if (== (:type v) -1)
                i
                (do
                  (>!! (:write-chan client)
                       (.getBytes (prn-str (assoc v :time (System/currentTimeMillis)))))
                  (recur (inc i))))))]
    (assert (= :connected (<!! (:read-chan client))))
    (loop [results []
           i 0]
      (if (== i n)
        results
        (let [read (<!! (:read-chan client))
              response (loop []
                         (if (not (keyword? read))
                           (let [res (edn/read-string (String. read))]
                             (assoc res :time (- (System/currentTimeMillis) (:time res))))
                           (when (and (keyword? read) (not= :connected read))
                             (recur))))]
          (recur (conj results response) (inc i)))))))

(defn registry
  "Register yourself as a peer."
  {:added "0.1.0"}
  [])

(defn register
  "Register a file."
  {:added "0.1.0"}
  [])

(defn deregister
  "Deregister a file."
  {:added "0.1.0"}
  [])

(defn search
  "Search for a list of viable peers."
  {:added "0.1.0"}
  [])

(defn retrieve
  "Makd a request to another peer for a file."
  {:added "0.1.0"}
  [file-name host port])

(defn handle-retrieve
  "Download the file contents and send it to the client."
  {:added "0.1.0"}
  [client-bindings])

(defn execute
  "Function to execute requests. To be run concurrently with the server event loop."
  {:added "0.1.0"}
  [ch & args]
  (let [fut-ch (chan (dropping-buffer 100))]
    (loop []
      ; take a message off the channel
      (when-let [msg (<!! ch)]
        ; execute it in a future and put the future on the future channel
        (let [client-bindings {:file-name (:file-name msg)
                               :client-ch (:write-chan msg)
                               :dir (nth args 0)}]
          (case (:type msg)
            0 (go (>! fut-ch (future (handle-retrieve client-bindings))))))
        ; use alts and timeout to check the future channel
        ; error raised on dereference if the future errored
        (let [[v _] (alts!! [fut-ch (timeout 10)])]
          (logging/info @v)))
      (recur))))

(defn execute-shadow-client
  "Make shadow requests on a semi-regular basis."
  {:added "0.1.0"}
  []
  (let [message-ch (chan)]
    (go
      (>! message-ch {:type 0 :host "127.0.0.1" :port 8715})
      ; (>! message-ch {:type 1 })
      )
    (doseq [result (make-request "127.0.0.1" 8715 message-ch)]
      (logging/info result))))