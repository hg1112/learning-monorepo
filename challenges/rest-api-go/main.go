package main

import (
	"encoding/json"
	"net/http"
)

type Book struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Author string `json:"author"`
}

var books = []Book{
	{ID: "1", Title: "The Hobbit", Author: "J.R.R. Tolkien"},
	{ID: "2", Title: "1984", Author: "George Orwell"},
}

func getBooks(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	json.NewEncoder(w).Encode(books)
}

func createBook(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	var newBook Book

	_ = json.NewDecoder(r.Body).Decode(&newBook)

	books = append(books, newBook)

	json.NewEncoder(w).Encode(newBook)
}

func main() {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /books", getBooks)
	mux.HandleFunc("POST /books", createBook)

	println("Server is running on http://localhost:8080")
	http.ListenAndServe(":8080", mux)
}
