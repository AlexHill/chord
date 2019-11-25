(ns chord.client
  (:require [cljs.core.async :as a :refer [chan <! >! put! close!]]
            [chord.channels :refer [core-async-ch]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))

(defn close-event->maybe-error [ev]
  (when-not (.-wasClean ev)
    {:reason (.-reason ev)
     :code (.-code ev)}))

(defn- create-ws [url opts]
  (cond
    ;; Detect if the "ws" node library is available
    ;; Note that just checking that cljs.core/*target* == nodejs works for nodejs
    ;; but not for node-webkit (at least)
    ;; This should work in all cases
    (and (exists? js/require)
         (try (js/require "ws")
              (catch :default e
                false)))
    (let [ws (js/require "ws")]
      (if opts
        (new ws url (clj->js opts))
        (new ws url)))

    :else (js/WebSocket. url)))

(defn ws-ch
  "Creates websockets connection and returns a 2-sided channel when the websocket is opened.
   Arguments:
    ws-url      - (required) link to websocket service
    opts        - (optional) map to configure reading/writing channels
      :read-ch  - (optional) (possibly buffered) channel to use for reading the websocket
      :write-ch - (optional) (possibly buffered) channel to use for writing to the websocket
      :format   - (optional) data format to use on the channel, (at the moment)
                             either :edn (default), :json, :json-kw or :str.
      :ws-opts  - (optional) Other options to be passed to the websocket constructor (NodeJS only)
                                  see https://github.com/websockets/ws/blob/master/doc/ws.md#new-websocketaddress-protocols-options

   Usage:
    (:require [cljs.core.async :as a])


    (a/<! (ws-ch \"ws://127.0.0.1:6437\"))

    (a/<! (ws-ch \"ws://127.0.0.1:6437\" {:read-chan (a/chan (a/sliding-buffer 10))}))

    (a/<! (ws-ch \"ws://127.0.0.1:6437\" {:read-chan (a/chan (a/sliding-buffer 10))
                                          :write-chan (a/chan (a/dropping-buffer 10))}))"

  [ws-url & [{:keys [ws-opts] :as opts}]]

  (let [web-socket (create-ws ws-url ws-opts)
        open-ch (a/chan)
        close-ch (a/chan)]

    (set! (.-binaryType web-socket) "arraybuffer")
    (.-addEventListener web-socket "open" #(put! open-ch %))
    (.-addEventListener web-socket "close" #(put! close-ch %))

    (let [ws-chan (core-async-ch web-socket (dissoc opts :ws-opts))
          read-ch (:read-chan (meta ws-chan))
          initial-ch (a/chan)]

      (go-loop [opened? false]
        (alt!
          open-ch ([_]
                   (a/>! initial-ch {:ws-channel ws-chan})
                   (a/close! initial-ch)
                   (recur true))

          close-ch ([ev]
                    (when-some [error (close-event->maybe-error ev)]
                      (a/>! (if opened? read-ch initial-ch)
                            {:error error}))
                    (a/close! initial-ch))))

      initial-ch)))
