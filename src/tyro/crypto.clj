(ns tyro.crypto
  (:require [clojure.math.numeric-tower :as math]))

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

(is-prime 48383)

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

(generate-prime-candidate)

(defn generate-prime
  ""
  {:added "0.4.0"}
  [& {:keys [minimum maximum]
      :or   {minimum 3000
             maximum (*' minimum 10)}}]
  (let [p (atom (bigint (generate-prime-candidate :minimum minimum)))]
    (while (not (is-prime @p))
      (reset! p (bigint (generate-prime-candidate :minimum minimum))))
    @p))

(generate-prime :minimum 2000)