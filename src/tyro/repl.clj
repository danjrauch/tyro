(ns tyro.repl
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.set :refer :all]
            [clojure.java.io :as io]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clojure.pprint :as pprint]
            [spinner.core :as spin]
            [trptcolin.versioneer.core :as version]
            [tyro.peer :as peer])
  (:use clojure.java.shell))

;; Enhancement from TARS cli library

(def cli-options
  [["-h" "--host HOST" "Host"]
   ["-p" "--port PORT" "Port number"
    :default 8000
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   [nil "--pid ID" "Peer ID"
    :parse-fn #(Integer/parseInt %)]
   ["-f" "--file FILENAME" "File name"]
   [nil "--path PATH" "Path to test"]
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc]
   [nil "--help"]])

(def ascii_up 65)
(def ascii_down 66)
(def ascii_right 67)
(def ascii_left 68)
(def ascii_enter 10)
(def ascii_left_bracket 91)
(def ascii_escape 27)
(def ascii_backspace 127)
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

(defn- print-prompt
  "Prints the command prompt."
  {:added "0.1.0"}
  []
  (print "tyro=> ") (flush))

(defn- move-cursor-to-pos
  "Move the cursor to the specified position on the screen."
  {:added "0.1.0"}
  [pos cursor_pos]
  (if (> pos cursor_pos)
    (dotimes [_ (- pos cursor_pos)]
      (print (char ascii_escape)) (print (char ascii_left_bracket)) (print (char ascii_right)) (flush))
    (dotimes [_ (- cursor_pos pos)]
      (print (char ascii_escape)) (print (char ascii_left_bracket)) (print (char ascii_left)) (flush))))

(defn- clean-command-line
  "Cleans the command line."
  {:added "0.1.0"}
  [buffer cursor_pos]
  (when (> (count buffer) cursor_pos)
    (move-cursor-to-pos (count buffer) cursor_pos))
  (loop [new_pos (count buffer)]
    (when (> new_pos 0)
      (prints print "\b \b")
      (recur (dec new_pos)))))

(defn- delete-char
  "Removes character before the cursor from the string."
  {:added "0.1.0"}
  [buffer cursor_pos]
  (str (subs buffer 0 (dec cursor_pos)) (when (< cursor_pos (count buffer)) (subs buffer cursor_pos))))

(defn- insert-char
  "Add character after the cursor from the string."
  {:added "0.1.0"}
  [buffer cursor_pos c]
  (cond
    (= cursor_pos (count buffer)) (str buffer c)
    :else (str (subs buffer 0 cursor_pos) c (subs buffer cursor_pos))))

(defn- refresh-command-line
  "Erase current buffer string, print new buffer string, move cursor to correct position."
  {:added "0.1.0"}
  [buffer new_buffer cursor_pos next_pos]
  (clean-command-line buffer cursor_pos)
  (prints print new_buffer)
  (move-cursor-to-pos next_pos (count new_buffer)))

(defmacro handle-backspace
  "Handles the backspace key stroke. It deletes the chars on the left hand side of the cursor."
  {:added "0.1.0"}
  [buffer cursor_pos]
  `(if (and (not-empty ~buffer) (> ~cursor_pos 0))
     (do
       (refresh-command-line ~buffer (delete-char ~buffer ~cursor_pos) ~cursor_pos (dec ~cursor_pos))
       (recur (delete-char ~buffer ~cursor_pos) (dec ~cursor_pos)))
     (recur ~buffer 0)))

(defmacro handle-left
  "Handles left key stroke that moves the cursor to the left if possible."
  {:added "0.1.0"}
  [buffer cursor_pos]
  `(if (> ~cursor_pos 0)
     (do
       (print (char ascii_escape))
       (print (char ascii_left_bracket))
       (print (char ascii_left))
       (flush)
       (recur ~buffer (dec ~cursor_pos)))
     (recur ~buffer ~cursor_pos)))

(defmacro handle-right
  "Handles right key stroke that moves the cursor to the right if possible."
  {:added "0.1.0"}
  [buffer cursor_pos]
  `(if (< ~cursor_pos (count ~buffer))
     (do
       (print (char ascii_escape))
       (print (char ascii_left_bracket))
       (print (char ascii_right))
       (flush)
       (recur ~buffer (inc ~cursor_pos)))
     (recur ~buffer ~cursor_pos)))

(def history_cursor (atom -1))
(def command_history (atom '()))
(def current_buffer (atom {:buffer "" :cursor_pos 0}))

; (defn- refresh-command-line
;   "Erase current buffer string, print new buffer string, move cursor to correct position."
;   [buffer new_buffer cursor_pos next_pos]

(defmacro handle-down
  "Handles down key stroke that moves the history_cursor through the command history."
  {:added "0.1.0"}
  [buffer cursor_pos]
  `(if (>= (dec @history_cursor) 0)
     (do
       (swap! history_cursor dec)
       (def new_buffer (nth @command_history @history_cursor))
       (refresh-command-line ~buffer new_buffer ~cursor_pos (count new_buffer))
       (recur new_buffer (count new_buffer)))
     (do
       (when (= @history_cursor 0) (swap! history_cursor dec))
       (refresh-command-line ~buffer (:buffer @current_buffer) ~cursor_pos (:cursor_pos @current_buffer))
       (recur (:buffer @current_buffer) (:cursor_pos @current_buffer)))))

(defmacro handle-up
  "Handles up key stroke that moves the history_cursor through the command history."
  {:added "0.1.0"}
  [buffer cursor_pos]
  `(if (< (inc @history_cursor) (count @command_history))
     (do
       (when (= @history_cursor -1) (reset! current_buffer {:buffer ~buffer :cursor_pos ~cursor_pos}))
       (swap! history_cursor inc)
       (def new_buffer (nth @command_history @history_cursor))
       (refresh-command-line ~buffer new_buffer ~cursor_pos (count new_buffer))
       (recur new_buffer (count new_buffer)))
     (recur ~buffer ~cursor_pos)))

(def required-args
  {:registry [:host :port]
   :register [:pid :file]
   :deregister [:pid :file]
   :search [:file]
   :retrieve [:host :port :file]
   :index [:host :port]
   :perf [:path]})

(defn get-results
  ""
  {:added "0.1.0"}
  [endpoints]
  (loop [points endpoints
         all-results []]
    (if (empty? points)
      all-results
      (let [point (keyword (first points))
            info (point @peer/channel-map)
            results (peer/connect-and-collect (:host info) (:port info) (:ch info))]
        (recur (rest points) (into all-results results))))))

(defn execute-command
  "Execute a command for the repl"
  {:added "0.1.0"}
  [f required opts]
  (if (empty? (difference (set required) (set (keys (:options opts)))))
    (apply f (map #(% (:options opts)) required))
    false))

(defn calculate-stats
  {:added "0.1.0"}
  [results]
  (let [total-time (reduce (fn [t r]
                             (+ t (:time r)))
                           0 results)
        avg-time (double (/ total-time (count results)))]
    {:count (count results)
     :total-time total-time
     :avg-time avg-time}))

(defn handle-input
  ""
  {:added "0.1.0"}
  [args]
  (let [opts (parse-opts args cli-options)]
    (cond
      (:errors opts) (do (println)
                         (print (first (:errors opts)))
                         (println))
      (contains? (:options opts) :help) (do (println)
                                            (println "Provide only one arguemnt out of [registry register deregister search retrieve exec perf exit]")
                                            (println (:summary opts)))
      (== (count (:arguments opts)) 1) (case (first (:arguments opts))
                                         "registry" (execute-command peer/registry (:registry required-args) opts)
                                         "register" (execute-command peer/register (:register required-args) opts)
                                         "deregister" (execute-command peer/deregister (:deregister required-args) opts)
                                         "search" (execute-command peer/search (:search required-args) opts)
                                         "retrieve" (execute-command peer/retrieve (:retrieve required-args) opts)
                                         "index" (execute-command peer/set-index (:index required-args) opts)
                                         "exec" (do
                                                  (println "")
                                                  (doseq [res (get-results (keys @peer/channel-map))]
                                                    (if (and (== (:type res) 4) (> (count (:contents res)) 10))
                                                      (do
                                                        (peer/save-file (:file-name res) (:contents res))
                                                        (println (assoc res :contents (str (subs (:contents res) 0 10) "..."))))
                                                      (println res))))
                                         "perf" (if (empty? (difference (set (:perf required-args)) (set (keys (:options opts)))))
                                                  (if (.exists (io/file (:path (:options opts))))
                                                    (with-open [rdr (io/reader (:path (:options opts)))]
                                                      (println "")
                                                      (println "--------------" _C)
                                                      (println "Commands" _R_)
                                                      (println "--------------")
                                                      (doseq [[i command] (map-indexed (fn [i itm] [i itm]) (line-seq rdr))]
                                                        (if (< i 5)
                                                          (println command)
                                                          (when (< i 8)
                                                            (println ".")))
                                                        (let [args (str/split (str/trim command) #" ")
                                                              opts (parse-opts args cli-options)]
                                                          (if (and (== (count (:arguments opts)) 1) (= (first (:arguments opts)) "exec"))
                                                            (let [all-results (get-results (keys @peer/channel-map))
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
                                                                    (when (< i 8)
                                                                      (println ".")))
                                                                  (when (< i 10)
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
                                                              (println))
                                                            (handle-input args)))))
                                                    (println " File does not exist."))
                                                  (println " Error"))
                                         "exit" (do (println) (System/exit 0)))
      :else (do (println)
                (println "Provide only one arguemnt out of [registry register deregister search retrieve exec perf exit]")
                (println (:summary opts))))))

(defn repl
  "Read-Eval-Print-Loop implementation."
  {:added "0.1.0"}
  []
  (print-prompt)
  (loop [buffer "" cursor_pos 0]
    (let [input_char (.read System/in)]
      (cond
        (= input_char ascii_escape)
        (do
          ;; by-pass the first char after escape-char.
          (.read System/in)
          (let [escape-char (.read System/in)]
            ;; Handle navigation keys, left and right key strokes.
            (cond
              (= escape-char ascii_right)
              (handle-right buffer cursor_pos)
              (= escape-char ascii_left)
              (handle-left buffer cursor_pos)
              (= escape-char ascii_up)
              (handle-up buffer cursor_pos)
              (= escape-char ascii_down)
              (handle-down buffer cursor_pos))))
        ;; On-enter pressed.
        (= input_char ascii_enter)
        (do
          (reset! history_cursor -1)
          (move-cursor-to-pos (count buffer) cursor_pos)
          (when (not (str/blank? buffer)) (swap! command_history conj buffer))
          (print " ")
          (cond
            ; (nil? buffer) ""
            (str/blank? buffer) (do (println) "")
            :else (handle-input (str/split (str/trim buffer) #" "))))
        ;; On-backspace entered.
        (= input_char ascii_backspace)
        (handle-backspace buffer cursor_pos)
        ;; default case
        :else
        (do
          (refresh-command-line buffer (insert-char buffer cursor_pos (char input_char)) cursor_pos (inc cursor_pos))
          (recur (insert-char buffer cursor_pos (char input_char)) (inc cursor_pos)))))))

(defn addShutdownHook
  "Add a function as shutdown hook on JVM exit."
  {:added "0.1.0"}
  [func]
  (.addShutdownHook (Runtime/getRuntime) (Thread. func)))

(defn turn-char-buffering-on
  {:added "0.1.0"}
  []
  (sh "sh" "-c" "stty -g < /dev/tty")
  (sh "sh" "-c" "stty -icanon min 1 < /dev/tty")
  (sh "sh" "-c" "stty -echo </dev/tty"))

(defn turn-char-buffering-off
  {:added "0.1.0"}
  []
  (flush)
  (sh "sh" "-c" "stty echo </dev/tty"))

(defn start-repl
  "Starts the repl session"
  {:added "0.1.0"}
  []
  (prints print (clojure.string/replace (slurp "resources/branding") #"VERSION" (version/get-version "GROUP-ID" "ARTIFACT-ID")))
  (addShutdownHook (fn [] (turn-char-buffering-off)))
  (turn-char-buffering-on)
  (while true (repl))
  (System/exit 0))