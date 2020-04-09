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
  (when (not= port 8001)
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
          files (filter #(not (.isDirectory %)) (seq (.listFiles (io/file @peer/dir))))
          file-names (map #(.getName %) files)]
      ; register and deregister the files in our directory
      ; (doseq [file-name file-names]
      ;  (peer/register pid file-name)
      ;  (peer/deregister pid file-name))
      
      ; register them all again
      (doseq [file-name file-names]
        (peer/register pid file-name))

      (dotimes [_ 1]
        (when (not= port 8001)
          (peer/search "1.txt")))

      ; (let [ports-to-run-search [8001]]
      ;   (when (some #{port} ports-to-run-search)
      ;     (dotimes [_ 1]
      ;       (peer/search "1.txt"))))
      
      ; get the results of the previous requests
      (swap! results into (tool/connect-and-collect (:host (:index @peer/channel-map))
                                                    (:port (:index @peer/channel-map))
                                                    (:ch (:index @peer/channel-map))))

      ; (let [total-search-time (atom 0)
      ;       total-searches (atom 0)]
      ;   (doseq [result @results]
      ;     (when (== (:type result) 3)
      ;       (swap! total-search-time + (:time result))
      ;       (swap! total-searches inc)))
      ;   (timbre/debug (str "AVERAGE SEARCH TIME: " (double (/ @total-search-time @total-searches)))))
      
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
          (timbre/debug (str "SAVING FILE: " (:file-name result)))
          (peer/save-file (:file-name result) (:contents result) {:master (:master result)
                                                                  :refresh-interval (:refresh-interval result)
                                                                  :version (:version result)
                                                                  :host (:host result)
                                                                  :port (:port result)})))

      (when (== port 8001)
        (Thread/sleep 20000)
        (spit (str @peer/dir "/1.txt") (apply str (take 10000 (repeatedly #(char (+ (rand 26) 65))))))
        (Thread/sleep 5000))
      
      @results)))