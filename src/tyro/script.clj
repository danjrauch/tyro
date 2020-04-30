(ns tyro.script
  (:require [tyro.index :as ind]
            [tyro.peer :as peer]
            [tyro.crypto :as crypto]
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
                                                (:ch (:index @peer/channel-map))))
        num-searches (atom 0)
        num-invalid-results (atom 0)]
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

      (tool/connect-and-collect (:host (:index @peer/channel-map))
                                (:port (:index @peer/channel-map))
                                (:ch (:index @peer/channel-map)))

      (dotimes [_ 1]

        (reset! results [])

        (when (some #{port} [8001 8002 8003 8101 8102 8103])
          (peer/search "1.txt")

          (swap! results into (tool/connect-and-collect (:host (:index @peer/channel-map))
                                                        (:port (:index @peer/channel-map))
                                                        (:ch (:index @peer/channel-map))))

          (let [peer-host (atom "")
                peer-port (atom -1)]
            (doseq [result @results]
              (when (and (== (:type result) 3) (> (count (:endpoints result)) 0))
                (swap! num-searches inc)
                (when (contains? @peer/file-index (:file-name result))
                  (when (< (get-in @peer/file-index [(:file-name result) :version]) (:version (first (:endpoints result))))
                    (timbre/debug (str (:file-name result) " MYPORT: " @peer/my-port " SEARCH PORT: " (:port (first (:endpoints result))) " | " "VERSION: " (get-in @peer/file-index [(:file-name result) :version]) " SEARCH VERSION: " (:version (first (:endpoints result)))))
                    (swap! num-invalid-results inc)))

                (reset! peer-host (:host (first (:endpoints result))))
                (reset! peer-port (:port (first (:endpoints result))))
                (peer/retrieve @peer-host @peer-port (:public-kp (first (:endpoints result))) (:file-name result))

                (swap! results into (tool/connect-and-collect @peer-host
                                                              @peer-port
                                                              (:ch ((keyword (str @peer-host ":" @peer-port)) @peer/channel-map)))))))

          (doseq [result @results]
            (when (== (:type result) 4)
              ;; (let [result (crypto/decrypt result @peer/private-key (:n @peer/key-pair))]
              (timbre/debug (str "SAVING FILE: " (:file-name result)))
              (peer/save-file (:file-name result) (:contents result) {:master (:master result)
                                                                      :refresh-interval (:refresh-interval result)
                                                                      :version (:version result)
                                                                      :host (:host result)
                                                                      :port (:port result)})
                ;; )
              )))

        (Thread/sleep 500)
        (when (== port 8001)
          (spit (str @peer/dir "/1.txt") (apply str (take 10000 (repeatedly #(char (+ (rand 26) 65)))))))
        (Thread/sleep (rand-int 10000)))

      (Thread/sleep 10000)
      
      (timbre/debug (str "NUM SEARCH RESULTS: " @num-searches ", NUM INVALID SEARCH RESULTS: " @num-invalid-results))
      
      @results)))