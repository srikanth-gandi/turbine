(ns turbine.routes
    (:require [clojure.core.async :refer [<!! >!! thread alts!! chan close!]]))

;; This returns a transducer that will inject (map identity) when the length
;; of the input channel specifier is less than two.
(defn- alias-injector []
    (map 
        (fn [v]
            (if (> (count v) 1)
                (subvec v 0 2)
                [(first v) (map identity)]))))

(defn- fan-out [route-spec]
    (into {} (alias-injector)
             (nth route-spec 2)))

(defn- fan-in [route-spec]
    (into {} (alias-injector) [(nth route-spec 2)]))

(defmulti xform-aliases first)

(defmethod xform-aliases :scatter [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :splatter [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :select [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :spread [route-spec]
    (fan-out route-spec))

(defmethod xform-aliases :union [route-spec]
    (fan-in route-spec))

(defmethod xform-aliases :gather [route-spec]
    (fan-in route-spec))

(defmethod xform-aliases :in [route-spec]
    (into {} (alias-injector) [(subvec route-spec 1)]))

(defmethod xform-aliases :collect [route-spec]
    (fan-in route-spec))

;;;; There are no aliases in a sinker, but they're in the spec, so this makes
;;;; all of the collection functions applied to the spec consistent.
(defmethod xform-aliases :sink [route-spec] {})

(defmulti make-route 
    "All methods take two arguments, `route-spec`, which is the route specifier,
    and `chans`, which is a map of channel aliases to the channels themselves.
    
    The structure of the route specifier depends on the type of route itself -
    consult the documentation for details.
    "
    (fn [route-spec chans] (first route-spec)))

(defmethod make-route :scatter
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
         ;; The outbound channel aliases are the first elements of the
         ;; third part of the route specifier.
          out-chans (map (fn [o] (chans (first o)))
                         (nth route-spec 2))]
        (thread 
            (loop []
                ;; The loop recursion is contained in when-let, which exits if
                ;; the upstream in-chan is closed.
                (when-let [in-val (<!! in-chan)]
                    (doseq [out-chan out-chans]
                        (>!! out-chan in-val))
                     ;; Continue the loop after sending the input downstream.
                     (recur)))
            ;; After the loop terminates, close the downstream channels.
            (doseq [out-chan out-chans]
                (close! out-chan)))))

(defmethod make-route :splatter
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          ;; The outbound channel aliases are the first elements of the 
          ;; third part of the route specifier.
          out-chans (map (fn [o] (chans (first o)))
                         (nth route-spec 2))]
        (thread 
            (loop []
                ;; Read the sequence from the in-channel.
                (when-let [in-seq (<!! in-chan)]
                    ;; Write each element to it's corresponding out-chan.
                    (doseq [[out-chan out-val] (map vector out-chans in-seq)]
                        (>!! out-chan out-val))
                    (recur)))
            ;; After the loop terminates, close the downstream channels.
            (doseq [out-chan out-chans]
                (close! out-chan)))))

(defmethod make-route :select
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          out-chans-with-selectors
            ;; We need the out-channel alias (o) and the selector value (v).
            ;; The middle element is the xform, which we don't need. 
            (map (fn [[o _ v]] [(chans o) v])
                 (nth route-spec 2))
          selector-fn (nth route-spec 3)]
        (thread 
            (loop []
                ;; Read a single value from in-chan.
                (when-let [in-val (<!! in-chan)]
                           ;; Determine the selector value from selector-fn and 
                           ;; in-val.
                    (let [in-selector-val (selector-fn in-val)]
                        ;; Write in-val to output channels with a matching selector 
                        ;; value.
                        (doseq [[out-chan chan-selector-val] 
                                 out-chans-with-selectors]
                            (when (= in-selector-val chan-selector-val)
                                  (>!! out-chan in-val)))
                    (recur))))
            ;; After the loop exits, close the downstream channels.
            (doseq [[out-chan _] out-chans-with-selectors]
                (close! out-chan)))))

(defmethod make-route :spread
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          ;; Create an infinite (lazy) sequence of out channels to use in the
          ;; loop construct.
          out-chans (cycle (map (fn [o] (chans (first o)))
                           (nth route-spec 2)))]
        (thread
            ;; The loop is initialized with the full out-chans sequence.
            (loop [out-chan-cycle out-chans]
                (when-let [in-val (<!! in-chan)]
                    ;; Drop the input value onto whatever channel is first in
                    ;; the cycle.
                    (>!! (first out-chan-cycle) in-val)
                    ;; Advance the loop by cycling to the next channel.
                    (recur (next out-chan-cycle))))
            ;; After the loop exits, close the downstream channels.
            (doseq [out-chan out-chans]
                (close! out-chan)))))

(defmethod make-route :gather
    [route-spec chans]
    (let [in-chans (map chans (second route-spec))
          out-chan (chans (first (nth route-spec 2)))]
        (thread
            (loop []
                (let [in-vals (for [in-chan in-chans] (<!! in-chan))]
                      (when (not-any? nil? in-vals)
                            ;; Read each value from in-chan.
                            (->> in-vals
                                ;; Convert the values from a seq into a vector.
                                vec
                                ;; Write that vector to the output channel
                                (>!! out-chan))
                                (recur))))
            (close! out-chan))))

(defmethod make-route :union
    [route-spec chans]
    (let [in-chans (map chans (second route-spec))
          out-chan (chans (first (nth route-spec 2)))]
        (thread 
            (loop [in in-chans]
                ;; Read from any of the input channels.
                (let [[in-val in-chan] (alts!! in)]
                    (if-not (nil? in-val)
                        ;; Write to the output if the input isn't nil.
                        (do
                            (>!! out-chan in-val)
                            (recur in))
                        ;; Otherwise drop the channel and keep reading if there
                        ;; are more channels.
                        (let [remaining-chans (remove #{in-chan} in)]
                            (when-not 
                                (empty? remaining-chans)
                                (recur remaining-chans))))))
            (close! out-chan))))

(defmethod make-route :sink
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          sink-fn (nth route-spec 2)]
        (thread 
            (loop []
                ;; When the upstream channel gives nil, end the loop.
                (when-let [in (<!! in-chan)]
                    (sink-fn in)
                    (recur))))))

(defmethod make-route :collect
    [route-spec chans]
    (let [in-chan (chans (second route-spec))
          out-chan (chans (first (nth route-spec 2)))
          reducer (nth route-spec 3)
          ;; Wrap the accumulator in a volatile.
          accumulator (volatile! (nth route-spec 4))]
        (thread
            (loop []
                ;; Update the accumulator with the new value.
                (when-let [in (<!! in-chan)]
                    (vswap! accumulator reducer in)
                    (recur)))
            ;; Flush the accumulator when the topology closes.
            (>!! out-chan @accumulator)
            ;; Close the output channel.
            (close! out-chan))))