(ns tyro.peer
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts! alts!! poll! close! thread timeout dropping-buffer chan]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [net.async.tcp :refer [event-loop connect accept]]
            [tyro.crypto :as crypto]
            [tyro.tool :as tool]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def pid (ref -1))
(def dir (ref ""))
(def channel-map (ref {}))
(def file-index (ref {}))
(def file-history (ref []))
(def message-count (atom -1))
(def my-host (ref ""))
(def my-port (ref -1))
(def key-pair (crypto/generate-rsa-keypair))
(def public-kp (dissoc key-pair :d))
(def private-key (:d key-pair))

(defn set-index
  "Set the endpoint of the index.
   If an index was already set, execute requests in the channel and return them."
  {:added "0.1.0"}
  [host port]
  (if (contains? @channel-map :index)
    (let [info (:index @channel-map)
          results (tool/connect-and-collect (:host info) (:port info) (:ch info))]
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
    (>!! (:ch (:index @channel-map)) {:type 0 :host host :port port :public-kp public-kp})
    false))

(defn register
  "Register a file with the index server and file-index."
  {:added "0.1.0"}
  [peer-id file-name]
  (if (and (contains? @channel-map :index) (not= @pid -1))
    (do
      (dosync
       (alter file-index assoc file-name {:master @pid
                                          :version 0}))
      (>!! (:ch (:index @channel-map)) {:type 1
                                        :peer-id peer-id
                                        :file-name file-name}))
    false))

(defn deregister
  "Deregister a file with the index server and file-index."
  {:added "0.1.0"}
  [peer-id file-name]
  (if (and (contains? @channel-map :index) (not= @pid -1))
    (do
      (dosync
       (alter file-index dissoc file-name))
      (>!! (:ch (:index @channel-map)) {:type 2 
                                        :peer-id peer-id 
                                        :file-name file-name}))
    false))

(defn search
  "Search for a list of viable peers that have a certain file."
  {:added "0.1.0"}
  [file-name]
  (if (and (contains? @channel-map :index) (not= @pid -1))
    (>!! (:ch (:index @channel-map)) {:type 3
                                      :file-name file-name
                                      :host @my-host
                                      :port @my-port
                                      :ttl 3
                                      :id (str @pid (swap! message-count inc))})
    false))

(defn retrieve
  "Make a request to another peer for a file."
  {:added "0.1.0"}
  [host port target-public-kp file-name]
  (let [info (first (for [[_ v] @channel-map
                          :when (and (= (:host v) host) (= (:port v) port))]
                      v))
        msg (crypto/encrypt {:type 4 :file-name file-name :public-kp public-kp} target-public-kp (:block-size target-public-kp))]
    ; put a retrieve request on the peer's channel, register one if necessary
    (if (nil? info)
      (dosync
       (alter channel-map assoc (keyword (str host ":" port)) {:host host
                                                               :port port
                                                               :ch (chan (dropping-buffer 10000))})
       (>!! (:ch ((keyword (str host ":" port)) @channel-map)) msg))
      (>!! (:ch info) msg))))

(defn save-file
  "Write the file contents to the dir."
  {:added "0.1.0"}
  [file-name contents & args]
  (let [file (io/file @dir "downloads" file-name)]
    (io/make-parents file)
    (spit file contents)
    (when (not-empty args)
      (let [metadata-args (nth args 0)]
        (dosync
         (alter file-index assoc file-name {:master (:master metadata-args)
                                            :refresh-interval (:refresh-interval metadata-args)
                                            :ttr (+ (System/currentTimeMillis) (:refresh-interval metadata-args))
                                            :version (:version metadata-args)
                                            :host (:host metadata-args)
                                            :port (:port metadata-args)}))))))

(defn handle-retrieve
  "Download the file contents and put it on the write back channel."
  {:added "0.1.0"}
  [client-bindings]
  (let [{:keys [file-name write-chan msg]} client-bindings
        exists (.exists (io/file @dir file-name))
        msg (assoc msg
                   :contents (if exists (slurp (io/file @dir file-name)) "")
                   :master @pid
                   :refresh-interval 3000
                   :version (get-in @file-index [file-name :version])
                   :host @my-host
                   :port @my-port
                   :success true)
        msg (dissoc msg :write-chan)]
    ; (crypto/encrypt msg (:public-kp msg) (:block-size (:public-kp msg)))
    (go (>! write-chan (.getBytes (prn-str msg))))
    (if exists
      (str "RETURNED CONTENTS for file " file-name " to client")
      (str "FILE " file-name " DOES NOT EXIST"))))

(defn handle-version-check
  "Return the current version information for a file in our directory."
  {:added "0.3.0"}
  [client-bindings]
  (let [{:keys [file-name write-chan msg]} client-bindings
        version (get-in @file-index [file-name :version])
        msg (assoc msg
                   :version version
                   :success (not (nil? version)))
        msg (dissoc msg :write-chan)]
    (go (>! write-chan (.getBytes (prn-str msg))))
    (if (not (nil? version))
      (str "RETURNED VERSION " version " FOR FILE NAME: " file-name)
      (str "COULD NOT FIND VERSION FOR FILE NAME: " file-name))))

(defn handle-invalidate
  "Delete an invalid file from the directory."
  {:added "0.3.0"}
  [client-bindings]
  (let [{:keys [file-name version write-chan msg]} client-bindings
        file (io/file @dir "downloads" file-name)
        version-check (or (nil? version)
                          (< (get-in @file-index [file-name :version]) version))
        success (and version-check (.exists file) (io/delete-file file))
        msg (assoc msg :success success)
        msg (dissoc msg :write-chan)]
    (go (>! write-chan (.getBytes (prn-str msg))))
    (if success
      (str "DELETED FILE " file-name)
      (str "FILE " file-name " DOES NOT EXIST OR COULD NOT BE DELETED"))))

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
  [policy]
  (loop []
    (when (and (not= @pid -1) (not= @dir ""))
      (let [files (filter #(not (.isDirectory %)) (seq (.listFiles (io/file @dir))))
            actual-file-names (map #(.getName %) files)
            historical-file-names (map :name @file-history)
            new-file-names (set/difference (set actual-file-names) (set historical-file-names))
            deleted-file-names (set/difference (set historical-file-names) (set actual-file-names))
            invalid-file-names (into deleted-file-names (set (for [file files
                                                                   hist-file @file-history
                                                                   :when (and (= (:name hist-file) (.getName file))
                                                                              (< (:last-modified hist-file) (.lastModified file)))]
                                                               (:name hist-file))))]
        ; register the new files with the index
        (doseq [n new-file-names]
          (register @pid n))

        ; deregister the files that were moved/deleted
        (doseq [d deleted-file-names]
          (deregister @pid d))

        ; give invalid files new version number
        (doseq [invalid-file-name invalid-file-names]
          (dosync
           (alter file-index update-in [invalid-file-name :version] inc)
           (when (contains? @channel-map :index)
             (>!! (:ch (:index @channel-map)) {:type 7
                                               :file-name invalid-file-name
                                               :version (get-in @file-index [invalid-file-name :version])
                                               :host @my-host
                                               :port @my-port})
             )))

        ; TODO Add consistency back in
        ;; (case policy
        ;;   ; If an old file was modified since the last time we checked, 
        ;;   ; send an invalid message to the index, either in a lazy or eager way.
        ;;   "push" (when (contains? @channel-map :index)
        ;;            (doseq [invalid-file-name invalid-file-names]
        ;;              ; send the invalid message to the index
        ;;              (>!! (:ch (:index @channel-map)) {:type 5
        ;;                                                :file-name invalid-file-name
        ;;                                                :host @my-host
        ;;                                                :port @my-port
        ;;                                                :master @pid ; (get-in @file-index [invalid-file-name :master])
        ;;                                                :id (str @pid (swap! message-count inc))}))
        
        ;;            (when (not-empty invalid-file-names)
        ;;              (timbre/debug (str "SENDING INVALIDATE MESSAGE TO INDEX FROM PEER with PORT: " @my-port))
        ;;              (tool/connect-and-collect (:host (:index @channel-map))
        ;;                                        (:port (:index @channel-map))
        ;;                                        (:ch (:index @channel-map)))))
        ;;   "peer-pull" (doseq [[file-name metadata] @file-index]
        ;;                 (when (and (not (nil? (:ttr metadata))) (< (:ttr metadata) (System/currentTimeMillis)))
        ;;                   (let [peer-endpoint-kw (keyword (str (:host metadata) ":" (:port metadata)))
        ;;                         peer-ch (get-in @channel-map [peer-endpoint-kw :ch])]
        ;;                     (>!! peer-ch {:type 8
        ;;                                   :file-name file-name})
        ;;                     (doseq [result (tool/connect-and-collect (:host metadata)
        ;;                                                              (:port metadata)
        ;;                                                              peer-ch)]
        ;;                       (when (or (not (:success result)) (< (:version metadata) (:version result)))
        ;;                         (let [file (io/file @dir "downloads" file-name)
        ;;                               success (and (.exists file) (io/delete-file file))]
        ;;                           (if success
        ;;                             (dosync
        ;;                              (alter file-index dissoc file-name)
        ;;                              (timbre/debug (str "DELETED FILE " file-name)))
        ;;                             (timbre/debug (str "FILE " file-name " DOES NOT EXIST OR COULD NOT BE DELETED")))))))))
        ;;   "index-pull" (when (contains? @channel-map :index)
        ;;                  (>!! (:ch (:index @channel-map)) {:type 9})
        ;;                  (tool/connect-and-collect (:host (:index @channel-map))
        ;;                                            (:port (:index @channel-map))
        ;;                                            (:ch (:index @channel-map))))
        ;;   (timbre/debug (str "NO CONSISTENCY POLICY SET FOR PEER ID: " @pid)))
        
        (dosync (ref-set file-history (vec (for [file files] {:name (.getName file)
                                                              :last-modified (.lastModified file)}))))))
    (Thread/sleep 5000)
    (recur)))

(defn execute
  "Function to execute requests. To be run concurrently with the server event loop."
  {:added "0.1.0"}
  [ch & args]
  (let [fut-ch (chan (dropping-buffer 10000))]
    (dosync
     (ref-set dir (nth args 1))
     (ref-set my-host "127.0.0.1")
     (ref-set my-port (nth args 0)))

    ; start a logging thread
    (thread (logger fut-ch))

    ; start a thread for directory syncing
    (thread (sync-dir (nth args 2)))

    (loop []
      ; take a message off the channel
      (when-let [msg (<!! ch)]
        ; execute it in a future and put the future on the future channel
        ; TODO do the message decomposition in core?
        (let [time (:time msg)
              write-chan (:write-chan msg)
              msg (crypto/decrypt (dissoc msg :time :write-chan) private-key (:n key-pair))
              msg (assoc msg :time time :write-chan write-chan)
              client-bindings {:file-name (:file-name msg)
                               :write-chan (:write-chan msg)
                               :msg msg}]
          (case (:type msg)
            4 (go (>! fut-ch (future (handle-retrieve client-bindings))))
            6 (go (>! fut-ch (future (handle-invalidate client-bindings))))
            8 (go (>! fut-ch (future (handle-version-check client-bindings))))))
        ; poll! for a result of a command, error raised on dereference if the future errored
        (loop []
          (let [v (poll! fut-ch)]
            (when (not (nil? v))
              (timbre/debug @v)
              (recur)))))
      (recur))))