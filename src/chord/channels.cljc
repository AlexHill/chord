(ns chord.channels
  (:require [chord.format :as cf]
            #?(:clj [org.httpkit.server :as http])

            #?(:clj
               [clojure.core.async :refer [chan <! >! put! close! go-loop]]
               :cljs
               [cljs.core.async :refer [chan put! close! <! >!]])

            #?(:clj
               [clojure.core.async.impl.protocols :as p]
               :cljs
               [cljs.core.async.impl.protocols :as p]))

  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]])))

(defn close-ws! [ws]
  #?(:clj
     (when (http/open? ws)
       (http/close ws))
     :cljs
     (.close ws)))

(defn read-from-ws! [ws ch fmtr]
  #?(:clj
     (http/on-receive ws #(put! ch (cf/thaw-message fmtr %)))
     :cljs
     (set! (.-onmessage ws) #(put! ch (cf/thaw-message fmtr (.-data %))))))

(defn write-to-ws! [ws ch fmtr]
  (go-loop []
    (if-some [msg (<! ch)]
      (let [msg (cf/freeze fmtr msg)]
        #?(:clj
           (http/send! ws msg)
           :cljs
           (.send ws msg))
        (recur))
      (close-ws! ws))))

(defn on-close [ws read-ch write-ch]
  (let [close-read (fn [_] (close! read-ch))]
    #?(:clj
       (http/on-close ws close-read)
       :cljs
       (.-addEventListener ws "close" close-read))))

(defn bidi-ch [read-ch write-ch]
  (reify
    p/ReadPort
    (take! [_ handler]
      (p/take! read-ch handler))

    p/WritePort
    (put! [_ msg handler]
      (p/put! write-ch msg handler))

    p/Channel
    (close! [_]
      (p/close! write-ch))
    (closed? [_]
      (p/closed? read-ch))))

(defn deprecated-args?
  [_ opts]
  (let [chans (boolean (some opts [:read-chan :write-chan]))
        chs (boolean (some opts [:read-ch :write-ch]))]
    (condp = [chans chs]
      [false false] :standard-args
      [true false] :standard-args
      [false true] :deprecated-args
      [true true] :invalid-args)))

(defmulti core-async-ch deprecated-args?)

(defmethod core-async-ch :invalid-args
  [_ _]
  (throw (IllegalArgumentException. "The :read/write-ch and :read/write-chan options may not be used together")))

(defmethod core-async-ch :standard-args
  [ws-conn {:keys [read-chan write-chan]
            :or   {read-chan  (chan)
                   write-chan (chan)}
            :as   opts}]
  (on-close ws-conn read-chan write-chan)
  (let [fmtr (cf/formatter (dissoc opts :read-chan :write-chan))]
    (read-from-ws! ws-conn read-chan fmtr)
    (write-to-ws! ws-conn write-chan fmtr)
    ^{:platform-ws ws-conn
      :read-chan read-chan
      :write-chan write-chan}
    (bidi-ch read-chan write-chan)))

(defmethod core-async-ch :deprecated-args
  [ws-conn {:keys [read-ch write-ch] :as opts}]
  (let [{:keys [read-ch write-ch]} (-> {:read-ch (or read-ch (chan))
                                        :write-ch (or write-ch (chan))}
                                       (cf/wrap-format (dissoc opts :read-ch :write-ch)))]
    (core-async-ch
      ws-conn
      (-> opts
          (dissoc :read-ch :write-ch)
          (assoc :read-chan read-ch
                 :write-chan write-ch
                 :format :str)))))
