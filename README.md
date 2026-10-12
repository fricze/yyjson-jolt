# yyjson-jolt

JSON for [jolt](https://jolt-lang.net) over [yyjson](https://github.com/ibireme/yyjson) (C), with a
`clojure.data.json`-shaped API.

```clojure
(require '[yyjson.core :as json])

(json/write-str {:caption "New menu 🍂" :tags ["autumn"]})   ;=> "{\"caption\":\"New menu 🍂\",\"tags\":[\"autumn\"]}"
(json/write-str {:a [1 2]} :pretty true)
(json/read-str "{\"a\":{\"b\":1}}" :key-fn keyword)          ;=> {:a {:b 1}}
```

## Why

- **Correct Unicode on jolt.** jolt strings hold code points; `clojure.data.json` assumes UTF-16. On jolt it
  writes emoji as a 5-digit `\u` escape (`ὠ0`) that every client decodes as `ὠ0`, and throws reading escaped
  surrogate pairs (`😀`). yyjson-jolt writes raw UTF-8 and reads both forms.
- **Speed.** About 3x faster than data.json on a 21KB API payload (0.63ms vs 2.08ms, jolt 0.8.18, Apple M-series).
  What's left is mostly copying strings across the FFI boundary (~165ns per 70-char string).

## Install

```clojure
;; deps.edn
{:deps {yyjson-jolt/yyjson-jolt {:local/root "path/to/yyjson-jolt"}}}
```

The native library (`native/libyyjson-jolt.{dylib,so}`) is compiled with `cc` the first time `yyjson.core`
loads, and again when a C source is newer than it. Builds go to a temp file and are renamed into place, so
processes starting at once don't collide. Needs a C compiler once. To use a prebuilt library instead, set
`YYJSON_JOLT_LIB=/path/to/libyyjson-jolt.so`.

Requires jolt 0.8.18 or newer. Tested on macOS (arm64).

## What it writes and reads

| Clojure | JSON |
|---|---|
| map | object (keys: name of a keyword/symbol, `str` of anything else, or `:key-fn`) |
| vector, list, seq, set | array |
| string, char, uuid | string (raw UTF-8; `"`, `\`, control chars and NUL escaped) |
| keyword, symbol | string, by name (namespace dropped, like data.json) |
| long, double | number |
| BigInt, BigDecimal | number, written exactly |
| ratio | number, as a double |
| `true` / `false` / `nil` | `true` / `false` / `null` |
| anything else | `ex-info {:yyjson/error :unsupported}`, unless `:value-fn` converts it |

Reading gives maps with string keys (or `:key-fn`), vectors, strings, longs (or larger integers beyond 64 bits),
doubles, booleans and nil. Duplicate keys: the last one wins.

Strings and keys may contain NUL: they take a slower, length-aware path and are written as `\u0000`.
A raw NUL in input is invalid JSON.

## Errors

All are `ex-info`:

| `:yyjson/error` | when | also |
|---|---|---|
| `:invalid` | input is not JSON (including lone surrogates, trailing data, raw NUL) | `:position` (0-based byte offset), `:code` |
| `:write` | NaN or Infinity, allocation failure | `:code` |
| `:unsupported` | a value with no JSON form | `:value` |
| `:build` | the native library could not be compiled | `:exit`, `:dir` |

## Not yet

- `jolt build` binaries: the library is found next to its sources at run time. Static linking (`:jolt/native`
  with `:static`) would remove that.
- Streaming (`read`/`write` on readers and writers). Strings only.
- Prebuilt binaries for machines without a C compiler.

## Tests

```sh
jolt -M:test
```

Emoji (ZWJ sequences, flags, skin tones, variation selectors), escaped surrogate pairs, every control character,
NUL in values and keys, number edge cases, options, error positions, freeing on failure, and 500 seeded random
strings over the full code point range, cross-checked against data.json where data.json is correct.

## License

MIT. Bundles yyjson 0.13.0 (MIT, `native/yyjson/LICENSE`).
