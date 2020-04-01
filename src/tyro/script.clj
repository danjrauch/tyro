(ns tyro.script
  (:require [tyro.index :as ind]
            [tyro.peer :as peer]
            [tyro.tool :as tool]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(defn stress-test
  [host port]
  (when (not (== port 8001))
    (Thread/sleep 10000))
  (when (not (contains? @peer/channel-map :index))
    (peer/set-index host 8715))
  (peer/registry host port)

  (let [results (atom (tool/connect-and-collect (:host (:index @peer/channel-map))
                                                (:port (:index @peer/channel-map))
                                                (:ch (:index @peer/channel-map))))]
    (doseq [result @results]
      (when (== (:type result) 0)
        (dosync (ref-set peer/pid (:peer-id result)))))

    (let [pid @peer/pid
          files (filter #(not (.isDirectory %)) (file-seq (io/file @peer/dir)))
          file-names (map #(.getName %) files)]
      ; register and deregister the files in our directory
      ; (doseq [file-name file-names]
      ;  (peer/register pid file-name)
      ;  (peer/deregister pid file-name))
      
      ; register them all again
      (doseq [file-name file-names]
        (peer/register pid file-name))

      ; TODO input file name to search for
      ; search for the file 1.txt
      
      (dotimes [_ 1]
        (peer/search "1.txt"))

      ; [8001 8101 8201 8301 8401 8501 8601 8701 8801 8901]
      
      ; (let [ports-to-run-search [8001]]
      ;   (when (some #{port} ports-to-run-search)
      ;     (dotimes [_ 1]
      ;       (peer/search "1.txt"))))

      ; get the results of the previous requests
      (swap! results into (tool/connect-and-collect (:host (:index @peer/channel-map))
                                                    (:port (:index @peer/channel-map))
                                                    (:ch (:index @peer/channel-map))))

      (let [total-search-time (atom 0)
            total-searches (atom 0)]
        (doseq [result @results]
          (when (== (:type result) 3)
            (swap! total-search-time + (:time result))
            (swap! total-searches inc)))
        (timbre/debug (str "AVERAGE SEARCH TIME: " (double (/ @total-search-time @total-searches)))))

      ; connect to the peer with 1.txt and download it
      (let [peer-host (atom "")
            peer-port (atom -1)]
        (doseq [result @results]
          (when (and (== (:type result) 3) (> (count (:endpoints result)) 0))
            (reset! peer-host (:host (nth (:endpoints result) 0)))
            (reset! peer-port (:port (nth (:endpoints result) 0)))
            (peer/retrieve @peer-host @peer-port (:file-name result))

            (swap! results into (tool/connect-and-collect @peer-host
                                                          @peer-port
                                                          (:ch ((keyword (str @peer-host ":" @peer-port)) @peer/channel-map)))))))

      ; save 1.txt to our directory
      (doseq [result @results]
        (when (== (:type result) 4)
          (peer/save-file (:file-name result) (:contents result))))

      @results)))