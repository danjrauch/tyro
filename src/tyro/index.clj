(ns tyro.index
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts!! thread poll! timeout dropping-buffer chan]]
            [tyro.tool :as tool]
            [clojure.math.numeric-tower :as math]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def channel-map (ref {}))
(def message-index (ref {}))

(defn add
  "Add an index to the index's peer list."
  {:added "0.2.0"}
  [host port]
  (dosync
   (let [endpoint-keyword (keyword (str host ":" port))]
     (when (not (contains? @channel-map endpoint-keyword))
       (alter channel-map assoc endpoint-keyword {:host host :port port :ch (chan (dropping-buffer 10000))})))))

(defn handle-registry
  "Return unique peer ID. Peer needs to remember this number."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index endpoint-index] {:keys [host port write-chan]} :msg} client-bindings
         possible-peer (filter #(= {:host host :port port} (second %)) (seq @endpoint-index))
         peer-id (if (empty? possible-peer)
                   ; TODO Research an even better solution than this
                   ; ((fnil inc 0) (first (sort > (keys @p2f-index))))
                   ;  (rand-int 1000000)
                   (math/abs (hash (str tool/get-ip port)))
                   (first (first possible-peer)))]
     (alter p2f-index assoc peer-id #{})
     (alter endpoint-index assoc peer-id {:host host
                                          :port port})
     (let [msg (assoc (:msg client-bindings) :peer-id peer-id :success true)
           msg (dissoc msg :write-chan)]
       (go (>! write-chan (.getBytes (prn-str msg)))))
     (str "REGISTERED PEER " host " on port " port " with ID: " peer-id))))

(defn handle-register
  "Add the file name to the peer index."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index f2p-index] {:keys [peer-id file-name write-chan]} :msg} client-bindings]
     (when (not (contains? @f2p-index file-name))
       (alter f2p-index assoc file-name #{}))
     (when (not (contains? @p2f-index peer-id))
       (alter p2f-index assoc peer-id #{}))
     (alter f2p-index update file-name conj peer-id)
     (alter p2f-index update peer-id conj file-name)
     (let [msg (assoc (:msg client-bindings) :success true)
           msg (dissoc msg :write-chan)]
       (go (>! write-chan (.getBytes (prn-str msg)))))
     (str "REGISTERED FILE " file-name " for ID: " peer-id))))

(defn handle-deregister
  "Remove the file name from the peer's index."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index f2p-index] {:keys [peer-id file-name write-chan]} :msg} client-bindings]
     (when (contains? @f2p-index file-name)
       (alter f2p-index update file-name disj peer-id))
     (when (contains? @p2f-index peer-id)
       (alter p2f-index update peer-id disj file-name))
     (let [msg (assoc (:msg client-bindings) :success true)
           msg (dissoc msg :write-chan)]
       (go (>! write-chan (.getBytes (prn-str msg)))))
     (str "DEREGISTERED FILE " file-name " for ID: " peer-id))))

(defn handle-search
  "Search the global index and return the endpoint map for peers with that file."
  {:added "0.1.0"}
  [client-bindings]
  (let [{:keys [f2p-index endpoint-index] {:keys [file-name id host port ttl write-chan]} :msg} client-bindings]
    (if (contains? @message-index id)
      (let [msg (assoc (:msg client-bindings)
                       :endpoints []
                       :hit false
                       :success true
                       :ttl (dec ttl))
            msg (dissoc msg :write-chan)]
        (dosync 
         (alter message-index update-in [id] + 100))
        (>!! write-chan (.getBytes (prn-str msg))))
      (let [results (atom (vec (map #(get @endpoint-index %) (get @f2p-index file-name))))
            msg (assoc (:msg client-bindings)
                       :success true
                       :ttl (dec ttl))
            msg (dissoc msg :write-chan)]
        (dosync
         (alter message-index assoc id (System/currentTimeMillis)))
        (when (pos? ttl)
          ; (timbre/debug (str (vec (map :port (vals @channel-map)))))
          (doseq [con-index (vals @channel-map)]
            (>!! (:ch con-index) msg)
            (timbre/debug (str "FORWARDING A SEARCH REQUEST WITH ID: " id " to PORT: " (:port con-index)))
            (doseq [result (tool/connect-and-collect (:host con-index) (:port con-index) (:ch con-index))]
              (swap! results into (:endpoints result)))))
        (>!! write-chan (.getBytes (prn-str (assoc msg :endpoints @results :hit (not (empty? @results))))))))
    (str "RETURNED RESULTS for file " file-name " to client")))

(defn logger
  "Log the results of the requests."
  {:added "0.1.0"}
  [ch]
  (loop []
    (doseq [[k v] @message-index]
      (when (< v (- (System/currentTimeMillis) 500))
        (dosync
         (alter message-index dissoc k))))
    (let [v (poll! ch)]
      (when (not (nil? v))
        (timbre/debug @v))
      (recur))))

(defn execute
  "Function to execute requests. To be run concurrently with the server event loop."
  {:added "0.1.0"}
  [ch & _]
  (let [fut-ch (chan (dropping-buffer 10000))
        f2p-index (ref {})
        p2f-index (ref {})
        endpoint-index (ref {})]

    ; start a logging thread
    (thread (logger fut-ch))

    (loop []
      ; take a message off the channel
      (when-let [msg (<!! ch)]
        ; execute it in a future and put the future on the future channel
        (let [client-bindings {:p2f-index p2f-index
                               :f2p-index f2p-index
                               :endpoint-index endpoint-index
                               :msg msg}]
          (case (:type msg)
            ; 0 (go (>! fut-ch (future (handle-registry client-bindings))))
            ; 1 (go (>! fut-ch (future (handle-register client-bindings))))
            ; 2 (go (>! fut-ch (future (handle-deregister client-bindings))))
            ; 3 (go (>! fut-ch (future (handle-search client-bindings))))
            
            0 (timbre/debug (handle-registry client-bindings))
            1 (timbre/debug (handle-register client-bindings))
            2 (timbre/debug (handle-deregister client-bindings))
            3 (go (>! fut-ch (future (handle-search client-bindings))))
            ;; 3 (timbre/debug (handle-search client-bindings))
            )))
      (recur))))