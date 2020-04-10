(ns tyro.tool
  (:require [clojure.core.async :refer [<!! <! >! >!! go alts! alts!! poll! close! thread timeout dropping-buffer chan]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [net.async.tcp :refer [event-loop connect accept]]
            [taoensso.timbre :as timbre
             :refer [log  trace  debug  info  warn  error  fatal  report
                     logf tracef debugf infof warnf errorf fatalf reportf
                     spy get-env]])
  (:import (java.net NetworkInterface)))

(def connect-event-loop (event-loop))

(defn load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  {:added "0.2.0"}
  [source]
  (try
    (with-open [r (io/reader source)]
      (edn/read (java.io.PushbackReader. r)))

    (catch java.io.IOException e
      (printf "Couldn't open '%s': %s\n" source (.getMessage e)))
    (catch RuntimeException e
      (printf "Error parsing edn file '%s': %s\n" source (.getMessage e)))))

(defn get-ip
  "Get the IP address of the host machine."
  {:added "0.2.0"}
  []
  (-> (->> (NetworkInterface/getNetworkInterfaces)
           enumeration-seq
           (map bean)
           (mapcat :interfaceAddresses)
           (map bean)
           (filter :broadcast)
           (filter #(= (.getClass (:address %)) java.net.Inet4Address)))
      (nth 0)
      (get :address)
      .getHostAddress))

(defn connect-and-collect
  "Connect to a server and return the results"
  {:added "0.1.0"}
  [host port message-ch]
  (let [client (connect connect-event-loop {:host host :port port})
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
          (do
            (close! (:write-chan client))
            (reset! (:sockets connect-event-loop) #{})
            results)
          (let [read (<!! (:read-chan client))
                response (loop []
                           (if (not (keyword? read))
                             (let [res (edn/read-string (String. read))]
                               (assoc res :time (- (System/currentTimeMillis) (:time res))))
                             (when (and (keyword? read) (not= :connected read))
                               (recur))))]
            (recur (conj results response) (inc i)))))
      [{:type -1 :connection-error true :host host :port port}])))

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

(defn print-peer-stats
  {:added "0.1.0"}
  [all-results]
  (let [all-stats (calculate-stats all-results)
        registry-stats (calculate-stats (filter #(== (:type %) 0) all-results))
        register-stats (calculate-stats (filter #(== (:type %) 1) all-results))
        deregister-stats (calculate-stats (filter #(== (:type %) 2) all-results))
        search-stats (calculate-stats (filter #(== (:type %) 3) all-results))
        retrieve-stats (calculate-stats (filter #(== (:type %) 4) all-results))]
    (println "--------------")
    (println "Results")
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
    (println "--------------")
    (println "Statistics")
    (println "--------------")
    (print)
    (println "Total")
    (println (str "Count: " (:count all-stats) " requests"))
    (println (str "Total time: " (:total-time all-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time all-stats)) " ms."))

    (print)
    (println "Registry")
    (println (str "Count: " (:count registry-stats) " requests"))
    (println (str "Total time: " (:total-time registry-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time registry-stats)) " ms."))

    (print)
    (println "Register")
    (println (str "Count: " (:count register-stats) " requests"))
    (println (str "Total time: " (:total-time register-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time register-stats)) " ms."))

    (print)
    (println "Deregister")
    (println (str "Count: " (:count deregister-stats) " requests"))
    (println (str "Total time: " (:total-time deregister-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time deregister-stats)) " ms."))

    (print)
    (println "Search")
    (println (str "Count: " (:count search-stats) " requests"))
    (println (str "Total time: " (:total-time search-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time search-stats)) " ms."))

    (print)
    (println "Retrieve")
    (println (str "Count: " (:count retrieve-stats) " requests"))
    (println (str "Total time: " (:total-time retrieve-stats) " ms."))
    (println (str "Average time: " (format "%.0f" (:avg-time retrieve-stats)) " ms."))
    (println)))