(ns tyro.index
  ; (:require [net.async/async "0.1.0"]
  ;           [clojure.tools.logging :as logging])
  )

(defn handle-registry
  "Return unique peer ID. Peer needs to remember this number."
  {:added "0.1.0"}
  [])

(defn handle-register
  "Add the file name to the peer's index."
  {:added "0.1.0"}
  [peer-id file-name])

(defn handle-deregister
  "Remove the file name from the peer's index."
  {:added "0.1.0"}
  [peer-id file-name])

(defn handle-search
  "Search the global index and return the IP/Port pair for peers with that file."
  {:added "0.1.0"}
  [file-name])