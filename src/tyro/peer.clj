(ns tyro.peer
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts! alts!! poll! close! thread timeout dropping-buffer chan]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.logging :as logging]
            [net.async.tcp :refer [event-loop connect accept]]))

(def dir (ref ""))
; TODO implement a 'set index' function
(def channel-map (ref {:index {:host "127.0.0.1"
                               :port 8715
                               :ch (chan (dropping-buffer 100))}}))

(defn connect-and-collect
  [host port message-ch]
  (let [client (connect (event-loop) {:host host :port port})
        n (loop [i 0]
            (let [v (poll! message-ch)]
              (if (nil? v)
                i
                (do
                  (>!! (:write-chan client)
                       (.getBytes (prn-str (assoc v :time (System/currentTimeMillis)))))
                  (recur (inc i))))))
        check (<!! (:read-chan client))]
    (if (= :connected check)
      ; TODO just put results on a result-ch
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
            (recur (conj results response) (inc i)))))
      [{:type -1 :connection-error true :host host :port port}])))

(defn registry
  "Register yourself as a peer."
  {:added "0.1.0"}
  [host port]
  (>!! (:ch (:index @channel-map)) {:type 0 :host host :port port}))

(defn register
  "Register a file."
  {:added "0.1.0"}
  [peer-id file-name]
  (>!! (:ch (:index @channel-map)) {:type 1 :peer-id peer-id :file-name file-name}))

(defn deregister
  "Deregister a file."
  {:added "0.1.0"}
  [peer-id file-name]
  (>!! (:ch (:index @channel-map)) {:type 2 :peer-id peer-id :file-name file-name}))

(defn search
  "Search for a list of viable peers."
  {:added "0.1.0"}
  [file-name]
  (>!! (:ch (:index @channel-map)) {:type 3 :file-name file-name}))

(defn retrieve
  "Makd a request to another peer for a file."
  {:added "0.1.0"}
  [host port file-name]
  (let [[_ info] (first (filter (fn [[_, v]]
                                  (and (= (:host v) host) (= (:port v) port))) 
                                @channel-map))]
    (if (nil? info)
      (dosync
        (alter channel-map assoc (keyword (str host ":" port)) {:host host
                                                                :port port
                                                                :ch (chan (dropping-buffer 100))})
        (>!! (:ch ((keyword (str host ":" port)) @channel-map)) {:type 4 :file-name file-name}))
      (>!! (:ch info) {:type 4 :file-name file-name}))))

(defn save-file
  "Write the file contents to the dir."
  {:added "0.1.0"}
  [file-name contents]
  (spit (io/file @dir file-name) contents))

(defn handle-retrieve
  "Download the file contents and put it on the write back channel."
  {:added "0.1.0"}
  [client-bindings]
  (let [{:keys [file-name write-chan msg]} client-bindings]
    (if (.exists (io/file @dir file-name))
      (let [msg (assoc msg :contents (slurp (io/file @dir file-name)) :success true)
            msg (dissoc msg :write-chan)]
        (go (>! write-chan (.getBytes (prn-str msg))))
        (str "RETURNED CONTENTS for file " file-name " to client"))
      (let [msg (assoc msg :contents "" :success false)
            msg (dissoc msg :write-chan)]
        (go (>! write-chan (.getBytes (prn-str msg))))
        (str "FILE " file-name " DOES NOT EXIST")))))

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
                               :write-chan (:write-chan msg)
                              ;  :dir @dir ; (nth (flatten args) 0)
                               :msg msg}]
          (case (:type msg)
            4 (go (>! fut-ch (future (handle-retrieve client-bindings))))))
        ; use alts and timeout to check the future channel
        ; error raised on dereference if the future errored
        (let [[v _] (alts!! [fut-ch (timeout 10)])]
          (logging/info @v)))
      (recur))))

; (defn execute-shadow-client
;   "Make shadow requests on a semi-regular basis."
;   {:added "0.1.0"}
;   []
;   (let [message-ch (chan (dropping-buffer 100))]
;     (registry "127.0.0.1" 8714 message-ch)
;     (let [results (connect-and-collect "127.0.0.1" 8715 message-ch)
;           peer-id (:peer-id (nth results 0))]
;       (register peer-id "some.txt" message-ch)
;       (deregister peer-id "some.txt" message-ch)
;       (search "some.txt" message-ch)

;       (logging/info (into [] (concat results (connect-and-collect "127.0.0.1" 8715 message-ch)))))))