"""The commonest GloVe words' vectors, small, for Winnow's WordMeanings (a text's meaning as a whole).

The shipped file (classifier/src/main/resources/.../word-meanings.bin.gz) was made with:

    curl -LO https://downloads.cs.stanford.edu/nlp/data/glove.6B.zip   # 822 MB
    unzip glove.6B.zip glove.6B.50d.txt
    python3 word_meanings.py glove.6B.50d.txt 20000 word-meanings.bin.gz

GloVe (Pennington, Socher and Manning, Stanford) is public domain under the ODC Public Domain
Dedication and License. Each vector is scaled to length 1, then stored as signed bytes with one
scale per word. Format (big-endian, gzipped): int magic 0x574d4e47 ("WMNG"), int count, int dim,
then per word: Java modified-UTF-8 string (u2 length + bytes), float scale, dim bytes. Needs numpy.

usage: python3 word_meanings.py glove.6B.50d.txt N out.bin.gz
"""
import gzip
import re
import struct
import sys

import numpy as np

src, n_words, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
word_re = re.compile(r"^[a-z]{2,}$")
words, vecs = [], []
with open(src, encoding="utf-8") as f:
    for line in f:
        w, rest = line.split(" ", 1)
        if not word_re.match(w):
            continue
        words.append(w)
        vecs.append(np.array(rest.split(), dtype=np.float32))
        if len(words) >= n_words:
            break
X = np.stack(vecs)
X /= np.linalg.norm(X, axis=1, keepdims=True)
dim = X.shape[1]
with gzip.open(out, "wb", compresslevel=9) as g:
    g.write(struct.pack(">iii", 0x574D4E47, len(words), dim))
    for w, v in zip(words, X):
        scale = float(np.abs(v).max()) / 127.0
        q = np.clip(np.round(v / scale), -127, 127).astype(np.int8)
        b = w.encode("utf-8")
        g.write(struct.pack(">H", len(b)) + b + struct.pack(">f", scale) + q.tobytes())
print(f"{len(words)} words, {dim} dims")
