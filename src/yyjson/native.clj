(ns yyjson.native
  "Builds and loads native/libyyjson-jolt.{dylib,so}: vendored yyjson + native/shim.c.

  The library is compiled with `cc` the first time this namespace loads, and again whenever a
  C source is newer than the built file. It lands next to the sources, in the library's own
  directory (for a git dependency, jolt's gitlibs cache). Set YYJSON_JOLT_LIB to a prebuilt
  library to skip the build."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [jolt.ffi :as ffi]))

;; src/yyjson/native.clj -> the library root. Located through the loader rather than *file*,
;; which on a cold deps cache can be relative to something other than the working directory.
(def ^:private root
  (-> (io/resource "yyjson/native.clj") io/file fs/absolutize fs/normalize fs/parent fs/parent fs/parent str))

(defn- lib-path []
  (str root "/native/libyyjson-jolt."
       (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac") "dylib" "so")))

(def ^:private sources ["native/shim.c" "native/yyjson/yyjson.c" "native/yyjson/yyjson.h"])

(defn- stale? [lib]
  (or (not (fs/exists? lib))
      (seq (fs/modified-since lib (map #(str root "/" %) sources)))))

(defn- build!
  "Compile into a temp file and rename it into place, so two processes building at once can't
  load a half-written library."
  [lib]
  (let [tmp (str lib "." (random-uuid) ".tmp")
        linux? (str/ends-with? lib ".so")
        args (concat ["cc" "-O3" "-shared" "-fPIC" "-o" tmp "native/shim.c" "native/yyjson/yyjson.c"]
                     (when linux? ["-lm"]))
        {:keys [exit err]} (try (apply sh/sh (concat args [:dir root]))
                                (catch Throwable e {:exit -1 :err (str "could not run cc: " (or (ex-message e) (type e)))}))]
    (when-not (zero? exit)
      (fs/delete-if-exists tmp)
      (throw (ex-info (str "yyjson-jolt: building the native library failed (needs a C compiler as `cc`):\n" err)
                      {:yyjson/error :build :exit exit :dir root})))
    (fs/move tmp lib {:replace-existing true :atomic-move true})))

(defn load!
  "Load the native library, building it first when needed. Returns its path."
  []
  (let [lib (or (System/getenv "YYJSON_JOLT_LIB")
                (let [lib (lib-path)] (when (stale? lib) (build! lib)) lib))]
    (ffi/load-library lib)
    lib))
