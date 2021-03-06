(ns flare.core-test
  (:require [clojure.test :refer :all]
            [flare.core :refer :all]))

(deftest guess-shape-test
  (testing "simple guess-shape"
    (are [x y] (= (guess-shape x) y)
      [1 2 3] [3]
      [[1 2 3]] [1 3]
      [[1 2] [3 4]] [2 2]))
  (testing "bad shapes should throw"
    (let [bad-shapes [[[1 2] [3]]]]
      (doseq [s bad-shapes]
        (is (thrown? RuntimeException (guess-shape s)))))))


(deftest vector-shape-test
  (are [x y] (= (vector-shape? x) y)
    [1] true
    [1 3] false
    [10] true))

(deftest scaler-shape-test
  (are [x y] (= (scalar-shape? x) y)
    [1] true
    [1 3] false
    [10] false))
