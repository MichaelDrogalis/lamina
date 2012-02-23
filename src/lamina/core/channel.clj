;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.channel
  (:use
    [lamina.core.utils])
  (:require
    [lamina.core.node :as n]
    [lamina.core.queue :as q]
    [lamina.core.lock :as l]
    [lamina.core.result :as r]
    [clojure.string :as str])
  (:import
    [lamina.core.lock
     AsymmetricLock]
    [lamina.core.node
     Node]
    [java.io
     Writer]))

(set! *warn-on-reflection* true)

;;;

(defprotocol IChannel
  (receiver-node [_]
    "Returns the receiver node for the channel.")
  (emitter-node [_]
    "Returns the emitter node for the channel.")
  (split-receiver [_]
    "Ensures the receiver and emitter are split, and returns the emitter."))

(deftype Channel
  [^Node receiver
   ^{:volatile-mutable true :tag Node} emitter]
  IEnqueue
  (enqueue [_ msg]
    (n/propagate receiver msg true))
  IChannel
  (receiver-node [_]
    receiver)
  (emitter-node [this]
    emitter)
  (split-receiver [this]
    (if-let [n (n/split receiver)]
      (do
        (set! emitter n)
        n)
      emitter))
  Object
  (toString [_]
    (if-not (= ::none (n/error-value receiver ::none))
      (str "<== | ERROR: " (n/error-value receiver nil) " |")
      (if-let [q (n/queue emitter)]
        (str "<== "
          (let [msgs (q/messages q)
                msgs-str (-> msgs vec str)]
            (str
              (first msgs-str)
              (subs msgs-str 1 (dec (count msgs-str)))
              (when-not (n/closed? receiver)
                (str
                  (when-not (empty? msgs) " ")
                 "\u2026"))
              (last msgs-str))))
        (str "<== "
          (if-not (n/closed? receiver)
            "[ \u2026 ]"
            "[ ]"))))))

(defrecord SplicedChannel [^Channel receiver ^Channel emitter]
  IEnqueue
  (enqueue [_ msg]
    (n/propagate (receiver-node receiver) msg true))
  IChannel
  (receiver-node [_]
    (receiver-node receiver))
  (emitter-node [_]
    (emitter-node emitter))
  (split-receiver [_]
    (split-receiver receiver))
  Object
  (toString [_]
    (str emitter)))

(defmacro channel* [& options]
  `(let [n# (n/node* ~@options)]
     (Channel. n# n#)))

(defn channel
  "Returns a channel containing the given messages."
  [& messages]
  (channel* :messages (seq messages)))

(defn mimic [channel]
  (let [n (n/mimic (receiver-node channel))]
    (Channel. n n)))

(defn closed-channel
  "Returns a closed channel containing the given messages."
  [& messages]
  (let [ch (channel* :messages (seq messages))]
    (n/close (receiver-node ch))
    ch))

(defn grounded-channel
  "Returns a channel that cannot accumulate messages."
  []
  (channel* :grounded? true))

(defn ground
  "Ensures that messages will not accumulate in the channel's queue."
  [ch]
  (n/ground (emitter-node ch)))

(defn splice
  "Returns a channel where all messages are enqueud into 'receiver', and
   consumed from 'emitter'."
  [emitter receiver]
  (SplicedChannel.
    (if (instance? SplicedChannel receiver)
      (.receiver ^SplicedChannel receiver)
      receiver)
    (if (instance? SplicedChannel emitter)
      (.emitter ^SplicedChannel emitter)
      emitter)))

(defn channel? [x]
  (or
    (instance? Channel x)
    (instance? SplicedChannel x)))

;;;

(defn receive
  "Consumes a single message from the channel, which will be passed to 'callback.'  Only
   one callback can receive any given message; calling (receive ...) multiple times will
   always consume multiple messages.

   Callbacks registered with this method can be cancelled using (cancel-callback ...) if
   they have not already been triggered."
  [channel callback]
  (n/receive (emitter-node channel) callback callback))

(defn read-channel
  "Returns a result-channel representing the next message from the channel.  Only one
   result-channel can represent any given message; calling (read-channel ...) multiple times
   will always consume multiple messages.

   Enqueueing a value into the result-channel before it is realized will prevent the message from
   being consumed."
  ([channel]
     (n/read-node (emitter-node channel))))

(defmacro read-channel*
  "A version of (read-channel ...) with more dials.  Valid options include:

   :timeout -
   :on-timeout -
   :predicate -
   :on-false -
   :result -
   :on-drained -
   :on-error -"
  [ch & {:as options}]
  `(n/read-node* (emitter-node ~ch) ~@(apply concat options)))

(defn receive-all [channel callback]
  (n/link (emitter-node channel)
    callback
    (n/edge "receive-all" (n/callback-node callback))
    nil
    nil))

(defn sink [callback channel]
  (receive-all channel callback))

(defn cancel-callback
  ([channel callback]
     (n/cancel (emitter-node channel) callback)))

(defn fork [channel]
  (let [n (n/node identity)
        emitter (split-receiver channel)]
    (n/join
      (receiver-node channel)
      (n/edge "fork" n)
      #(when %
         (when-let [q (n/queue emitter)]
           (-> n n/queue (q/append (q/messages q)))))
      nil)
    (Channel. n n))) 

(defn close [channel]
  (n/close (receiver-node channel)))

(defn error [channel err]
  (n/error (receiver-node channel) err))

(defn closed? [channel]
  (n/closed? (receiver-node channel)))

(defn drained? [channel]
  (n/drained? (emitter-node channel)))

(defn transactional? [channel]
  (n/transactional? (receiver-node channel)))

(defn on-closed [channel callback]
  (n/on-closed (receiver-node channel) callback))

(defn on-drained [channel callback]
  (n/on-drained (emitter-node channel) callback))

(defn on-error [channel callback]
  (n/on-error (emitter-node channel) callback))

(defn closed-result [channel]
  (n/closed-result (receiver-node channel)))

(defn drained-result [channel]
  (n/drained-result (emitter-node channel)))

;;;

(defn siphon [src dst]
  (n/siphon (emitter-node src) (receiver-node dst)))

(defn join [src dst]
  (n/join (emitter-node src) (receiver-node dst)))

(defn bridge-join [src description callback & dsts]
  (n/bridge-join (emitter-node src) nil description callback
    (if (empty? dsts)
      [(n/terminal-node nil)]
      (map receiver-node dsts))))

(defn bridge-siphon [src description callback & dsts]
  (n/bridge-siphon (emitter-node src) nil description callback
    (if (empty? dsts)
      [(n/terminal-node nil)]
      (map receiver-node dsts))))

(defn map* [f channel]
  (let [n (n/downstream-node f (emitter-node channel))]
    (Channel. n n)))

(defn filter* [f channel]
  (let [n (n/downstream-node (predicate-operator f) (emitter-node channel))]
    (Channel. n n)))

(defn remove* [f channel]
  (filter* (complement f) channel))

;;;

(defmethod print-method Channel [o ^Writer w]
  (.write w (str o)))

(defmethod print-method SplicedChannel [o ^Writer w]
  (.write w (str o)))
