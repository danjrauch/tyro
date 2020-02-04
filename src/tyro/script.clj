(ns tyro.script
  (:require [tyro.index :as ind]
            [tyro.peer :as peer]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]]))

(def _R_ "\u001B[0m")
(def _B "\u001B[30m")
(def _R "\u001B[31m")
(def _G "\u001B[32m")
(def _Y "\u001B[33m")
(def _B "\u001B[34m")
(def _P "\u001B[35m")
(def _C "\u001B[36m")
(def _W "\u001B[37m")

(defmulti prints
  (fn [f, & arg] (class arg)))

(defmethod prints :default [f, & arg]
  (doseq [item arg] (f item))
  (flush))

(defmethod prints String [f, & arg]
  (f arg)
  (flush))

(defmethod prints Character [f, & arg]
  (f arg)
  (flush))

(defn calculate-stats
  {:added "0.1.0"}
  [results]
  (let [total-time (reduce (fn [t r]
                             (+ t (:time r)))
                           0 results)
        total-count (count results)
        avg-time (double (/ total-time (if (== total-count 0)
                                         1
                                         total-count)))]
    {:count total-count
     :total-time total-time
     :avg-time avg-time}))

(defn register-stress
  []
  (peer/set-index "127.0.0.1" 8715)
  (dotimes [_ 1000]
    (peer/register 1 "1.txt"))
  (peer/search "1.txt")

  (let [all-results (peer/connect-and-collect (:host (:index @peer/channel-map))
                                              (:port (:index @peer/channel-map))
                                              (:ch (:index @peer/channel-map)))
        all-stats (calculate-stats all-results)
        registry-stats (calculate-stats (filter #(== (:type %) 0) all-results))
        register-stats (calculate-stats (filter #(== (:type %) 1) all-results))
        deregister-stats (calculate-stats (filter #(== (:type %) 2) all-results))
        search-stats (calculate-stats (filter #(== (:type %) 3) all-results))
        retrieve-stats (calculate-stats (filter #(== (:type %) 4) all-results))]
    (println "--------------" _B)
    (println "Results" _R_)
    (println "--------------")
    (loop [results all-results
           i 0]
      (let [res (first results)
            res (if (and (== (:type res) 4) (> (count (:contents res)) 10))
                  (assoc res :contents (str (subs (:contents res) 0 10) "..."))
                  res)]
        (if (< i 5)
          (println res)
          (when (and (< i 8) (> (count all-results) 8))
            (println ".")))
        (when (and (seq (rest results)) (< i 10))
          (recur (rest results) (inc i)))))
    (println "--------------" _R)
    (println "Statistics" _R_)
    (println "--------------")
    (print _P)
    (println "Total" _R_)
    (println (str "Count: " (:count all-stats) " requests"))
    (println (str "Total time: " (:total-time all-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time all-stats)) " ms."))

    (print _P)
    (println "Registry" _R_)
    (println (str "Count: " (:count registry-stats) " requests"))
    (println (str "Total time: " (:total-time registry-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time registry-stats)) " ms."))

    (print _P)
    (println "Register" _R_)
    (println (str "Count: " (:count register-stats) " requests"))
    (println (str "Total time: " (:total-time register-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time register-stats)) " ms."))

    (print _P)
    (println "Deregister" _R_)
    (println (str "Count: " (:count deregister-stats) " requests"))
    (println (str "Total time: " (:total-time deregister-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time deregister-stats)) " ms."))

    (print _P)
    (println "Search" _R_)
    (println (str "Count: " (:count search-stats) " requests"))
    (println (str "Total time: " (:total-time search-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time search-stats)) " ms."))

    (print _P)
    (println "Retrieve" _R_)
    (println (str "Count: " (:count retrieve-stats) " requests"))
    (println (str "Total time: " (:total-time retrieve-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time retrieve-stats)) " ms."))
    (println)))