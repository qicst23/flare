(ns flare.examples.bilstm-tag
  (:gen-class)
  (:require [flare.node :as node]
            [flare.rnn :as rnn]
            [flare.compute :as compute]
            [flare.embeddings :as embeddings]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [flare.neanderthal-ops :as no]
            [flare.model :as model]
            [flare.report :as report]
            [flare.computation-graph :as cg]
            [flare.train :as train]
            [flare.module :as module]
            [flare.optimize :as optimize]
            [flare.core :as flare]))

(def cli-options
  ;; An option with a required argument
  [["-train" "--train-file PATH" "path to data"
    :default "data/sentiment-train10k.txt"]
   ["-test" "--test-file PATH" "path to test"
    :default "data/sentiment-test10k.txt"]
   ["-e" "--embed-file PATH" "path to data"
    :default "data/glove.6B.300d.txt"]
   ["-c" "--num-classes PATH" "path to data"
    :default 2
    :parse-fn #(Integer/parseInt ^String %)]
   ["s" "--emb-size NUM" "size of embedding data"
    :default 300
    :parse-fn #(Integer/parseInt ^String %)]
   ["l" "--lstm-size NUM" "lstm size"
    :default 25
    :parse-fn #(Integer/parseInt ^String %)]
   ["-n"  "--num-data DATA"
    :default 2000
    :parse-fn #(Integer/parseInt ^String %)]])

(defn load-embeddings [opts]
  (embeddings/fixed-embedding
   (no/factory)
   (:emb-size opts)
   (-> opts :embed-file io/reader embeddings/read-text-embedding-pairs)))

(defn lstm-sent-classifier [model word-emb lstm-size num-classes]
  (node/let-scope
      ;; let-scope so the parameters get smart-nesting
      [emb-size (embeddings/embedding-size word-emb)
       num-dirs 2
       input-size (* num-dirs emb-size)
       hidden-size (* num-dirs lstm-size)
       lstm (rnn/lstm-cell model input-size hidden-size)
       factory (model/tensor-factory model)
       hidden->logits (module/affine model num-classes [hidden-size])]
    (reify
      module/PModule
      ;; build logits
      (graph [this sent]
        (when-let [inputs (seq (embeddings/sent-nodes factory word-emb sent))]
          (let [[outputs _] (rnn/build-seq lstm inputs (= num-dirs 2))
                train? (:train? (meta this))
                hidden (last outputs)
                hidden (if train? (cg/dropout 0.5 hidden) hidden)]
            (module/graph hidden->logits hidden))))
      ;; build loss node for two-arguments
      (graph [this sent label]
        (when-let [logits (module/graph this sent)]
          (let [label-node (node/constant factory "label" [label])]
            (cg/cross-entropy-loss logits label-node)))))))

(defn load-data [path]
  (for [line (line-seq (io/reader path))
        :let [[tag & sent] (.split (.trim ^String line) " ")]]
    [sent (double (Integer/parseInt tag))]))

(defn train [{:keys [lstm-size, num-classes, train-file, test-file] :as opts}]
  (let [emb (load-embeddings opts)
        train-data (take (:num-data opts) (load-data train-file))
        test-data (take (:num-data opts) (load-data test-file))
        gen-batches #(partition-all 32 train-data)
        factory (no/factory)
        model (model/simple-param-collection factory)
        ;; classifier can use a cache to avoid
        ;; re-allocating tensors across prediction
        classifier (lstm-sent-classifier model emb lstm-size num-classes)
        loss-fn (fn [[sent tag]]
                    (-> classifier
                        (with-meta {:train? true})
                        (module/graph sent tag)))
        predict-fn (module/predict-fn factory classifier)
        train-opts {:num-iters 100
                    :optimizer (optimize/->Adadelta factory 1.0 0.9 1e-6)
                    ;; report train/test accuracy each iter
                    :iter-reporter
                    [(report/accuracy
                      :train-accuracy
                      (constantly train-data)
                      predict-fn)
                     (report/accuracy
                      :test-accuracy
                      (constantly test-data)
                      predict-fn)
                     (report/callback
                      #(flare/debug-info factory))]
                    :learning-rate 1}]
    (println "Params " (map first (seq model)))
    (println "Total # params " (model/total-num-params model))
    (train/train! model loss-fn gen-batches train-opts)))

(comment
  (def opts {:embed-file "data/small-glove.50d.txt"
             :lstm-size 100
             :num-classes 2
             :num-data 100
             :train-file "data/sentiment-train10k.txt"
             :test-file "data/sentiment-test10k.txt"
             :emb-size 50}))

(defn -main [& args]
  (let [parse (parse-opts args cli-options)]
    (println (:options parse))
    (train (:options parse))))