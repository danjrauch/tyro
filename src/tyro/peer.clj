(ns tyro.peer
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts! alts!! poll! close! thread timeout dropping-buffer chan]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [net.async.tcp :refer [event-loop connect accept]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def pid (ref -1))
(def dir (ref ""))
(def channel-map (ref {}))
(def file-history (ref []))

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
            (when (== (:type response) 0)
              (dosync (ref-set pid (:peer-id response))))
            (recur (conj results response) (inc i)))))
      [{:type -1 :connection-error true :host host :port port}])))

(defn set-index
  "Set the endpoint of the index.
   If an index was already set, execute requests in the channel and return them."
  {:added "0.1.0"}
  [host port]
  (if (contains? @channel-map :index)
    (let [info (:index @channel-map)
          results (connect-and-collect (:host info) (:port info) (:ch info))]
      (dosync
       (alter channel-map assoc :index {:host host :port port :ch (chan (dropping-buffer 10000))})
       results))
    (dosync
     (alter channel-map assoc :index {:host host :port port :ch (chan (dropping-buffer 10000))})
     [])))

(defn registry
  "Register yourself as a peer."
  {:added "0.1.0"}
  [host port]
  (if (contains? @channel-map :index)
    (>!! (:ch (:index @channel-map)) {:type 0 :host host :port port})
    false))

(defn register
  "Register a file with the index."
  {:added "0.1.0"}
  [peer-id file-name]
  (if (contains? @channel-map :index)
    (>!! (:ch (:index @channel-map)) {:type 1 :peer-id peer-id :file-name file-name})
    false))

(defn deregister
  "Deregister a file with the index."
  {:added "0.1.0"}
  [peer-id file-name]
  (if (contains? @channel-map :index)
    (>!! (:ch (:index @channel-map)) {:type 2 :peer-id peer-id :file-name file-name})
    false))

(defn search
  "Search for a list of viable peers that have a certain file."
  {:added "0.1.0"}
  [file-name]
  (if (contains? @channel-map :index)
    (>!! (:ch (:index @channel-map)) {:type 3 :file-name file-name})
    false))

(defn retrieve
  "Make a request to another peer for a file."
  {:added "0.1.0"}
  [host port file-name]
  (let [[_ info] (first (filter (fn [[_, v]]
                                  (and (= (:host v) host) (= (:port v) port)))
                                @channel-map))]
    (if (nil? info)
      (dosync
       (alter channel-map assoc (keyword (str host ":" port)) {:host host
                                                               :port port
                                                               :ch (chan (dropping-buffer 10000))})
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

(defn logger
  "Log the results of the requests."
  {:added "0.1.0"}
  [ch]
  (loop []
    (let [v (poll! ch)]
      (when (not (nil? v))
        (timbre/debug @v))
      (recur))))

(defn sync-dir
  "Synchronize contents of dir with the index."
  {:added "0.1.0"}
  []
  (loop []
    (when (and (not= @pid -1) (not= @dir ""))
      (let [files (filter #(not (.isDirectory %)) (file-seq (io/file @dir)))
            actual-file-names (map #(.getName %) files)
            historical-file-names (map :name @file-history)
            new-file-names (set/difference (set actual-file-names) (set historical-file-names))
            deleted-file-names (set/difference (set historical-file-names) (set actual-file-names))]
        (doseq [n new-file-names]
          (register @pid n))

        (doseq [d deleted-file-names]
          (deregister @pid d))

        ; (when (contains? @channel-map :index)
        ;   (connect-and-collect (:host (:index @channel-map))
        ;                        (:port (:index @channel-map))
        ;                        (:ch (:index @channel-map))))

        (dosync (ref-set file-history (vec (for [file files] {:name (.getName file)}))))))
    (Thread/sleep 30000)
    (recur)))

(defn execute
  "Function to execute requests. To be run concurrently with the server event loop."
  {:added "0.1.0"}
  [ch & args]
  (let [fut-ch (chan (dropping-buffer 10000))]
    
    ; start a logging thread
    (thread (logger fut-ch))

    ; start a thread for directory syncing
    (thread (sync-dir))
    
    (loop []
      ; take a message off the channel
      (when-let [msg (<!! ch)]
        ; execute it in a future and put the future on the future channel
        (let [client-bindings {:file-name (:file-name msg)
                               :write-chan (:write-chan msg)
                               :msg msg}]
          (case (:type msg)
            4 (go (>! fut-ch (future (handle-retrieve client-bindings))))))
        ; poll! for a result of a command, error raised on dereference if the future errored
        (loop []
          (let [v (poll! fut-ch)]
            (when (not (nil? v))
              (timbre/debug @v)
              (recur)))))
      (recur))))