% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
FROM books METADATA _score
| WHERE match(title, "Return") AND match(author, "Tolkien")
| EVAL first_score = score(match(title, "Return"))
```


