(ns wun.uploads
  "Upload entry state. LiveView's `allow_upload`-style abstraction:
   each connection has an `:uploads` map under its state slice;
   entries track their own progress, status, and final URL.

   Entry shape, parked under `[:uploads <upload-id>]` in app state:

       {:upload-id  \"u-7f3a\"
        :form       :my-form          (optional binding to a form)
        :field      :avatar           (optional field name)
        :filename   \"photo.jpg\"
        :content-type \"image/jpeg\"
        :size       128440             (bytes; nil if streaming)
        :received   72048              (bytes received so far)
        :status     :uploading        (:queued :uploading :complete :errored)
        :url        nil               (set on :complete)
        :error      nil}              (set on :errored)

   The wire model: client POSTs multipart to `/upload`; the server
   writes the bytes to its configured upload backend (filesystem by
   default), pushing progress patches via `:uploads` mutation as
   chunks land. The UI binds against `:uploads <id> :received` to
   show a progress bar; on `:complete` it has `:url` to use in the
   submit handler.

   This namespace is pure cljc -- the actual HTTP / disk side of
   uploads lives in `wun.server.upload`."
  (:require [clojure.string :as str]))

(defn empty-upload
  "Initial upload entry. `id` is required; the rest are optional."
  ([id] (empty-upload id {}))
  ([id {:keys [form field filename content-type size]}]
   {:upload-id    id
    :form         form
    :field        field
    :filename     filename
    :content-type content-type
    :size         size
    :received     0
    :status       :queued
    :url          nil
    :error        nil}))

(defn upload [state id]
  (get-in state [:uploads id]))

(defn assoc-upload
  "Set the upload entry for `id`."
  [state id entry]
  (assoc-in state [:uploads id] entry))

(defn dissoc-upload
  "Drop the upload entry for `id`. Used after the user submits the
   parent form (or explicitly cancels)."
  [state id]
  (update state :uploads dissoc id))

(defn update-upload [state id f & args]
  (assoc-in state [:uploads id]
            (apply f (or (upload state id) (empty-upload id)) args)))

;; ---------------------------------------------------------------------------
;; Lifecycle transitions

(defn start
  "Move `id`'s entry to `:uploading`. Idempotent on already-uploading."
  [state id]
  (update-upload state id assoc :status :uploading))

(defn progress
  "Update `id`'s `:received` counter. Monotonic; never decreases."
  [state id received]
  (update-upload state id
                 (fn [u] (assoc u :received (max (:received u 0) received)))))

(defn complete
  "Mark `id` `:complete` with its final `:url`."
  [state id url]
  (update-upload state id
                 (fn [u] (assoc u :status :complete :url url
                                :received (or (:size u) (:received u))))))

(defn errored
  "Mark `id` `:errored` with `reason`."
  [state id reason]
  (update-upload state id
                 (fn [u] (assoc u :status :errored :error reason))))

;; ---------------------------------------------------------------------------
;; Read accessors

(defn received [state id]
  (get-in state [:uploads id :received] 0))

(defn status [state id]
  (get-in state [:uploads id :status]))

(defn complete? [state id]
  (= :complete (status state id)))

(defn errored? [state id]
  (= :errored (status state id)))

(defn percent
  "Progress percent 0..100 if size is known; nil otherwise. Uploads
   without a known size show as indeterminate in the UI."
  [state id]
  (let [u (upload state id)]
    (when (and u (:size u) (pos? (:size u)))
      (-> (:received u 0)
          (/ (:size u))
          (* 100.0)
          double
          (min 100.0)))))

;; ---------------------------------------------------------------------------
;; Helpers

(defn safe-filename
  "Take the basename of a possibly-path-shaped filename, strip
   anything other than letters / digits / `.`/`_`/`-`, collapse runs
   of `.` so `..` can't survive, and return a reasonable default when
   the result would be blank.

       \"../../etc/passwd/evil.png\" -> \"evil.png\"
       \"x.txt\"                     -> \"x.txt\"
       nil / \"\"                    -> \"upload.bin\""
  [name]
  (let [s        (or name "")
        last-seg (last (str/split s #"[\\/]+"))
        cleaned  (-> (or last-seg "")
                     (str/replace #"[^A-Za-z0-9._-]" "")
                     (str/replace #"\.{2,}" ".")
                     (str/replace #"^\.+" "")
                     str/trim)]
    (if (str/blank? cleaned) "upload.bin" cleaned)))
