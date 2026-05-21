;; ============================================================================
;; xsofy.hash JVM cross-validation
;; ============================================================================
;;
;; PR1 Task 18: independent Clojure JVM port of xsofy.hash to cross-check
;; that our let-go implementation is genuinely correct against an
;; independent re-implementation in a different language runtime, not just
;; happily-coincidentally-matching the C reference because of a shared bug.
;;
;; This file is intentionally self-contained: it copies the u64 helpers,
;; encode-salt, xxh3-64 short/medium paths, and the 192-byte default secret
;; verbatim from xsofy/hash.lg. It does NOT depend on xsofy.hash at all.
;;
;; Invocation: clojure -M xsofy/test/hash_test_jvm.clj
;;
;; Expected output: "All 25 published vectors match." Failure prints a diff
;; per row and exits non-zero.

(ns xsofy.hash-test-jvm)

;; ============================================================================
;; BigInt -> long coercion for u64 literals
;; ============================================================================
;;
;; Clojure JVM's reader parses hex literals greater than Long/MAX_VALUE as
;; clojure.lang.BigInt, e.g. (class 0xbe4ba423396cfeb8) => BigInt. The bit-*
;; ops only accept primitive longs, so we must coerce each high-bit-set u64
;; constant to its signed int64 representation. `ul` (unsigned-long) wraps
;; unchecked-long so the call site reads as a u64 literal.
;;
;; let-go does not need this — its reader treats hex literals as int64 and
;; lets the bit pattern carry through directly to signed-int64 land.

