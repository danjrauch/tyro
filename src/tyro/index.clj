(ns tyro.index
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts!! thread poll! timeout dropping-buffer chan]]
            [clojure.math.numeric-tower :as math]
            [tyro.crypto :as crypto]
            [tyro.tool :as tool]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def channel-map (ref {}))
(def message-index (ref {}))
(def file-index (ref {}))
(def message-count (atom -1))
(def my-host (ref ""))
(def my-port (ref -1))
(def my-key-pair (crypto/generate-rsa-keypair))
(def my-public-kp (dissoc my-key-pair :d))
(def my-private-key (:d my-key-pair))

(defn add
  "Add an index to the index's peer list."
  {:added "0.2.0"}
  [host port]
  (dosync
   (let [endpoint-keyword (keyword (str host ":" port))]
     (when (not (contains? @channel-map endpoint-keyword))
       (alter channel-map assoc endpoint-keyword {:host host :port port :ch (chan (dropping-buffer 10000))})))))

(defn save-public-kp
  "Ask an index for its public keypair."
  {:added "0.4.0"}
  [host port]
  (dosync
   (let [endpoint-keyword (keyword (str host ":" port))
         ch (chan 1)]
     (when (contains? @channel-map endpoint-keyword)
       (>!! ch {:type 10 :public-kp my-public-kp})
       (let [result (first (tool/connect-and-collect host port ch my-key-pair))]
         (if (and (:success result) (== (:type result) 10))
           (do
             (alter channel-map assoc-in [endpoint-keyword :public-kp] (:public-kp result))
             (timbre/debug (str "SAVED PUBLIC KEYPAIR FOR " host ":" port)))
           (timbre/debug (str "COULD NOT FIND PUBLIC KEYPAIR FOR " host ":" port))))))))

(defn handle-public-kp-query
  "Hand over our public keypair."
  {:added "0.4.0"}
  [client-bindings]
  (let [{:keys [] {:keys [write-chan]} :msg} client-bindings
        msg (assoc (:msg client-bindings)
                   :public-kp my-public-kp
                   :success true)
        msg (dissoc msg :write-chan)]
    (>!! write-chan (.getBytes (prn-str msg)))
    (str "RETURNED PUBLIC KEY")))

(defn handle-registry
  "Return unique peer ID. Peer needs to remember this number."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index p2e-index] {:keys [host port public-kp write-chan]} :msg} client-bindings
         possible-peer (for [[k v] @p2e-index
                             :when (and (= host (:host v)) (= port (:port v)))]
                         k)
         peer-id (if (empty? possible-peer)
                   (math/abs (hash (str tool/get-ip port)))
                   (first possible-peer))]
     (alter p2f-index assoc peer-id #{})
     (alter p2e-index assoc peer-id {:host host
                                     :port port
                                     :public-kp public-kp
                                     :ch (chan (dropping-buffer 10000))})
     (let [msg (assoc (:msg client-bindings) :peer-id peer-id :public-kp my-public-kp :success true)
           msg (dissoc msg :write-chan)]
       (go (>! write-chan (.getBytes (prn-str (crypto/encrypt msg public-kp (:block-size public-kp)))))))
     (str "REGISTERED PEER " host " on port " port " with ID: " peer-id))))

