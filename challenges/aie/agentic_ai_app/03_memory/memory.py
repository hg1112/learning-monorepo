###############################################################################
import time
import sqlite3

## SHORT TERM MEMORY:


_short_term: dict[str, str] = {}

def remember(key: str, value: str) -> str:
    _short_term[key] = value
    return f"Remember {key} = {value}"

def recall(key: str) -> str:
    return _short_term.get(key, f"No memory found for key: {key}")


###############################################################################


## LONG TERM MEMORY:

DB_PATH = "memory.db"

def _init_db():
    con = sqlite3.connect(DB_PATH)
    con.execute("CREATE TABLE IF NOT EXISTS memories(key TEXT, value TEXT, timestamp REAL)")
    con.commit()
    con.close()


def save_memory(key: str, value: str):
    _init_db()
    ts = time.time()
    con = sqlite3.connect(DB_PATH)
    con.execute("INSERT INTO memories VALUES (?, ?, ?)", (key, value, ts))
    con.commit()
    con.close()
    return f"Saved memory ({key}, {value}, {ts})"

def search_memory(key: str):
    _init_db()
    con = sqlite3.connect(DB_PATH)
    cursor = con.execute("SELECT * FROM memories WHERE key = ?", (key,))
    results = cursor.fetchall()
    con.close()
    return f"Found memories : {results}"

def load_recent_memories(limit : int = 5) -> str:
    _init_db()
    con = sqlite3.connect(DB_PATH)
    cursor = con.execute("SELECT * FROM memories ORDER BY timestamp DESC LIMIT ?", (limit,))
    results = cursor.fetchall()
    con.close()
    return f"Recent memories : {results}"

###############################################################################


## LONG TERM MEMORY With VECTOR SEARCH:

from sentence_transformers import SentenceTransformer
import numpy as np

_model = SentenceTransformer("all-MiniLM-L6-v2")  # small, fast, good quality
_doc_vectors = []
_docs: list[str] = []

def embed_documents(docs: list[str]) -> None:
    global _doc_vectors, _docs
    _docs.extend(docs)
    for doc in docs:
        _doc_vectors.append(_model.encode(doc, convert_to_numpy=True))

def cosine_similarity(a, b):
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b))

def semantic_search(query: str, k: int = 3) -> list[str]:
    vector = _model.encode(query, convert_to_numpy=True)
    results = []
    for i in range(len(_doc_vectors)):
        score = cosine_similarity(vector, _doc_vectors[i])
        results.append((_docs[i], score))
    results.sort(key=lambda x: x[1], reverse=True)
    return [r[0] for r in results[:k]]

