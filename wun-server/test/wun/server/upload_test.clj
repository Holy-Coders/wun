(ns wun.server.upload-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [wun.server.state :as state]
            [wun.server.upload :as up]
            [wun.uploads :as uploads])
  (:import  [java.io ByteArrayInputStream File]))

(use-fixtures :each
  (fn [t]
    ;; Snapshot/restore conn-states + config so cases stay independent.
    (let [conns @state/connections
          conn-states @state/conn-states
          cfg-snap @up/config]
      (try
        (reset! state/connections {})
        (reset! state/conn-states {})
        (let [tmp (doto (File/createTempFile "wun-upload-test" "")
                    (.delete))]
          (.mkdirs tmp)
          (up/configure! {:upload-dir              (.getAbsolutePath tmp)
                          :progress-interval-bytes 64
                          :max-size-bytes          (* 4 1024 1024)})
          (try (t)
               (finally
                 (doseq [^File f (file-seq tmp)]
                   (try (.delete f) (catch Throwable _ nil))))))
        (finally
          (reset! state/connections conns)
          (reset! state/conn-states conn-states)
          (reset! up/config cfg-snap))))))

(defn- request [headers body-bytes]
  {:headers headers
   :body    (ByteArrayInputStream. body-bytes)})

(defn- bytes-of [n]
  (let [arr (byte-array n)]
    (dotimes [i n] (aset arr i (unchecked-byte (mod i 251))))
    arr))

(deftest happy-path-end-to-end
  (let [progress (atom [])
        bcast    (fn [cid] (swap! progress conj cid))
        body     (bytes-of 200)
        req      (request {"x-wun-conn-id"   "cid-1"
                           "x-wun-upload-id" "u-1"
                           "x-wun-filename"  "data.bin"
                           "x-wun-size"      "200"
                           "x-wun-csrf"      "skip-csrf"}
                          body)]
    (with-redefs [wun.server.csrf/required? (constantly false)]
      (let [resp (up/handle-upload! req bcast)]
        (is (= 200 (:status resp)))
        (is (re-find #"\"status\":\"ok\"" (:body resp)))
        (is (uploads/complete? (state/state-for "cid-1") "u-1"))
        (is (= 200 (uploads/received (state/state-for "cid-1") "u-1")))
        ;; A few broadcasts: at least start + complete (progress depends
        ;; on whether the chunk size crosses the interval).
        (is (pos? (count @progress)))))))

(deftest rejects-without-conn-id-or-upload-id
  (let [resp (up/handle-upload!
              (request {"x-wun-csrf" "x"} (bytes-of 10))
              nil)]
    (is (= 400 (:status resp)))))

(deftest rejects-on-csrf-failure
  (let [req (request {"x-wun-conn-id"   "cid-1"
                      "x-wun-upload-id" "u-1"
                      "x-wun-csrf"      "wrong-token"}
                     (bytes-of 10))
        resp (up/handle-upload! req nil)]
    (is (= 403 (:status resp)))))

(deftest enforces-max-size
  (up/configure! {:max-size-bytes 100})
  (let [req (request {"x-wun-conn-id"   "cid-2"
                      "x-wun-upload-id" "u-2"
                      "x-wun-filename"  "big.bin"
                      "x-wun-csrf"      "skip"}
                     (bytes-of 500))]
    (with-redefs [wun.server.csrf/required? (constantly false)]
      (let [resp (up/handle-upload! req nil)]
        (is (= 413 (:status resp)))
        (is (uploads/errored? (state/state-for "cid-2") "u-2"))))))

(deftest progress-fires-while-streaming
  (up/configure! {:progress-interval-bytes 50})
  (let [bcasts (atom 0)
        req    (request {"x-wun-conn-id"   "cid-3"
                         "x-wun-upload-id" "u-3"
                         "x-wun-filename"  "file.txt"
                         "x-wun-size"      "300"
                         "x-wun-csrf"      "skip"}
                        (bytes-of 300))]
    (with-redefs [wun.server.csrf/required? (constantly false)]
      (up/handle-upload! req (fn [_] (swap! bcasts inc))))
    ;; start + complete + multiple progress hits
    (is (>= @bcasts 3))))

(deftest commit-fn-overrides-public-url
  (let [req (request {"x-wun-conn-id"   "cid-4"
                      "x-wun-upload-id" "u-4"
                      "x-wun-filename"  "hi.bin"
                      "x-wun-csrf"      "skip"}
                     (bytes-of 50))]
    (up/configure! {:commit-fn (fn [_entry _file] "https://cdn.example.com/u-4.bin")})
    (with-redefs [wun.server.csrf/required? (constantly false)]
      (up/handle-upload! req nil))
    (is (= "https://cdn.example.com/u-4.bin"
           (get-in (state/state-for "cid-4") [:uploads "u-4" :url])))))