(defn handle-register
  "Add the file name to the peer index."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index p2e-index f2p-index] {:keys [peer-id file-name write-chan]} :msg} client-bindings]
     (when (not (contains? @f2p-index file-name))
       (alter f2p-index assoc file-name #{}))
     (when (not (contains? @p2f-index peer-id))
       (alter p2f-index assoc peer-id #{}))
     (alter f2p-index update file-name conj peer-id)
     (alter p2f-index update peer-id conj file-name)
     (alter file-index assoc file-name {:master peer-id
                                        :version 0})
     (let [msg (assoc (:msg client-bindings) :success true)
           msg (dissoc msg :write-chan)
           peer-public-kp (get-in @p2e-index [peer-id :public-kp])]
       (go (>! write-chan (.getBytes (prn-str (crypto/encrypt msg peer-public-kp (:block-size peer-public-kp)))))))
     (str "REGISTERED FILE " file-name " for ID: " peer-id))))

(defn handle-deregister
  "Remove the file name from the peer's index."
  {:added "0.1.0"}
  [client-bindings]
  (dosync
   (let [{:keys [p2f-index p2e-index f2p-index] {:keys [peer-id file-name write-chan]} :msg} client-bindings]
     (when (contains? @f2p-index file-name)
       (alter f2p-index update file-name disj peer-id))
     (when (contains? @p2f-index peer-id)
       (alter p2f-index update peer-id disj file-name))
     (alter file-index dissoc file-name)
     (let [msg (assoc (:msg client-bindings) :success true)
           msg (dissoc msg :write-chan)
           peer-public-kp (get-in @p2e-index [peer-id :public-kp])]
       (go (>! write-chan (.getBytes (prn-str (crypto/encrypt msg peer-public-kp (:block-size peer-public-kp)))))))
     (str "DEREGISTERED FILE " file-name " for ID: " peer-id))))

(defn handle-versioning
  "Set the version of a file for one of the peers in the file-index."
  {:added "0.3.0"}
  [client-bindings]
  (dosync
   (let [{:keys [] {:keys [file-name version host port write-chan]} :msg} client-bindings
         msg (assoc (:msg client-bindings) :success true)
         msg (dissoc msg :write-chan)]
     (when (contains? @file-index file-name)
       (alter file-index assoc-in [file-name :version] version))
     (go (>! write-chan (.getBytes (prn-str msg))))
     (str "SET VERSION FOR FILE " file-name " TO " version))))

(defn handle-search
  "Search the global index and return the endpoint map for peers with that file."
  {:added "0.1.0"}
  [client-bindings]
  (let [{:keys [f2p-index p2e-index] {:keys [file-name id host port ttl write-chan]} :msg} client-bindings]
    (if (contains? @message-index id)
      (let [msg (assoc (:msg client-bindings)
                       :endpoints []
                       :hit false
                       :success true
                       :ttl (dec ttl))
            msg (dissoc msg :write-chan)]
        (dosync
         (alter message-index update-in [id] + 100))
        (>!! write-chan (.getBytes (prn-str msg)))
        (str "TERMINATED SEARCH FORWARD CHAIN"))
      (let [search-fn (fn [peer-id]
                        (let [res (get @p2e-index peer-id)
                              res (dissoc res :ch)
                              res (assoc res
                                         :master peer-id
                                         :version (get-in @file-index [file-name :version]))]
                          res))
            results (atom (vec (map search-fn (get @f2p-index file-name))))
            msg (assoc (:msg client-bindings)
                       :success true
                       :ttl (dec ttl))
            msg (dissoc msg :write-chan)]
        (dosync
         (alter message-index assoc id (System/currentTimeMillis)))
        (when (pos? ttl)
          (doseq [con-index (vals @channel-map)]
            (>!! (:ch con-index) (crypto/encrypt msg (:public-kp con-index) (:block-size (:public-kp con-index))))
            (timbre/debug (str "FORWARDING A SEARCH REQUEST WITH ID: " id " to PORT: " (:port con-index)))
            (doseq [result (tool/connect-and-collect (:host con-index) (:port con-index) (:ch con-index) my-key-pair)]
              (swap! results into (:endpoints result)))))
        ; add master/version info for index-pull consistency 
        (doseq [result @results]
          (dosync
           (alter file-index assoc file-name {:master (:master result)
                                              :version (:version result)})))
        (>!! write-chan (.getBytes (prn-str (assoc msg :endpoints @results :hit (not (empty? @results))))))
        (str "RETURNED RESULTS for file " file-name " to client")))))

(defn handle-invalidate
  "Forward invalidate message to all peers, then forward message to connected indexes."
  {:added "0.3.0"}
  [client-bindings]
  (let [{:keys [f2p-index p2e-index] {:keys [file-name id host port master write-chan]} :msg} client-bindings]
    (if (contains? @message-index id)
      (let [msg (assoc (:msg client-bindings)
                       :success true)
            msg (dissoc msg :write-chan)]
        (dosync
         (alter message-index update-in [id] + 100))
        (>!! write-chan (.getBytes (prn-str msg)))
        (str "TERMINATED INVALIDATE FORWARD CHAIN"))
      (let [msg (assoc (:msg client-bindings)
                       :success true)
            msg (dissoc msg :write-chan)]
        (dosync
         (alter message-index assoc id (System/currentTimeMillis)))
        ; Send back ACK to peer
        (>!! write-chan (.getBytes (prn-str msg)))
        ; Send invalidate message to peers
        (doseq [peer-id (keys @p2e-index)
                :when (not= master peer-id)
                :let [peer-endpoint (get @p2e-index peer-id)]]
          (>!! (:ch peer-endpoint) {:type 6
                                    :file-name file-name})
          (timbre/debug (str "SENDING AN INVALIDATE REQUEST TO PORT: " (:port peer-endpoint)))
          (tool/connect-and-collect (:host peer-endpoint) (:port peer-endpoint) (:ch peer-endpoint) my-key-pair))
        ; Deregister the file in the local indexes
        ; (handle-deregister )
        ; Forward message to connected indexes
        (doseq [con-index (vals @channel-map)]
          (>!! (:ch con-index) msg)
          (timbre/debug (str "FORWARDING AN INVALIDATE REQUEST WITH ID: " id " to PORT: " (:port con-index)))
          (tool/connect-and-collect (:host con-index) (:port con-index) (:ch con-index) my-key-pair))
        (str "INVALIDATED FILE " file-name " ON ALL PEERS")))))

(defn handle-index-pull
  "Check indexes for new file verison."
  {:added "0.3.0"}
  [client-bindings]
  (let [{:keys [p2e-index] {:keys [write-chan]} :msg} client-bindings
        ch (chan (dropping-buffer 10000))]
    (doseq [[file-name _] @file-index]
      (>!! ch {:type 3
               :file-name file-name
               :host @my-host
               :port @my-port
               :ttl 3
               :id (str "I" (swap! message-count inc))}))
    ; send a search message to ourselves
    (doseq [result (tool/connect-and-collect @my-host @my-port ch my-key-pair)
            peer-endpoint (vals @p2e-index)]
      (when (:hit result)
        (>!! (:ch peer-endpoint) {:type 6
                                  :file-name (:file-name result)
                                  :version (:version (first (:endpoints result)))})
        (tool/connect-and-collect (:host peer-endpoint) (:port peer-endpoint) (:ch peer-endpoint) my-key-pair)))
    (let [msg (assoc (:msg client-bindings)
                     :success true)
          msg (dissoc msg :write-chan)]
      (>!! write-chan (.getBytes (prn-str msg)))
      (str "HANDLED INDEX PULL REQUEST"))))

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
  [ch & args]
  (let [fut-ch (chan (dropping-buffer 10000))
        f2p-index (ref {})
        p2f-index (ref {})
        p2e-index (ref {})]
    (dosync
     (ref-set my-host "127.0.0.1")
     (ref-set my-port (nth args 0)))

    (thread
      (doseq [endpoint (vals @channel-map)]
        (save-public-kp (:host endpoint) (:port endpoint))
        (timbre/debug (str "NEW PUB KEY: " (get-in @channel-map [(keyword (str (:host endpoint) ":" (:port endpoint))) :public-kp])))))

    ; start a logging thread
    (thread (logger fut-ch))

    (loop []
      ; take a message off the channel
      (when-let [msg (<!! ch)]
        ; execute it in a future and put the future on the future channel
        (let [time (:time msg)
              write-chan (:write-chan msg)
              msg (if (some? (:type msg))
                    msg
                    (crypto/decrypt (dissoc msg :time :write-chan) my-private-key (:n my-key-pair)))
              msg (assoc msg :time time :write-chan write-chan)
              client-bindings {:p2f-index p2f-index
                               :f2p-index f2p-index
                               :p2e-index p2e-index
                               :msg msg}]
          (case (:type msg)
            0 (timbre/debug (handle-registry client-bindings))
            1 (timbre/debug (handle-register client-bindings))
            2 (timbre/debug (handle-deregister client-bindings))
            3 (go (>! fut-ch (future (handle-search client-bindings))))
            5 (go (>! fut-ch (future (handle-invalidate client-bindings))))
            7 (go (>! fut-ch (future (handle-versioning client-bindings))))
            9 (go (>! fut-ch (future (handle-index-pull client-bindings))))
            10 (go (>! fut-ch (future (handle-public-kp-query client-bindings)))))))
      (recur))))