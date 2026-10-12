(ns yyjson.core-test
  (:require [clojure.data.json :as data.json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is are testing]]
            [yyjson.core :as json]))

(defn- round-trip [v] (json/read-str (json/write-str v)))
(defn- error-of [thunk] (try (thunk) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---------------------------------------------------------------- unicode

(def emoji
  ["😀"                 ; astral plane, one code point
   "👍🏽"                ; skin tone modifier
   "👩‍👩‍👧"              ; ZWJ sequence
   "🇵🇱"                ; regional indicator pair (flag)
   "❤️"                 ; BMP heart + variation selector 16
   "🧋☕️🥐"
   "Autumn 🍂 is on the menu! Pumpkin cortado 🎃☕"])

(deftest emoji-written-as-raw-utf8
  (doseq [s emoji]
    (let [out (json/write-str {:caption s})]
      (is (= (str "{\"caption\":\"" s "\"}") out) s)
      (is (not (str/includes? out "\\u")) s)
      (is (= {"caption" s} (json/read-str out)) s))))

(deftest emoji-read-escaped
  (are [in expected] (= expected (json/read-str (str "[\"" in "\"]")))
    "\\ud83d\\ude00"                                            ["😀"]
    "\\uD83D\\uDE00"                                            ["😀"]
    "\\ud83d\\udc69\\u200d\\ud83d\\udc69\\u200d\\ud83d\\udc67"  ["👩‍👩‍👧"]
    "\\ud83c\\uddf5\\ud83c\\uddf1"                              ["🇵🇱"]
    "\\u00e9t\\u00e9 caf\\u00e9"                                ["été café"]
    "raw 😀 and escaped \\ud83d\\ude00"                         ["raw 😀 and escaped 😀"]))

(deftest lone-surrogates-are-invalid
  (is (= :invalid (:yyjson/error (error-of #(json/read-str "[\"\\ud83d\"]")))))
  (is (= :invalid (:yyjson/error (error-of #(json/read-str "[\"\\ude00x\"]"))))))

(deftest non-ascii-text
  (doseq [s ["zażółć gęślą jaźń" "日本語のキャプション" "مرحبا بالعالم" "line\u2028sep\u2029para" "ﬁ ligature ẞ"]]
    (is (= {"t" s} (round-trip {:t s})) s)))

;; ---------------------------------------------------------------- escaping and NUL

(deftest escaping
  (is (= "[\"a\\\"b\"]" (json/write-str ["a\"b"])))
  (is (= "[\"a\\\\b\"]" (json/write-str ["a\\b"])))
  (is (= "[\"a\\nb\\tc\\rd\"]" (json/write-str ["a\nb\tc\rd"])))
  (doseq [c (concat (range 1 32) [127])]
    (let [s (str "x" (char c) "y")]
      (is (= [s] (round-trip [s])) (str "control char " c)))))

(deftest nul-in-strings-and-keys
  (testing "written as \\u0000, read back intact"
    (are [v expected] (= expected (round-trip v))
      ["before\u0000after"]                   ["before\u0000after"]
      {"k\u0000ey" 1}                         {"k\u0000ey" 1}
      {"k\u0000" "v\u0000"}                   {"k\u0000" "v\u0000"}
      {:nested [{:x "\u0000"} "\u0000\u0000"]} {"nested" [{"x" "\u0000"} "\u0000\u0000"]}
      "\u0000"                                "\u0000"
      ["😀\u0000😀"]                          ["😀\u0000😀"])
    (is (str/includes? (json/write-str ["a\u0000b"]) "\\u0000")))
  (testing "escaped NUL in input is decoded"
    (is (= ["ab\u0000cd"] (json/read-str "[\"ab\\u0000cd\"]")))
    (is (= {"k\u0000" 1} (json/read-str "{\"k\\u0000\":1}"))))
  (testing "a raw NUL in input is invalid JSON, with its byte position"
    (is (= {:yyjson/error :invalid :position 5} (error-of #(json/read-str "[\"zé\u0000\"]"))) "0-based, in bytes: é is 2")))

;; ---------------------------------------------------------------- values

(deftest numbers
  (are [v] (= [v] (round-trip [v]))
    0 -1 42 Long/MAX_VALUE Long/MIN_VALUE
    0.5 -0.25 0.11090543259557344 1.0E300 5.0E-324)
  (is (= [18446744073709551615] (json/read-str "[18446744073709551615]")) "uint64")
  (is (= [1.5E10] (json/read-str "[1.5e10]")))
  (is (= "[1.50,1E+3]" (json/write-str [1.50M 1E3M])) "BigDecimal written exactly")
  (is (= "[123456789012345678901234567890]" (json/write-str [123456789012345678901234567890N])) "BigInt exactly")
  (is (= [0.5] (round-trip [1/2])) "ratio as double")
  (is (= :write (:yyjson/error (error-of #(json/write-str [##NaN])))))
  (is (= :write (:yyjson/error (error-of #(json/write-str {:x ##Inf}))))))

(deftest shapes
  (are [v] (= v (round-trip v))
    {} [] [[]] [{}] {"a" {}} {"a" [1 [2 [3]]]} {"n" nil "b" false "t" true})
  (is (= {"k" "v" "s" "x"} (round-trip {:k :v "s" "x"})) "keywords by name")
  (is (= {"b" "c"} (round-trip {:a/b :x/c})) "namespaces dropped, like data.json")
  (is (= [1 2 3] (round-trip '(1 2 3))) "seqs as arrays")
  (is (= [1] (round-trip #{1})) "sets as arrays")
  (is (= {"1" "x"} (round-trip {1 "x"})) "non-string keys via str")
  (is (= ["11111111-1111-1111-1111-111111111111" "z"]
         (round-trip [#uuid "11111111-1111-1111-1111-111111111111" \z])) "uuid and char as strings"))

(deftest top-level-scalars
  (are [v out] (= out (json/write-str v))
    nil "null" true "true" 1 "1" "s" "\"s\"" :k "\"k\"")
  (are [in v] (= v (json/read-str in))
    "null" nil "true" true "1" 1 "\"s\"" "s" " [1] " [1]))

(deftest unsupported-values
  (is (= :unsupported (:yyjson/error (error-of #(json/write-str {:x (Object.)})))))
  (is (= :unsupported (:yyjson/error (error-of #(json/write-str [(java.time.LocalDate/of 2026 1 1)]))))))

;; ---------------------------------------------------------------- options

(deftest options
  (is (= {:a {:b 1}} (json/read-str "{\"a\":{\"b\":1}}" :key-fn keyword)))
  (is (= "{\"A\":1}" (json/write-str {:a 1} :key-fn #(str/upper-case (name %)))))
  (is (= "{\"d\":\"2026-01-01\",\"n\":1}"
         (json/write-str (sorted-map :d (java.time.LocalDate/of 2026 1 1) :n 1)
                         :value-fn (fn [_ v] (if (instance? java.time.LocalDate v) (str v) v)))))
  (is (= "{\n  \"a\": [\n    1,\n    2\n  ]\n}" (json/write-str {:a [1 2]} :pretty true))))

;; ---------------------------------------------------------------- errors

(deftest invalid-json
  (are [in] (= :invalid (:yyjson/error (error-of #(json/read-str in))))
    "" "{" "[1,]" "{\"a\" 1}" "{} x" "nul" "[\"unterminated]" "{'a':1}")
  (let [e (try (json/read-str "{\"a\": tru}") (catch Exception e e))]
    (is (str/starts-with? (ex-message e) "yyjson: invalid JSON: "))
    (is (= 6 (:position (ex-data e))) "byte offset of the error")))

(deftest write-errors-free-the-document
  ;; An unsupported value midway must not leak or corrupt later writes.
  (dotimes [_ 1000] (error-of #(json/write-str {:a 1 :b (Object.)})))
  (is (= "{\"ok\":true}" (json/write-str {:ok true}))))

;; ---------------------------------------------------------------- property-style

;; Seeded pseudo-random strings over the whole code point range minus surrogates (jolt strings
;; can't hold them), weighted toward control chars, NUL, ASCII and emoji.
(defn- lcg [seed] (iterate #(mod (+ (* % 6364136223846793005) 1442695040888963407) 9223372036854775807) seed))

(defn- code-point [r]
  (let [cp (case (mod r 6)
             0 (mod r 32)                         ; control, NUL included
             1 (+ 32 (mod r 95))                  ; printable ASCII
             2 (+ 0x1F300 (mod r 0x700))          ; emoji blocks
             3 (+ 0xA0 (mod r 0xD700))            ; BMP below surrogates
             4 (+ 0xE000 (mod r 0x101FFF))        ; BMP above surrogates + astral
             5 0)]                                ; extra NUL
    (if (> cp 0x10FFFF) 0x41 cp)))

(def ^:private random-strings
  (let [rs (vec (take 20000 (rest (lcg 7))))]
    (vec (for [i (range 500)]
           (apply str (map #(char (code-point (abs %))) (subvec rs (* i 40) (+ (* i 40) (inc (mod i 40))))))))))

(deftest random-round-trip
  (doseq [s random-strings]
    (is (= {s s} (round-trip {s s})) (pr-str (map int s))))
  (testing "data.json reads our output (BMP-only: data.json mangles astral chars on jolt)"
    (doseq [s random-strings :when (every? #(< (int %) 0x10000) s)]
      (is (= {"s" s} (data.json/read-str (json/write-str {:s s})))))))

(deftest same-as-data-json-on-api-shapes
  (let [v {:posts [{:id "55555555-0000-0000-0000-000000000001" :title nil :status "published"
                    :approval_required false :created_at "2026-10-03T19:48:00.571Z"
                    :targets [{:options {"title" "Autumn" "tags" ["a" "b"]} :position 0}]
                    :engagementRate 0.11090543259557344 :impressions 49700}]}]
    (is (= (data.json/read-str (data.json/write-str v)) (json/read-str (json/write-str v))))))
