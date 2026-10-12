/* yyjson-jolt: a C ABI over yyjson for jolt.ffi.
 *
 * yyjson's document-building and reading API is mostly `static inline`, which a foreign-function
 * binding can't call, so every function here is one exported call. Writing is one call per scalar;
 * reading walks the immutable tree child by child. Errors go into a caller-allocated yj_err, never
 * thread-local state: a jolt fiber can move between OS threads between two calls. */
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "yyjson/yyjson.h"

typedef yyjson_mut_doc mdoc;
typedef yyjson_mut_val mval;

typedef struct { uint32_t code; size_t pos; const char *msg; } yj_err;

/* ---- writing ---------------------------------------------------------------------------- */

mdoc *yj_doc(void) { return yyjson_mut_doc_new(NULL); }
void yj_doc_free(mdoc *d) { yyjson_mut_doc_free(d); }
mval *yj_obj(mdoc *d) { return yyjson_mut_obj(d); }
mval *yj_arr(mdoc *d) { return yyjson_mut_arr(d); }

/* Fast path: NUL-terminated key and string (jolt :string arguments). */
#define KEY(d, k) yyjson_mut_strcpy(d, k)
bool yj_put_str(mdoc *d, mval *o, const char *k, const char *s) { return yyjson_mut_obj_add(o, KEY(d, k), yyjson_mut_strcpy(d, s)); }
bool yj_put_int(mdoc *d, mval *o, const char *k, int64_t v) { return yyjson_mut_obj_add(o, KEY(d, k), yyjson_mut_sint(d, v)); }
bool yj_put_real(mdoc *d, mval *o, const char *k, double v) { return yyjson_mut_obj_add(o, KEY(d, k), yyjson_mut_real(d, v)); }
bool yj_put_bool(mdoc *d, mval *o, const char *k, bool v) { return yyjson_mut_obj_add(o, KEY(d, k), yyjson_mut_bool(d, v)); }
bool yj_put_null(mdoc *d, mval *o, const char *k) { return yyjson_mut_obj_add(o, KEY(d, k), yyjson_mut_null(d)); }
bool yj_put_val(mdoc *d, mval *o, const char *k, mval *v) { return yyjson_mut_obj_add(o, KEY(d, k), v); }

bool yj_push_str(mdoc *d, mval *a, const char *s) { return yyjson_mut_arr_append(a, yyjson_mut_strcpy(d, s)); }
bool yj_push_int(mdoc *d, mval *a, int64_t v) { return yyjson_mut_arr_append(a, yyjson_mut_sint(d, v)); }
bool yj_push_real(mdoc *d, mval *a, double v) { return yyjson_mut_arr_append(a, yyjson_mut_real(d, v)); }
bool yj_push_bool(mdoc *d, mval *a, bool v) { return yyjson_mut_arr_append(a, yyjson_mut_bool(d, v)); }
bool yj_push_null(mdoc *d, mval *a) { return yyjson_mut_arr_append(a, yyjson_mut_null(d)); }
bool yj_push_val(mval *a, mval *v) { return yyjson_mut_arr_append(a, v); }

/* Standalone values, for the general path (top-level scalars, NUL strings, values under NUL keys). */
mval *yj_new_null(mdoc *d) { return yyjson_mut_null(d); }
mval *yj_new_bool(mdoc *d, bool v) { return yyjson_mut_bool(d, v); }
mval *yj_new_int(mdoc *d, int64_t v) { return yyjson_mut_sint(d, v); }
mval *yj_new_real(mdoc *d, double v) { return yyjson_mut_real(d, v); }
mval *yj_new_str(mdoc *d, const char *s) { return yyjson_mut_strcpy(d, s); }

/* Length-aware path, for strings and keys that contain NUL. */
mval *yj_strn(mdoc *d, const char *p, size_t n) { return yyjson_mut_strncpy(d, p, n); }
bool yj_put_valn(mdoc *d, mval *o, const char *k, size_t klen, mval *v) { return yyjson_mut_obj_add(o, yyjson_mut_strncpy(d, k, klen), v); }

/* A number written verbatim (BigDecimal, BigInt). The caller passes a valid JSON number. */
mval *yj_raw(mdoc *d, const char *s) { return yyjson_mut_rawcpy(d, s); }

/* Serialize with root and free the document. NULL on failure, with err filled in.
   The caller frees the returned string with yj_free. flags: 0 compact, 1 pretty (2 spaces). */
char *yj_finish(mdoc *d, mval *root, uint32_t flags, yj_err *err) {
  yyjson_mut_doc_set_root(d, root);
  yyjson_write_flag f = flags & 1 ? (YYJSON_WRITE_PRETTY | YYJSON_WRITE_PRETTY_TWO_SPACES) : YYJSON_WRITE_NOFLAG;
  yyjson_write_err we;
  char *s = yyjson_mut_write_opts(d, f, NULL, NULL, &we);
  if (!s) { err->code = we.code; err->pos = 0; err->msg = we.msg; }
  yyjson_mut_doc_free(d);
  return s;
}
void yj_free(char *s) { free(s); }

/* ---- reading ---------------------------------------------------------------------------- */

/* s is NUL-terminated: a raw NUL is never valid JSON (it must be escaped), so the caller refuses
   input containing one before calling. NULL on failure, with err filled in. */
yyjson_doc *yj_parse(const char *s, yj_err *err) {
  yyjson_read_err re;
  yyjson_doc *doc = yyjson_read_opts((char *)s, strlen(s), YYJSON_READ_NOFLAG, NULL, &re);
  if (!doc) { err->code = re.code; err->pos = re.pos; err->msg = re.msg; }
  return doc;
}
void yj_parse_free(yyjson_doc *d) { yyjson_doc_free(d); }
yyjson_val *yj_root(yyjson_doc *d) { return yyjson_doc_get_root(d); }

/* 0 null, 1 false, 2 true, 3 sint, 4 uint, 5 real, 6 str, 7 arr, 8 obj */
int yj_kind(yyjson_val *v) {
  switch (yyjson_get_type(v)) {
    case YYJSON_TYPE_NULL: return 0;
    case YYJSON_TYPE_BOOL: return yyjson_get_bool(v) ? 2 : 1;
    case YYJSON_TYPE_NUM:
      switch (yyjson_get_subtype(v)) {
        case YYJSON_SUBTYPE_SINT: return 3;
        case YYJSON_SUBTYPE_UINT: return 4;
        default: return 5;
      }
    case YYJSON_TYPE_STR: return 6;
    case YYJSON_TYPE_ARR: return 7;
    case YYJSON_TYPE_OBJ: return 8;
    default: return -1;
  }
}
size_t yj_len(yyjson_val *v) { return yyjson_get_len(v); }       /* string bytes, or arr/obj children */
const char *yj_str(yyjson_val *v) { return yyjson_get_str(v); }  /* may hold NUL: read yj_len bytes */
int64_t yj_sint(yyjson_val *v) { return yyjson_get_sint(v); }
uint64_t yj_uint(yyjson_val *v) { return yyjson_get_uint(v); }
double yj_real(yyjson_val *v) { return yyjson_get_real(v); }
/* Children are laid out flat: an object's first child is a key, and the key's value follows it. */
yyjson_val *yj_first(yyjson_val *ctn) { return unsafe_yyjson_get_first(ctn); }
yyjson_val *yj_next(yyjson_val *v) { return unsafe_yyjson_get_next(v); }
yyjson_val *yj_value_of(yyjson_val *key) { return key + 1; }
