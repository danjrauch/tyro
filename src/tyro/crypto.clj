(ns tyro.crypto
  (:require [clojure.math.numeric-tower :as math]
            [clojure.set :as set]
            [clojure.edn :as edn]))

(defn pow-mod
  " b^e mod m (using Java which solves some cases the pure clojure method has to be modified to tackle--i.e. with large b & e and 
    calculation simplications when gcd(b, m) == 1 and gcd(e, m) == 1) "
  [b e m]
  (bigint (.modPow (biginteger b) (biginteger e) (biginteger m))))

(defn is-prime
  "Use Miller-Rabin test for testing if a numebr is prime."
  {:added "0.4.0"}
  [n & {:keys [k]
        :or   {k 128}}]
  (cond
    (or (== n 2) (== n 3)) true
    (or (<= n 1) (== (mod n 2) 0)) false
    :else (let [s (atom 0)
                r (atom (- n 1))
                res (atom true)]
            (while (even? @r) ; (bit-and @r 1)
              (swap! s inc')
              (swap! r quot 2))
            (dotimes [_ k]
              (let [a (+' 2 (bigint (bigdec (rand (- n 2)))))
                    x (atom (pow-mod a @r n))]
                (when (and (not= @x 1) (not= @x (- n 1)))
                  (let [j (atom 1)]
                    (while (and (< @j @s) (not= @x (- n 1)))
                      (reset! x (pow-mod @x 2 n))
                      (when (== @x 1)
                        (reset! res true))
                      (swap! j inc'))
                    (when (not= @x (- n 1))
                      (reset! res false))))))
            @res)))

;; (is-prime 48383)

(defn generate-prime-candidate
  ""
  {:added "0.4.0"}
  [& {:keys [minimum maximum]
      :or   {minimum 3000N
             maximum (*' minimum 10000)}}]
  (let [n (+' minimum (bigint (bigdec (rand (- maximum minimum)))))]
    (if (even? n)
      (inc' n)
      n)))

;; (generate-prime-candidate)

(defn generate-prime
  ""
  {:added "0.4.0"}
  [& {:keys [minimum maximum]
      :or   {minimum 3000
             maximum (*' minimum 10)}}]
  (let [p (atom (bigint (generate-prime-candidate :minimum minimum :maximum maximum)))]
    (while (not (is-prime @p))
      (reset! p (bigint (generate-prime-candidate :minimum minimum :maximum maximum))))
    @p))

;; (generate-prime :minimum 2000)

(defn bytes->bigint
  [bytes & {:keys [little-endian]
            :or {little-endian true}}]
  (let [b (if little-endian (reverse bytes) bytes)]
    (->> b
         (cons (byte 0))
         (byte-array)
         (bigint))))

;; (bytes->bigint (.getBytes (prn-str {:type 4})))

(defn bigint->bytes
  [n & {:keys [little-endian]
        :or {little-endian true}}]
  (let [bytes (->> n
                   biginteger
                   .toByteArray)]
    (if little-endian (byte-array (reverse bytes)) (byte-array bytes))))

;; (.getBytes (prn-str {:type 4}))
;; (bigint->bytes (bytes->bigint (.getBytes (prn-str {:type 4}))))

(defn common-factors 
  [n m]
  (into (sorted-set)
        (for [x (range 1 (inc (math/gcd n m)))
              :when (zero? (rem (math/gcd n m) x))]
          x)))

;; (common-factors 4 8)

(defn generate-rsa-keypair
  "Generate a new public key for RSA."
  {:added "0.4.0"}
  []
  (let [e 3
        n (atom -1)
        d (atom -1)
        block-size (atom 1)]
    (while (== @d -1)
      (let [p (generate-prime :minimum 100000000000000N)
            q (generate-prime :minimum 100000000000000N)
            phi (*' (dec p) (dec q))]
        (when (= (common-factors e phi) #{1})
          (reset! n (*' p q))
          (reset! d (bigint (.modInverse (biginteger e) (biginteger phi))))
          (while (<= (bytes->bigint (byte-array (repeat @block-size 127))) (dec @n))
            (swap! block-size inc))
          (swap! block-size dec))))
    (zipmap [:e :n :d :block-size] [e @n @d @block-size])))

;; (generate-rsa-keypair)

(defn encrypt
  "Encrypt a message."
  {:added "0.4.0"}
  [m kp block-size]
  (let [byte-list (cond
                    (= (type m) (Class/forName "[B")) m
                    :else (.getBytes (prn-str m)))]
    (reduce merge (for [[i block] (map-indexed vector (partition block-size block-size nil byte-list))]
                    {i (pow-mod (bytes->bigint (byte-array block)) (:e kp) (:n kp))}))))

(defn decrypt
  "Decrypt a message."
  {:added "0.4.0"}
  [cypher d n]
  (edn/read-string
   (String. (byte-array (mapcat seq (for [[_ v] (into (sorted-map) cypher)]
                                      (bigint->bytes (pow-mod v d n))))))))

(let [whole-kp (generate-rsa-keypair)
      public-kp (dissoc whole-kp :d)
      private-key (:d whole-kp)
      plain {:time 1586479272529, :type 3, :port 8100, :host "127.0.0.1", :ttl 1, :success true, :id "I40", :file-name ".DS_Store"}
      cypher (encrypt plain public-kp (:block-size public-kp))
      decypher (decrypt cypher private-key (:n whole-kp))]
  decypher)

(let [cypher {0 240059982906633545748425936156N, 7 10795711369200399131271460900N, 1 116239426630159719004394060104N, 4 26783796991144353152925634938N, 6 177324462324592318281817477483N, 3 61719847572397334811190449980N, 2 301100204294376624515891889781N, 5 167938864209130777906862299750N, 8 207662158087728074009338379014N}
      private-key 229564912996267250456359545115
      n 344347369494402049311443784691]
  (decrypt cypher private-key n))