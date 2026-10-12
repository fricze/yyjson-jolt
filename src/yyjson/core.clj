(ns yyjson.core
  "JSON for jolt over yyjson (C), with a clojure.data.json-shaped API.

    (write-str v)                      (read-str s)
    (write-str v :pretty true)         (read-str s :key-fn keyword)
    (write-str v :key-fn f :value-fn g)

  Writes maps, sequentials and sets, strings, keywords and symbols (by name, like data.json),
  integers (BigInt exactly), doubles, BigDecimal (exactly), ratios (as doubles), booleans, nil,
  uuids and chars (as strings). Anything else throws, unless :value-fn turns it into one of those.
  Unicode is written as raw UTF-8. Strings and keys may contain NUL (written as \\u0000).

  Errors are ex-info with :yyjson/error :invalid (bad input, with :position, a byte offset),
  :write (NaN or Infinity, for instance) or :unsupported (a value with no JSON form)."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [yyjson.native :as native]))

(native/load!)

;; Mirrors yj_err in native/shim.c.
(def ^:private Err (ffi/layout [:struct [[:code :uint32] [:pos :size_t] [:msg :pointer]]]))

(ffi/defcfn ^:private yj-doc "yj_doc" [] :pointer)
(ffi/defcfn ^:private yj-doc-free "yj_doc_free" [:pointer] :void)
(ffi/defcfn ^:private yj-obj "yj_obj" [:pointer] :pointer)
(ffi/defcfn ^:private yj-arr "yj_arr" [:pointer] :pointer)
(ffi/defcfn ^:private yj-put-str "yj_put_str" [:pointer :pointer :string :string] :bool)
(ffi/defcfn ^:private yj-put-int "yj_put_int" [:pointer :pointer :string :int64] :bool)
(ffi/defcfn ^:private yj-put-real "yj_put_real" [:pointer :pointer :string :double] :bool)
(ffi/defcfn ^:private yj-put-bool "yj_put_bool" [:pointer :pointer :string :bool] :bool)
(ffi/defcfn ^:private yj-put-null "yj_put_null" [:pointer :pointer :string] :bool)
(ffi/defcfn ^:private yj-put-val "yj_put_val" [:pointer :pointer :string :pointer] :bool)
(ffi/defcfn ^:private yj-push-str "yj_push_str" [:pointer :pointer :string] :bool)
(ffi/defcfn ^:private yj-push-int "yj_push_int" [:pointer :pointer :int64] :bool)
(ffi/defcfn ^:private yj-push-real "yj_push_real" [:pointer :pointer :double] :bool)
(ffi/defcfn ^:private yj-push-bool "yj_push_bool" [:pointer :pointer :bool] :bool)
(ffi/defcfn ^:private yj-push-null "yj_push_null" [:pointer :pointer] :bool)
(ffi/defcfn ^:private yj-push-val "yj_push_val" [:pointer :pointer] :bool)
(ffi/defcfn ^:private yj-new-null "yj_new_null" [:pointer] :pointer)
(ffi/defcfn ^:private yj-new-bool "yj_new_bool" [:pointer :bool] :pointer)
(ffi/defcfn ^:private yj-new-int "yj_new_int" [:pointer :int64] :pointer)
(ffi/defcfn ^:private yj-new-real "yj_new_real" [:pointer :double] :pointer)
(ffi/defcfn ^:private yj-new-str "yj_new_str" [:pointer :string] :pointer)
(ffi/defcfn ^:private yj-strn "yj_strn" [:pointer :pointer :size_t] :pointer)
(ffi/defcfn ^:private yj-put-valn "yj_put_valn" [:pointer :pointer :pointer :size_t :pointer] :bool)
(ffi/defcfn ^:private yj-raw "yj_raw" [:pointer :string] :pointer)
(ffi/defcfn ^:private yj-finish "yj_finish" [:pointer :pointer :uint32 :pointer] :pointer)
(ffi/defcfn ^:private yj-free "yj_free" [:pointer] :void)

(ffi/defcfn ^:private yj-parse "yj_parse" [:string :pointer] :pointer)
(ffi/defcfn ^:private yj-parse-free "yj_parse_free" [:pointer] :void)
(ffi/defcfn ^:private yj-root "yj_root" [:pointer] :pointer)
(ffi/defcfn ^:private yj-kind "yj_kind" [:pointer] :int)
(ffi/defcfn ^:private yj-len "yj_len" [:pointer] :size_t)
(ffi/defcfn ^:private yj-str "yj_str" [:pointer] :pointer)
(ffi/defcfn ^:private yj-sint "yj_sint" [:pointer] :int64)
(ffi/defcfn ^:private yj-uint "yj_uint" [:pointer] :uint64)
(ffi/defcfn ^:private yj-real "yj_real" [:pointer] :double)
(ffi/defcfn ^:private yj-first "yj_first" [:pointer] :pointer)
(ffi/defcfn ^:private yj-next "yj_next" [:pointer] :pointer)
(ffi/defcfn ^:private yj-value-of "yj_value_of" [:pointer] :pointer)

(defn- err-message [e] (let [p (ffi/read-field e Err :msg)] (if (ffi/null? p) "unknown error" (ffi/ptr->string p))))

;; ---------------------------------------------------------------- writing

;; A :string argument crosses as a NUL-terminated C string. Strings holding NUL take the slower
;; length-aware path instead.
(defn- nul? [^String s] (str/includes? s "\u0000"))

(defn- with-bytes
  "Call (f ptr byte-count) with s's UTF-8 bytes in temporary native memory."
  [^String s f]
  (let [b (.getBytes s "UTF-8") n (alength b)]
    (ffi/with-alloc [p (max 1 n)]
      (when (pos? n) (ffi/write-array p b))
      (f p n))))

(defn- strn [d s] (with-bytes s #(yj-strn d %1 %2)))

;; jolt's int? is also true for BigInt; only a Long fits the :int64 fast path.
(defn- long? [v] (instance? Long v))

(defn- default-key [k] (if (or (keyword? k) (symbol? k)) (name k) (str k)))

(declare new-val)

(defn- unsupported [v]
  (throw (ex-info (str "yyjson: no JSON form for " (type v)) {:yyjson/error :unsupported :value v})))

(defn- obj [ctx d m]
  (let [o (yj-obj d) kf (:key-fn ctx) vf (:value-fn ctx)]
    (reduce-kv
      (fn [_ k v]
        (let [ks (kf k) v (if vf (vf k v) v)]
          (when-not (string? ks) (unsupported ks))
          (if (nul? ks)
            (with-bytes ks #(yj-put-valn d o %1 %2 (new-val ctx d v)))
            (cond (nil? v) (yj-put-null d o ks)
                  (string? v) (if (nul? v) (yj-put-val d o ks (strn d v)) (yj-put-str d o ks v))
                  (boolean? v) (yj-put-bool d o ks v)
                  (long? v) (yj-put-int d o ks v)
                  (float? v) (yj-put-real d o ks v)
                  :else (yj-put-val d o ks (new-val ctx d v))))))
      nil m)
    o))

(defn- arr [ctx d xs]
  (let [a (yj-arr d)]
    (run! (fn [v]
            (cond (nil? v) (yj-push-null d a)
                  (string? v) (if (nul? v) (yj-push-val a (strn d v)) (yj-push-str d a v))
                  (boolean? v) (yj-push-bool d a v)
                  (long? v) (yj-push-int d a v)
                  (float? v) (yj-push-real d a v)
                  :else (yj-push-val a (new-val ctx d v))))
          xs)
    a))

(defn- new-val [ctx d v]
  (cond (nil? v) (yj-new-null d)
        (string? v) (if (nul? v) (strn d v) (yj-new-str d v))
        (boolean? v) (yj-new-bool d v)
        (long? v) (yj-new-int d v)
        (float? v) (yj-new-real d v)
        (map? v) (obj ctx d v)
        (or (sequential? v) (set? v)) (arr ctx d v)
        (or (keyword? v) (symbol? v)) (new-val ctx d (name v))
        (or (uuid? v) (char? v)) (new-val ctx d (str v))
        (decimal? v) (yj-raw d (str v))   ; exact, e.g. 1.50 or 1E+3
        (integer? v) (yj-raw d (str v))   ; BigInt beyond 64 bits
        (ratio? v) (yj-new-real d (double v))
        :else (unsupported v)))

(defn write-str
  "v as a JSON string.
  :pretty    true for two-space indentation
  :key-fn    map key -> string (default: name of a keyword/symbol, str of anything else)
  :value-fn  (fn [key value]) applied to every map value before it is written"
  [v & {:keys [pretty key-fn value-fn]}]
  (let [ctx {:key-fn (or key-fn default-key) :value-fn value-fn}
        d (yj-doc)
        root (try (new-val ctx d v) (catch Throwable e (yj-doc-free d) (throw e)))]
    (ffi/with-layout [e Err]
      (let [p (yj-finish d root (if pretty 1 0) e)] ; frees the document either way
        (when (ffi/null? p)
          (throw (ex-info (str "yyjson: write failed: " (err-message e))
                          {:yyjson/error :write :code (ffi/read-field e Err :code)})))
        (try (ffi/ptr->string p) (finally (yj-free p)))))))

;; ---------------------------------------------------------------- reading

(defn- string-at [p] (ffi/read-bytes (yj-str p) (yj-len p)))

(defn- value [kf p]
  (case (yj-kind p)
    0 nil
    1 false
    2 true
    3 (yj-sint p)
    4 (yj-uint p)
    5 (yj-real p)
    6 (string-at p)
    7 (let [n (yj-len p)]
        (loop [i 0, c (when (pos? n) (yj-first p)), out (transient [])]
          (if (< i n)
            (recur (inc i) (when (< (inc i) n) (yj-next c)) (conj! out (value kf c)))
            (persistent! out))))
    8 (let [n (yj-len p)]
        (loop [i 0, k (when (pos? n) (yj-first p)), out (transient {})]
          (if (< i n)
            (let [v (yj-value-of k)]
              (recur (inc i) (when (< (inc i) n) (yj-next v)) (assoc! out (kf (string-at k)) (value kf v))))
            (persistent! out))))))

(defn read-str
  "Parse one JSON value from s. Objects become maps with string keys, or (key-fn k).
  Numbers become longs, doubles, or (beyond 64 bits) whatever :uint64 gives."
  [^String s & {:keys [key-fn]}]
  (when (nul? s) ; a raw NUL is never valid JSON, and a :string argument would end there
    (throw (ex-info "yyjson: invalid JSON: raw NUL character" {:yyjson/error :invalid
                                                              :position (count (.getBytes (subs s 0 (str/index-of s "\u0000")) "UTF-8"))})))
  (ffi/with-layout [e Err]
    (let [doc (yj-parse s e)]
      (when (ffi/null? doc)
        (throw (ex-info (str "yyjson: invalid JSON: " (err-message e) " at byte " (ffi/read-field e Err :pos))
                        {:yyjson/error :invalid :code (ffi/read-field e Err :code) :position (ffi/read-field e Err :pos)})))
      (try (value (or key-fn identity) (yj-root doc)) (finally (yj-parse-free doc))))))
