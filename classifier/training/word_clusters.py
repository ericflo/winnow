"""Groups the commonest GloVe words by meaning (spherical k-means), for Winnow's WordClusters.

The shipped groups (classifier/src/main/resources/.../word-clusters.txt.gz) were made with:

    curl -LO https://downloads.cs.stanford.edu/nlp/data/glove.6B.zip   # 822 MB
    unzip glove.6B.zip glove.6B.100d.txt
    python3 word_clusters.py glove.6B.100d.txt 30000 512 word-clusters.txt.gz

GloVe (Pennington, Socher and Manning, Stanford) is public domain under the ODC Public Domain
Dedication and License. Deterministic for a given input (seeded); needs numpy.

usage: python3 word_clusters.py glove.6B.100d.txt N K out.txt.gz
"""
import gzip
import re
import sys

import numpy as np

src, n_words, k, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
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

rng = np.random.default_rng(7)
# k-means++ seeding on cosine distance.
centers = [X[rng.integers(len(X))]]
d = 1 - X @ centers[0]
for _ in range(1, k):
    p = np.clip(d, 0, None) ** 2
    c = X[rng.choice(len(X), p=p / p.sum())]
    centers.append(c)
    d = np.minimum(d, 1 - X @ c)
C = np.stack(centers)
for it in range(40):
    assign = np.argmax(X @ C.T, axis=1)
    newC = np.zeros_like(C)
    np.add.at(newC, assign, X)
    empty = np.linalg.norm(newC, axis=1) == 0
    newC[empty] = X[rng.integers(len(X), size=empty.sum())]
    newC /= np.linalg.norm(newC, axis=1, keepdims=True)
    moved = float(np.mean(np.sum(newC * C, axis=1)))
    C = newC
    if moved > 0.99999:
        break
assign = np.argmax(X @ C.T, axis=1)

with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as g:
    for w, a in zip(words, assign):
        g.write(f"{w}\t{a}\n")

index = {w: a for w, a in zip(words, assign)}
for probe in ["sale", "discount", "vote", "package", "appointment", "verify", "dinner", "refund", "petition", "coupon"]:
    if probe in index:
        mates = [w for w in words[:12000] if index[w] == index[probe]][:14]
        print(f"{probe}: {' '.join(mates)}")
sizes = np.bincount(assign, minlength=k)
print(f"{len(words)} words, {k} groups, sizes min {sizes.min()} median {int(np.median(sizes))} max {sizes.max()}")