(defmacro ul
  "Coerce an integer literal to a primitive long with two's-complement wrap.
   Compile-time on literal args."
  [n]
  `(unchecked-long ~n))

;; ============================================================================
;; u64 helpers
;; ============================================================================
;;
;; Same family as let-go's u64-* helpers. Clojure JVM ships unchecked-* in
;; core, so no bin/lg wrapper is needed. unchecked-add and unchecked-multiply
;; on primitive longs return the low 64 bits of the mathematical result —
;; same wrap-mod-2^64 contract as let-go's unchecked-add and the upstream
;; xxh3 reference.

(defn u64+
  "Modular addition: (a + b) mod 2^64. Wraps on overflow."
  [a b]
  (unchecked-add a b))

(defn u64*
  "Modular multiplication: (a * b) mod 2^64. Wraps on overflow."
  [a b]
  (unchecked-multiply a b))

(def u64-xor bit-xor)

(defn u64-shr
  "Logical right shift by n bits (zero-fill, not sign-extend). n in [0, 64)."
  [x n]
  (unsigned-bit-shift-right x n))

(defn u64-shl
  "Modular left shift: returns the low 64 bits of (x << n). n in [0, 64)."
  [x n]
  (bit-shift-left x n))

(defn u64-rotl
  "Rotate-left by n bits over the low 64 bits of x. n normalized via mod 64;
   n=0 and n=64 explicitly identity (special-cased to avoid the silent-0
   behavior of a 64-bit shift)."
  [x n]
  (let [n (mod n 64)]
    (if (zero? n)
      x
      (bit-or (u64-shl x n)
              (u64-shr x (- 64 n))))))

(defn u64*-128
  "Full 128-bit unsigned product of two u64s, returned as [hi lo].
   Mirrors let-go's u64*-128, which mirrors Go's math/bits.Mul64."
  [a b]
  (let [mask32 0xFFFFFFFF
        a-lo (bit-and a mask32)
        a-hi (u64-shr a 32)
        b-lo (bit-and b mask32)
        b-hi (u64-shr b 32)
        w0 (unchecked-multiply a-lo b-lo)
        t (unchecked-add (unchecked-multiply a-hi b-lo)
                         (u64-shr w0 32))
        w1 (bit-and t mask32)
        w2 (u64-shr t 32)
        w1-cross (unchecked-add w1 (unchecked-multiply a-lo b-hi))
        hi (unchecked-add (unchecked-multiply a-hi b-hi)
                          (unchecked-add w2 (u64-shr w1-cross 32)))
        lo (unchecked-multiply a b)]
    [hi lo]))

(defn mul-fold
  "Compute the full 128-bit product a * b, then XOR the high and low halves.
   Pulled out via nth (not destructuring) to match the let-go shape."
  [a b]
  (let [r (u64*-128 a b)]
    (u64-xor (nth r 0)
             (nth r 1))))

(defn read-u64-le
  "Read 8 little-endian bytes from buf starting at offset i."
  [buf i]
  (loop [k 0
         acc 0]
    (if (>= k 8)
      acc
      (recur (inc k)
             (u64+ acc (u64-shl (nth buf (+ i k))
                                (* k 8)))))))

(defn read-u32-le
  "Read 4 little-endian bytes from buf starting at offset i. Returns a u64
   cell with the low 32 bits holding the assembled u32, high 32 bits zero."
  [buf i]
  (loop [k 0
         acc 0]
    (if (>= k 4)
      acc
      (recur (inc k)
             (u64+ acc (u64-shl (nth buf (+ i k))
                                (* k 8)))))))

;; ============================================================================
;; xxh3-64 constants
;; ============================================================================
;;
;; PRIME64 constants and 192-byte default secret reproduced verbatim from
;; upstream xxHash (Cyan4973/xxHash, xxhash.h). Copy of the table in
;; xsofy/hash.lg — see that file's comments for the byte-level provenance.

;; All five PRIME64 constants and 16 of the 24 secret cells have the high
;; bit set, so they overflow Long/MAX_VALUE as written. We wrap them in `ul`
;; uniformly (including the few that already fit in a positive long) so the
;; table reads consistently — extra `ul` calls on a small long are a no-op.

(def ^:private prime64-1 (ul 0x9E3779B185EBCA87))
(def ^:private prime64-2 (ul 0xC2B2AE3D27D4EB4F))
(def ^:private prime64-3 (ul 0x165667B19E3779F9))
(def ^:private prime64-4 (ul 0x85EBCA77C2B2AE63))
(def ^:private prime64-5 (ul 0x27D4EB2F165667C5))

(def ^:private xxh3-secret
  [(ul 0xbe4ba423396cfeb8)
   (ul 0x1cad21f72c81017c)
   (ul 0xdb979083e96dd4de)
   (ul 0x1f67b3b7a4a44072)
   (ul 0x78e5c0cc4ee679cb)
   (ul 0x2172ffcc7dd05a82)
   (ul 0x8e2443f7744608b8)
   (ul 0x4c263a81e69035e0)
   (ul 0xcb00c391bb52283c)
   (ul 0xa32e531b8b65d088)
   (ul 0x4ef90da297486471)
   (ul 0xd8acdea946ef1938)
   (ul 0x3f349ce33f76faa8)
   (ul 0x1d4f0bc7c7bbdcf9)
   (ul 0x3159b4cd4be0518a)
   (ul 0x647378d9c97e9fc8)
   (ul 0xc3ebd33483acc5ea)
   (ul 0xeb6313faffa081c5)
   (ul 0x49daf0b751dd0d17)
   (ul 0x9e68d429265516d3)
   (ul 0xfca1477d58be162b)
   (ul 0xce31d07ad1b8f88f)
   (ul 0x280416958f3acb45)
   (ul 0x7e404bbbcafbd7af)])

;; ============================================================================
;; xxh3-64 mixers
;; ============================================================================

(defn- xxh64-avalanche
  "XXH64 final-mix avalanche."
  [h]
  (let [h (u64-xor h (u64-shr h 33))
        h (u64* h prime64-2)
        h (u64-xor h (u64-shr h 29))
        h (u64* h prime64-3)
        h (u64-xor h (u64-shr h 32))]
    h))

(def ^:private prime-mx1 (ul 0x165667919E3779F9))
(def ^:private prime-mx2 (ul 0x9FB21C651E98DF25))

(defn- xxh3-avalanche
  "xxh3 fast avalanche."
  [h]
  (let [h (u64-xor h (u64-shr h 37))
        h (u64* h prime-mx1)
        h (u64-xor h (u64-shr h 32))]
    h))

(defn- xxh3-rrmxmx
  "xxh3 rrmxmx finalizer."
  [h len]
  (let [h (u64-xor h (u64-xor (u64-rotl h 49) (u64-rotl h 24)))
        h (u64* h prime-mx2)
        h (u64-xor h (u64+ (u64-shr h 35) len))
        h (u64* h prime-mx2)
        h (u64-xor h (u64-shr h 28))]
    h))

(defn- secret-le64
  "Return the LE u64 starting at BYTE offset (* 8 cell-idx) in the default
   secret. Only 8-aligned offsets are supported."
  [byte-offset]
  (let [cell (u64-shr byte-offset 3)
        rem  (bit-and byte-offset 7)]
    (assert (zero? rem)
            (str "secret-le64: byte offset " byte-offset
                 " is not 8-aligned (mod 8 = " rem ")"))
    (nth xxh3-secret cell)))

(defn- secret-le32-lo
  "Low 32 bits of secret cell 0."
  []
  (bit-and (nth xxh3-secret 0) 0xFFFFFFFF))

(defn- secret-le32-hi
  "High 32 bits of secret cell 0."
  []
  (u64-shr (nth xxh3-secret 0) 32))

(defn- swap32
  "Byte-reverse the low 32 bits of v."
  [v]
  (let [v (bit-and v 0xFFFFFFFF)]
    (bit-or (bit-or (bit-and (u64-shl v 24) 0xFF000000)
                    (bit-and (u64-shl v  8) 0x00FF0000))
            (bit-or (bit-and (u64-shr v  8) 0x0000FF00)
                    (bit-and (u64-shr v 24) 0x000000FF)))))

(defn- swap64
  "Byte-reverse a full u64. The top-byte mask 0xFF00000000000000 exceeds
   Long/MAX_VALUE so it must be wrapped in `ul` to avoid BigInt — all other
   masks fit in a positive long but we wrap uniformly for readability."
  [v]
  (bit-or
   (bit-or
    (bit-or (bit-and (u64-shl v 56) (ul 0xFF00000000000000))
            (bit-and (u64-shl v 40) 0x00FF000000000000))
    (bit-or (bit-and (u64-shl v 24) 0x0000FF0000000000)
            (bit-and (u64-shl v  8) 0x000000FF00000000)))
   (bit-or
    (bit-or (bit-and (u64-shr v  8) 0x00000000FF000000)
            (bit-and (u64-shr v 24) 0x0000000000FF0000))
    (bit-or (bit-and (u64-shr v 40) 0x000000000000FF00)
            (bit-and (u64-shr v 56) 0x00000000000000FF)))))

;; ============================================================================
;; xxh3-64 short input paths (0-16 bytes)
;; ============================================================================

(defn- xxh3-64-empty
  "Empty-input case."
  [seed]
  (xxh64-avalanche
   (u64-xor seed
            (u64-xor (secret-le64 56) (secret-le64 64)))))

(defn- xxh3-64-1to3
  "XXH3_len_1to3_64b."
  [input seed]
  (let [len (count input)
        c1  (nth input 0)
        c2  (nth input (u64-shr len 1))
        c3  (nth input (dec len))
        combined (bit-or (bit-or (u64-shl c1 16) (u64-shl c2 24))
                         (bit-or c3              (u64-shl len 8)))
        bitflip  (u64+ (u64-xor (secret-le32-lo) (secret-le32-hi)) seed)
        keyed    (u64-xor combined bitflip)]
    (xxh64-avalanche keyed)))

(defn- xxh3-64-4to8
  "XXH3_len_4to8_64b."
  [input seed]
  (let [len    (count input)
        seed'  (u64-xor seed (u64-shl (swap32 seed) 32))
        input1 (read-u32-le input 0)
        input2 (read-u32-le input (- len 4))
        bitflip (unchecked-subtract (u64-xor (secret-le64 8) (secret-le64 16))
                                    seed')
        input64 (u64+ input2 (u64-shl input1 32))
        keyed   (u64-xor input64 bitflip)]
    (xxh3-rrmxmx keyed len)))

(defn- xxh3-64-9to16
  "XXH3_len_9to16_64b."
  [input seed]
  (let [len      (count input)
        bitflip1 (u64+ (u64-xor (secret-le64 24) (secret-le64 32)) seed)
        bitflip2 (unchecked-subtract (u64-xor (secret-le64 40) (secret-le64 48))
                                     seed)
        input-lo (u64-xor (read-u64-le input 0)           bitflip1)
        input-hi (u64-xor (read-u64-le input (- len 8))   bitflip2)
        acc      (u64+ (u64+ (u64+ len (swap64 input-lo))
                             input-hi)
                       (mul-fold input-lo input-hi))]
    (xxh3-avalanche acc)))

;; ============================================================================
;; xxh3-64 medium input path (17-128 bytes)
;; ============================================================================

(defn- xxh3-mix-16b
  "XXH3_mix16B. mul-fold of two seeded XOR'd u64s read from input."
  [input input-off secret-byte-off seed]
  (let [input-lo (read-u64-le input input-off)
        input-hi (read-u64-le input (+ input-off 8))
        sec-lo   (secret-le64 secret-byte-off)
        sec-hi   (secret-le64 (+ secret-byte-off 8))]
    (mul-fold
     (u64-xor input-lo (u64+ sec-lo seed))
     (u64-xor input-hi (unchecked-subtract sec-hi seed)))))

(defn- xxh3-64-17to128
  "XXH3_len_17to128_64b."
  [input seed]
  (let [len (count input)
        acc (u64* len prime64-1)
        acc (if (> len 32)
              (let [acc (if (> len 64)
                          (let [acc (if (> len 96)
                                      (let [acc (u64+ acc (xxh3-mix-16b input 48 96 seed))
                                            acc (u64+ acc (xxh3-mix-16b input (- len 64) 112 seed))]
                                        acc)
                                      acc)
                                acc (u64+ acc (xxh3-mix-16b input 32 64 seed))
                                acc (u64+ acc (xxh3-mix-16b input (- len 48) 80 seed))]
                            acc)
                          acc)
                    acc (u64+ acc (xxh3-mix-16b input 16 32 seed))
                    acc (u64+ acc (xxh3-mix-16b input (- len 32) 48 seed))]
                acc)
              acc)
        acc (u64+ acc (xxh3-mix-16b input 0           0  seed))
        acc (u64+ acc (xxh3-mix-16b input (- len 16) 16  seed))]
    (xxh3-avalanche acc)))

;; ============================================================================
;; Byte-form secret + unaligned mix16B helper
;; ============================================================================
;;
;; The 129-240 and >= 241 paths both need to read u64s from the secret at
;; non-8-aligned byte offsets. We materialize a 192-byte view of the same
;; secret cells once and read it via read-u64-le.

(defn- secret-cells->bytes
  [cells]
  (loop [i 0
         acc []]
    (if (>= i (count cells))
      acc
      (let [c (nth cells i)]
        (recur (inc i)
               (loop [k 0 v acc]
                 (if (>= k 8)
                   v
                   (recur (inc k)
                          (conj v (bit-and (u64-shr c (* k 8)) 0xFF))))))))))

(def ^:private xxh3-secret-bytes (secret-cells->bytes xxh3-secret))

(defn- xxh3-mix-16b-byte-secret
  "Variant of xxh3-mix-16b that reads the secret from an arbitrary byte-offset
   (possibly unaligned) in the default byte-form secret."
  [input input-off secret-byte-off seed]
  (let [input-lo (read-u64-le input input-off)
        input-hi (read-u64-le input (+ input-off 8))
        sec-lo   (read-u64-le xxh3-secret-bytes secret-byte-off)
        sec-hi   (read-u64-le xxh3-secret-bytes (+ secret-byte-off 8))]
    (mul-fold
     (u64-xor input-lo (u64+ sec-lo seed))
     (u64-xor input-hi (unchecked-subtract sec-hi seed)))))

;; ============================================================================
;; xxh3-64 midsize input path (129-240 bytes)
;; ============================================================================

(def ^:private xxh3-midsize-startoffset 3)
(def ^:private xxh3-midsize-lastoffset  17)
(def ^:private xxh3-secret-size-min     136)

(defn- xxh3-64-129to240
  "XXH3_len_129to240_64b. Input is 129 to 240 bytes."
  [input seed]
  (let [len (count input)
        nb-rounds (u64-shr len 4)
        acc0 (u64* len prime64-1)
        acc1 (u64+ acc0 (xxh3-mix-16b input  0   0  seed))
        acc2 (u64+ acc1 (xxh3-mix-16b input 16  16  seed))
        acc3 (u64+ acc2 (xxh3-mix-16b input 32  32  seed))
        acc4 (u64+ acc3 (xxh3-mix-16b input 48  48  seed))
        acc5 (u64+ acc4 (xxh3-mix-16b input 64  64  seed))
        acc6 (u64+ acc5 (xxh3-mix-16b input 80  80  seed))
        acc7 (u64+ acc6 (xxh3-mix-16b input 96  96  seed))
        acc8 (u64+ acc7 (xxh3-mix-16b input 112 112 seed))
        acc-final (xxh3-avalanche acc8)
        acc-end-init (xxh3-mix-16b-byte-secret input (- len 16)
                                               (- xxh3-secret-size-min
                                                  xxh3-midsize-lastoffset)
                                               seed)]
    (loop [i 8
           acc-end acc-end-init]
      (if (>= i nb-rounds)
        (xxh3-avalanche (u64+ acc-final acc-end))
        (recur (inc i)
               (u64+ acc-end
                     (xxh3-mix-16b-byte-secret input (* 16 i)
                                               (+ (* 16 (- i 8))
                                                  xxh3-midsize-startoffset)
                                               seed)))))))

;; ============================================================================
;; xxh3-64 long input path (>= 241 bytes) — stripe accumulator
;; ============================================================================

(def ^:private xxh-stripe-len           64)
(def ^:private xxh-acc-nb                8)
(def ^:private xxh-secret-consume-rate   8)
(def ^:private xxh-secret-lastacc-start  7)
(def ^:private xxh-secret-mergeaccs-start 11)
(def ^:private xxh3-internalbuffer-size 256)
(def ^:private xxh-nb-stripes-per-block 16)
(def ^:private xxh-block-len           1024)

(def ^:private prime32-1 (ul 0x9E3779B1))
(def ^:private prime32-2 (ul 0x85EBCA77))
(def ^:private prime32-3 (ul 0xC2B2AE3D))

(def ^:private xxh3-init-acc
  [prime32-3 prime64-1 prime64-2 prime64-3
   prime64-4 prime32-2 prime64-5 prime32-1])

(defn- init-custom-secret
  [seed]
  (loop [i 0
         acc []]
    (if (>= i 12)
      acc
      (let [lo (u64+ (secret-le64 (* 16 i))         seed)
            hi (unchecked-subtract (secret-le64 (+ (* 16 i) 8)) seed)]
        (recur (inc i)
               (-> acc
                   (conj (bit-and lo            0xFF))
                   (conj (bit-and (u64-shr lo  8) 0xFF))
                   (conj (bit-and (u64-shr lo 16) 0xFF))
                   (conj (bit-and (u64-shr lo 24) 0xFF))
                   (conj (bit-and (u64-shr lo 32) 0xFF))
                   (conj (bit-and (u64-shr lo 40) 0xFF))
                   (conj (bit-and (u64-shr lo 48) 0xFF))
                   (conj (bit-and (u64-shr lo 56) 0xFF))
                   (conj (bit-and hi            0xFF))
                   (conj (bit-and (u64-shr hi  8) 0xFF))
                   (conj (bit-and (u64-shr hi 16) 0xFF))
                   (conj (bit-and (u64-shr hi 24) 0xFF))
                   (conj (bit-and (u64-shr hi 32) 0xFF))
                   (conj (bit-and (u64-shr hi 40) 0xFF))
                   (conj (bit-and (u64-shr hi 48) 0xFF))
                   (conj (bit-and (u64-shr hi 56) 0xFF))))))))

(defn- accumulate-512
  "XXH3_accumulate_512_scalar. One 64-byte stripe into the 8-lane accumulator."
  [acc input input-off secret-bytes secret-off]
  (let [d0 (read-u64-le input input-off)
        d1 (read-u64-le input (+ input-off 8))
        d2 (read-u64-le input (+ input-off 16))
        d3 (read-u64-le input (+ input-off 24))
        d4 (read-u64-le input (+ input-off 32))
        d5 (read-u64-le input (+ input-off 40))
        d6 (read-u64-le input (+ input-off 48))
        d7 (read-u64-le input (+ input-off 56))
        s0 (read-u64-le secret-bytes secret-off)
        s1 (read-u64-le secret-bytes (+ secret-off 8))
        s2 (read-u64-le secret-bytes (+ secret-off 16))
        s3 (read-u64-le secret-bytes (+ secret-off 24))
        s4 (read-u64-le secret-bytes (+ secret-off 32))
        s5 (read-u64-le secret-bytes (+ secret-off 40))
        s6 (read-u64-le secret-bytes (+ secret-off 48))
        s7 (read-u64-le secret-bytes (+ secret-off 56))
        k0 (u64-xor d0 s0)
        k1 (u64-xor d1 s1)
        k2 (u64-xor d2 s2)
        k3 (u64-xor d3 s3)
        k4 (u64-xor d4 s4)
        k5 (u64-xor d5 s5)
        k6 (u64-xor d6 s6)
        k7 (u64-xor d7 s7)
        mask32 0xFFFFFFFF
        m0 (u64* (bit-and k0 mask32) (u64-shr k0 32))
        m1 (u64* (bit-and k1 mask32) (u64-shr k1 32))
        m2 (u64* (bit-and k2 mask32) (u64-shr k2 32))
        m3 (u64* (bit-and k3 mask32) (u64-shr k3 32))
        m4 (u64* (bit-and k4 mask32) (u64-shr k4 32))
        m5 (u64* (bit-and k5 mask32) (u64-shr k5 32))
        m6 (u64* (bit-and k6 mask32) (u64-shr k6 32))
        m7 (u64* (bit-and k7 mask32) (u64-shr k7 32))
        a0 (nth acc 0)
        a1 (nth acc 1)
        a2 (nth acc 2)
        a3 (nth acc 3)
        a4 (nth acc 4)
        a5 (nth acc 5)
        a6 (nth acc 6)
        a7 (nth acc 7)]
    [(u64+ a0 (u64+ d1 m0))
     (u64+ a1 (u64+ d0 m1))
     (u64+ a2 (u64+ d3 m2))
     (u64+ a3 (u64+ d2 m3))
     (u64+ a4 (u64+ d5 m4))
     (u64+ a5 (u64+ d4 m5))
     (u64+ a6 (u64+ d7 m6))
     (u64+ a7 (u64+ d6 m7))]))

(defn- scramble-acc
  [acc secret-bytes secret-off]
  (loop [i 0
         out []]
    (if (>= i xxh-acc-nb)
      out
      (let [a0 (nth acc i)
            a1 (u64-xor a0 (u64-shr a0 47))
            a2 (u64-xor a1 (read-u64-le secret-bytes (+ secret-off (* 8 i))))
            a3 (u64* a2 prime32-1)]
        (recur (inc i) (conj out a3))))))

(defn- xxh3-mix-2accs
  [acc lane-off secret-bytes secret-off]
  (mul-fold
   (u64-xor (nth acc lane-off)
            (read-u64-le secret-bytes secret-off))
   (u64-xor (nth acc (inc lane-off))
            (read-u64-le secret-bytes (+ secret-off 8)))))

(defn- merge-accs
  [acc secret-bytes secret-off start]
  (loop [i 0
         result start]
    (if (>= i 4)
      (xxh3-avalanche result)
      (recur (inc i)
             (u64+ result
                   (xxh3-mix-2accs acc (* 2 i)
                                   secret-bytes
                                   (+ secret-off (* 16 i))))))))

(defn- process-stripes
  [acc input input-off n-stripes secret-bytes secret-base-off]
  (loop [s 0
         a acc]
    (if (>= s n-stripes)
      a
      (recur (inc s)
             (accumulate-512 a input (+ input-off (* s xxh-stripe-len))
                             secret-bytes (+ secret-base-off
                                             (* s xxh-secret-consume-rate)))))))

(defn- xxh3-64-long
  [input seed]
  (let [secret-bytes (if (zero? seed) xxh3-secret-bytes (init-custom-secret seed))
        len (count input)
        nb-blocks (quot (dec len) xxh-block-len)
        acc-after-blocks
        (loop [n 0
               a xxh3-init-acc]
          (if (>= n nb-blocks)
            a
            (let [block-off (* n xxh-block-len)
                  a-stripes (process-stripes a input block-off
                                             xxh-nb-stripes-per-block
                                             secret-bytes 0)
                  a-scrambled (scramble-acc a-stripes secret-bytes
                                            (- 192 xxh-stripe-len))]
              (recur (inc n) a-scrambled))))
        partial-off (* nb-blocks xxh-block-len)
        nb-stripes-partial (quot (- (dec len) (* nb-blocks xxh-block-len))
                                 xxh-stripe-len)
        acc-after-partial (process-stripes acc-after-blocks input partial-off
                                           nb-stripes-partial secret-bytes 0)
        acc-final (accumulate-512 acc-after-partial input (- len xxh-stripe-len)
                                  secret-bytes
                                  (- 192 xxh-stripe-len xxh-secret-lastacc-start))]
    (merge-accs acc-final secret-bytes
                xxh-secret-mergeaccs-start
                (u64* len prime64-1))))

;; ============================================================================
;; xxh3-64 dispatch
;; ============================================================================

(defn xxh3-64
  "Compute the xxh3-64 hash of a byte vector with optional seed."
  ([input] (xxh3-64 input 0))
  ([input seed]
   (let [len (count input)]
     (cond
       (zero? len)  (xxh3-64-empty    seed)
       (<= len 3)   (xxh3-64-1to3     input seed)
       (<= len 8)   (xxh3-64-4to8     input seed)
       (<= len 16)  (xxh3-64-9to16    input seed)
       (<= len 128) (xxh3-64-17to128  input seed)
       (<= len 240) (xxh3-64-129to240 input seed)
       :else        (xxh3-64-long     input seed)))))

;; ============================================================================
;; Streaming xxh3-64 API (xxh3-init / xxh3-update / xxh3-digest)
;; ============================================================================

(defn xxh3-init
  ([] (xxh3-init 0))
  ([seed]
   (let [secret-bytes (if (zero? seed) xxh3-secret-bytes (init-custom-secret seed))]
     [xxh3-init-acc [] 0 0 seed secret-bytes nil])))

(defn- consume-stripes
  [acc nb-stripes-so-far bytes input-off n-stripes secret-bytes]
  (loop [remaining n-stripes
         off       input-off
         a         acc
         sof       nb-stripes-so-far]
    (if (zero? remaining)
      [a sof]
      (let [room-in-block     (- xxh-nb-stripes-per-block sof)
            this-iter         (if (< remaining room-in-block) remaining room-in-block)
            a-after           (loop [k 0 acc-k a]
                                (if (>= k this-iter)
                                  acc-k
                                  (recur (inc k)
                                         (accumulate-512 acc-k bytes
                                                         (+ off (* k xxh-stripe-len))
                                                         secret-bytes
                                                         (* (+ sof k)
                                                            xxh-secret-consume-rate)))))
            sof-pre-scramble  (+ sof this-iter)
            block-full?       (= sof-pre-scramble xxh-nb-stripes-per-block)
            a-scrambled       (if block-full?
                                (scramble-acc a-after secret-bytes
                                              (- 192 xxh-stripe-len))
                                a-after)
            sof-final         (if block-full? 0 sof-pre-scramble)]
        (recur (- remaining this-iter)
               (+ off (* this-iter xxh-stripe-len))
               a-scrambled
               sof-final)))))

(defn- slice-stripe
  [buf end-exclusive]
  (vec (subvec buf (- end-exclusive xxh-stripe-len) end-exclusive)))

(defn xxh3-update
  [state bytes]
  (let [n (count bytes)]
    (if (zero? n)
      state
      (let [acc       (nth state 0)
            buffer    (nth state 1)
            total-len (nth state 2)
            sof       (nth state 3)
            seed      (nth state 4)
            secret    (nth state 5)
            last-stripe (nth state 6)
            new-total (u64+ total-len n)
            buf-size  (count buffer)
            room      (- xxh3-internalbuffer-size buf-size)]
        (if (<= n room)
          [acc (into buffer bytes) new-total sof seed secret last-stripe]
          (let [fill-end    room
                filled-buf  (into buffer (subvec bytes 0 fill-end))
                cs-result   (consume-stripes acc sof filled-buf 0
                                             (quot xxh3-internalbuffer-size
                                                   xxh-stripe-len)
                                             secret)
                acc-after   (nth cs-result 0)
                sof-after   (nth cs-result 1)
                last-after  (slice-stripe filled-buf xxh3-internalbuffer-size)
                remaining   (- n fill-end)]
            (if (<= remaining xxh3-internalbuffer-size)
              [acc-after (vec (subvec bytes fill-end n))
               new-total sof-after seed secret last-after]
              (let [bulk-input-len (- remaining 1)
                    n-stripes      (quot bulk-input-len xxh-stripe-len)
                    bulk-bytes     (* n-stripes xxh-stripe-len)
                    cs-bulk        (consume-stripes acc-after sof-after
                                                    bytes fill-end
                                                    n-stripes secret)
                    acc-bulk       (nth cs-bulk 0)
                    sof-bulk       (nth cs-bulk 1)
                    tail-start     (+ fill-end bulk-bytes)
                    new-buf        (vec (subvec bytes tail-start n))
                    last-bulk      (if (pos? n-stripes)
                                     (slice-stripe bytes tail-start)
                                     last-after)]
                [acc-bulk new-buf new-total sof-bulk seed secret last-bulk]))))))))

(defn xxh3-digest
  [state]
  (let [acc         (nth state 0)
        buffer      (nth state 1)
        total-len   (nth state 2)
        sof         (nth state 3)
        seed        (nth state 4)
        secret      (nth state 5)
        last-stripe (nth state 6)]
    (if (<= total-len 240)
      (xxh3-64 buffer seed)
      (let [buf-size (count buffer)
            digest-result
            (if (>= buf-size xxh-stripe-len)
              (let [nb-stripes-final (quot (dec buf-size) xxh-stripe-len)
                    cs (consume-stripes acc sof buffer 0 nb-stripes-final secret)
                    acc-after-stripes (nth cs 0)
                    last-stripe-off (- buf-size xxh-stripe-len)]
                (accumulate-512 acc-after-stripes buffer last-stripe-off
                                secret
                                (- 192 xxh-stripe-len
                                   xxh-secret-lastacc-start)))
              (let [catchup-size (- xxh-stripe-len buf-size)
                    synth-stripe (into (vec (subvec last-stripe
                                                    (- xxh-stripe-len catchup-size)
                                                    xxh-stripe-len))
                                       buffer)]
                (accumulate-512 acc synth-stripe 0 secret
                                (- 192 xxh-stripe-len
                                   xxh-secret-lastacc-start))))]
        (merge-accs digest-result secret
                    xxh-secret-mergeaccs-start
                    (u64* total-len prime64-1))))))

;; ============================================================================
;; Salt-key encoding (v1)
;; ============================================================================
;;
;; Same byte layout as xsofy.hash/encode-salt. On JVM we can use .getBytes
;; "UTF-8" instead of hand-rolling codepoint->utf8 — the let-go version
;; re-implements UTF-8 because let-go strings iterate as codepoints with no
;; built-in .getBytes for the String type.

(def ^:private salt-tag-kw  1)
(def ^:private salt-tag-int 2)
(def ^:private salt-tag-str 3)
(def ^:private salt-tag-vec 4)

(defn- int->8-be
  "Big-endian byte encoding of the low 64 bits of n as a length-8 vector
   of int byte values [0, 255]."
  [n]
  (loop [i 7
         acc []]
    (if (neg? i)
      acc
      (recur (dec i)
             (conj acc (bit-and (u64-shr n (* i 8)) 0xFF))))))

(defn- utf8-bytes
  "Convert a JVM string to its UTF-8 byte representation as a vector of int
   byte values in [0, 255]. Uses String.getBytes(\"UTF-8\"), then masks each
   signed byte back into the unsigned range — Java byte is signed, so a high
   bit prints as e.g. -61 instead of 195 without the mask."
  [s]
  (mapv (fn [b] (bit-and (long b) 0xFF))
        (.getBytes ^String s "UTF-8")))

(defn encode-salt
  "Canonical byte encoding of a salt-key (v1)."
  [s]
  (cond
    (keyword? s)
    (let [name-bytes (utf8-bytes (name s))]
      (assert (< (count name-bytes) 256)
              (str "Keyword name exceeds 255 bytes: " (name s)))
      (into [salt-tag-kw (count name-bytes)] name-bytes))

    (integer? s)
    (into [salt-tag-int] (int->8-be s))

    (string? s)
    (let [b (utf8-bytes s)]
      (assert (< (count b) 256)
              (str "String exceeds 255 bytes (len=" (count b) ")"))
      (into [salt-tag-str (count b)] b))

    (vector? s)
    (let [n (count s)]
      (assert (< n 256)
              (str "Vector exceeds 255 elements (len=" n ")"))
      (reduce (fn [acc el] (into acc (encode-salt el)))
              [salt-tag-vec n]
              s))

    :else
    (throw (ex-info "encode-salt: unsupported type"
                    {:value s :type (type s)}))))

;; ============================================================================
;; Cross-check: published vectors
;; ============================================================================
;;
;; Same 25 reference vectors as xsofy/test/hash_test.lg's
;; xxh3-published-vectors. Each row: [input-bytes seed expected-u64].
;; The expected values come from Python xxhash 3.7.0 wrapping the official
;; C library, so a match here means the JVM port matches the C reference.
;; Combined with the let-go suite (which uses the same vectors), this
;; transitively proves the let-go port matches the JVM port.

(defn- str->bytes
  "ASCII-only string-to-byte-vector helper for the published vectors."
  [s]
  (mapv int s))

(def published-vectors
  [;; ---- short input (0-16 bytes) ----
   [[]                                                  0  (ul 0x2d06800538d394c2)]
   [(str->bytes "a")                                    0  (ul 0xe6c632b61e964e1f)]
   [(str->bytes "ab")                                   0  (ul 0xa873719c24d5735c)]
   [(str->bytes "abc")                                  0  (ul 0x78af5f94892f3950)]
   [(str->bytes "abcd")                                 0  (ul 0x6497a96f53a89890)]
   [(str->bytes "abcde")                                0  (ul 0x55c65158ee9e652d)]
   [(str->bytes "abcdefgh")                             0  (ul 0x6f45a76842a96483)]
   [(str->bytes "abcdefghi")                            0  (ul 0xe0dde4fc174590a0)]
   [(str->bytes "abcdefghijklmno")                      0  (ul 0xa8edaf6dc2724d85)]
   [(str->bytes "abcdefghijklmnop")                     0  (ul 0x3d3ccac9af14d8a8)]
   ;; ---- medium input (17-128) ----
   [(str->bytes "abcdefghijklmnopq")                    0  (ul 0xca7f3571df47cacf)]
   [(vec (repeat 32 (int \a)))                          0  (ul 0xdbb6341d93939622)]
   [(vec (repeat 33 (int \a)))                          0  (ul 0x2bf5e46a41871e0d)]
   [(vec (repeat 64 (int \a)))                          0  (ul 0x2dddcef07d26ee8c)]
   [(vec (repeat 65 (int \a)))                          0  (ul 0xfcf352f3db82a064)]
   [(vec (repeat 96 (int \a)))                          0  (ul 0x8ef986534f394e7f)]
   [(vec (repeat 97 (int \a)))                          0  (ul 0xfb22fdde75565533)]
   [(vec (repeat 128 (int \a)))                         0  (ul 0x7a22200aadc3d36c)]
   ;; ---- seeded variants ----
   [[]                                                  42 (ul 0xb029411ff43d84d2)]
   [(str->bytes "a")                                    42 (ul 0x4c437dd47f0716f4)]
   [(str->bytes "abc")                                  42 (ul 0xd8438def21bbdcc3)]
   [(str->bytes "abcdefgh")                             42 (ul 0x5b58c256927cdea8)]
   [(str->bytes "abcdefghijklmnop")                     42 (ul 0x13d397cb29911732)]
   [(vec (repeat 21 (int \a)))                          42 (ul 0x50c511c2f277526f)]
   [(vec (repeat 128 (int \a)))                         42 (ul 0xd9081aac2e8df21c)]
   ;; ---- midsize input (129-240 bytes), PR1b ----
   [(vec (repeat 129  (int \a)))                        0  (ul 0x1586e7ed07cba75b)]
   [(vec (repeat 160  (int \a)))                        0  (ul 0x74fbed24a55cc08d)]
   [(vec (repeat 240  (int \a)))                        0  (ul 0x993c46d96a01b5c6)]
   [(vec (repeat 160  (int \a)))                        42 (ul 0xcaef7a6a2e008e5d)]
   [(vec (repeat 240  (int \a)))                        42 (ul 0xe70703a4f2c5dc56)]
   ;; ---- long input (>= 241 bytes), PR1b ----
   [(vec (repeat 241  (int \a)))                        0  (ul 0xf6cfef5c5aca1930)]
   [(vec (repeat 256  (int \a)))                        0  (ul 0x3fdb4ff1846c90f3)]
   [(vec (repeat 512  (int \a)))                        0  (ul 0x4659a548a9cc8db1)]
   [(vec (repeat 1024 (int \a)))                        0  (ul 0x4a5d6b09a9587a1c)]
   [(vec (repeat 1025 (int \a)))                        0  (ul 0xd46a63acfb8da1ea)]
   [(vec (repeat 4096 (int \a)))                        0  (ul 0x88a30670e919701e)]
   [(vec (repeat 256  (int \a)))                        42 (ul 0x17dcff7ff17e01cf)]
   [(vec (repeat 1024 (int \a)))                        42 (ul 0x4f582304f1a00fb6)]])

(defn- format-u64
  "Format a (possibly negative) signed int64 as a 16-hex-digit unsigned u64.
   We rely on Java's Long/toHexString for the u64 reinterpretation — it
   prints the two's-complement bit pattern directly, no mask needed."
  [n]
  (let [hex (Long/toHexString n)]
    (str "0x" (apply str (repeat (- 16 (count hex)) "0")) hex)))

(defn -main [& _args]
  (let [results
        (doall
         (map-indexed
          (fn [i row]
            (let [input    (nth row 0)
                  seed     (nth row 1)
                  expected (nth row 2)
                  actual   (xxh3-64 input seed)
                  ok       (= expected actual)]
              (println (format "  [%2d] len=%3d seed=%-3d expected=%s actual=%s %s"
                               i (count input) seed
                               (format-u64 expected)
                               (format-u64 actual)
                               (if ok "OK" "MISMATCH")))
              ok))
          published-vectors))
        n-pass (count (filter identity results))
        n-total (count results)]
    ;; Also exercise encode-salt on a representative salt-key — anything
    ;; that fails here would surface as a downstream `combine` mismatch.
    (let [s1 (encode-salt :foo)
          s2 (encode-salt 1)
          s3 (encode-salt 256)
          s4 (encode-salt -1)
          s5 (encode-salt -9223372036854775808)
          s6 (encode-salt "hi")
          s7 (encode-salt [:foo 1])
          s8-kw  (encode-salt [:a "b"])
          s8-str (encode-salt [:a :b])
          checks [[:keyword         [1 3 102 111 111] s1]
                  [:int-1           [2 0 0 0 0 0 0 0 1] s2]
                  [:int-256         [2 0 0 0 0 0 0 1 0] s3]
                  [:int-neg-1       [2 255 255 255 255 255 255 255 255] s4]
                  [:int-long-min    [2 128 0 0 0 0 0 0 0] s5]
                  [:string          [3 2 104 105] s6]
                  [:vector          [4 2  1 3 102 111 111  2 0 0 0 0 0 0 0 1] s7]]]
      (println)
      (println "encode-salt fixtures:")
      (doseq [[label expected actual] checks]
        (let [ok (= expected actual)]
          (println (format "  %-16s %s" (str label) (if ok "OK" "MISMATCH")))
          (when-not ok
            (println "    expected:" expected)
            (println "    actual:  " actual))))
      (let [shape-ok (not= s8-kw s8-str)]
        (println (format "  %-16s %s" ":shape-distinct"
                         (if shape-ok "OK" "MISMATCH")))))
    ;; ---- Streaming equivalence cross-check (PR1b) ----
    ;; The xxh3-init / xxh3-update / xxh3-digest path must agree with the
    ;; one-shot xxh3-64 across every input regime, and the result must be
    ;; independent of where the chunk boundaries fall.
    (println)
    (println "streaming equivalence checks:")
    (let [stream-cases
          [[100 0 [[100]]]
           [256 0 [[100 100 56] [256] [1 255]]]
           [1024 0 [[1024] [256 256 256 256] [1 1023]]]
           [4096 0 [[4096] [256 3840] [1024 1024 1024 1024]]]
           [1500 42 [[1500] [500 500 500] [1024 476]]]]
          stream-results
          (for [[size seed chunkings] stream-cases
                chunks chunkings]
            (let [bytes (vec (repeat size (int \a)))
                  one-shot (xxh3-64 bytes seed)
                  streamed (xxh3-digest
                            (reduce (fn [s n]
                                      (xxh3-update s (vec (repeat n (int \a)))))
                                    (xxh3-init seed)
                                    chunks))
                  ok (= one-shot streamed)]
              (println (format "  size=%-5d seed=%-3d chunks=%-25s %s"
                               size seed (str chunks) (if ok "OK" "MISMATCH")))
              ok))
          n-stream-pass (count (filter identity stream-results))
          n-stream-total (count stream-results)]
      (println)
      (println (format "%d / %d published vectors match." n-pass n-total))
      (println (format "%d / %d streaming cases match." n-stream-pass n-stream-total))
      (if (and (= n-pass n-total) (= n-stream-pass n-stream-total))
        (do (println "All published vectors and streaming cases match. JVM cross-validation: PASS.")
            (System/exit 0))
        (do (println "JVM cross-validation: FAIL.")
            (System/exit 1))))))

(apply -main *command-line-args*)
