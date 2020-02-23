(ns tyro.index
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts!! thread poll! timeout dropping-buffer chan]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(defn handle-registry
  "Return unique peer ID. Peer needs to remember this number."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index endpoint-index] {:keys [host port write-chan]} :msg} client-bindings
         possible-peer (filter #(= {:host host :port port} (second %)) (seq @endpoint-index))
         peer-id (if (empty? possible-peer)
                   ((fnil inc 0) (first (sort > (keys @p2f-index))))
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
  (let [{:keys [f2p-index endpoint-index] {:keys [file-name write-chan]} :msg} client-bindings
        results (vec (map #(get @endpoint-index %) (get @f2p-index file-name)))]
    (let [msg (assoc (:msg client-bindings) :endpoints results :success true)
          msg (dissoc msg :write-chan)]
      (go (>! write-chan (.getBytes (prn-str msg)))))
    (str "RETURNED RESULTS for file " file-name " to client")))

(defn logger
  "Log the results of the requests."
  {:added "0.1.0"}
  [ch]
  (loop []
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
            3 (timbre/debug (handle-search client-bindings)))))
      (recur))))