package main

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"strings"
	"unicode/utf8"

	"github.com/andybalholm/brotli"
	"github.com/klauspost/compress/zstd"
)

const maxSearchBody = 4 << 20

type indexDoc struct {
	id                                                                          int64
	uid, respStatus                                                             int
	pkg, method, scheme, host, path, sni, dst, reqHeaders, respHeaders, errText string
	reqBody, respBody                                                           []byte
}

func parseHeaders(value string) map[string][]string {
	h := map[string][]string{}
	_ = json.Unmarshal([]byte(value), &h)
	return h
}
func headerVal(h map[string][]string, name string) string {
	for k, v := range h {
		if strings.EqualFold(k, name) && len(v) > 0 {
			return v[0]
		}
	}
	return ""
}
func headerText(raw string) string {
	h := parseHeaders(raw)
	var b strings.Builder
	for k, vs := range h {
		for _, v := range vs {
			b.WriteString(k)
			b.WriteString(": ")
			b.WriteString(v)
			b.WriteByte('\n')
		}
	}
	return b.String()
}
func readSearchBody(r io.Reader) []byte {
	b, _ := io.ReadAll(io.LimitReader(r, maxSearchBody))
	return b
}
func decodeSearchBody(raw []byte, headers string) string {
	if len(raw) == 0 {
		return ""
	}
	h := parseHeaders(headers)
	ctype := strings.ToLower(headerVal(h, "Content-Type"))
	if strings.HasPrefix(ctype, "image/") || strings.HasPrefix(ctype, "audio/") || strings.HasPrefix(ctype, "video/") || strings.Contains(ctype, "application/octet-stream") || strings.Contains(ctype, "application/zip") || strings.Contains(ctype, "application/pdf") {
		return ""
	}
	enc := strings.ToLower(headerVal(h, "Content-Encoding"))
	parts := strings.Split(enc, ",")
	data := raw
	for i := len(parts) - 1; i >= 0; i-- {
		switch strings.TrimSpace(parts[i]) {
		case "", "identity":
		case "br":
			data = readSearchBody(brotli.NewReader(bytes.NewReader(data)))
		case "gzip":
			r, e := gzip.NewReader(bytes.NewReader(data))
			if e != nil {
				return ""
			}
			data = readSearchBody(r)
			_ = r.Close()
		case "deflate":
			r, e := zlib.NewReader(bytes.NewReader(data))
			if e == nil {
				data = readSearchBody(r)
				_ = r.Close()
			} else {
				f := flate.NewReader(bytes.NewReader(data))
				data = readSearchBody(f)
				_ = f.Close()
			}
		case "zstd":
			r, e := zstd.NewReader(bytes.NewReader(data), zstd.WithDecoderMaxMemory(64<<20), zstd.WithDecoderConcurrency(1))
			if e != nil {
				return ""
			}
			data = readSearchBody(r)
			r.Close()
		default:
			return ""
		}
	}
	if bytes.IndexByte(data, 0) >= 0 {
		return ""
	}
	if !utf8.Valid(data) {
		if !strings.HasPrefix(ctype, "text/") && !strings.Contains(ctype, "json") && !strings.Contains(ctype, "xml") && !strings.Contains(ctype, "javascript") {
			return ""
		}
		return strings.ToValidUTF8(string(data), "�")
	}
	text := string(data)
	if strings.Contains(ctype, "application/x-www-form-urlencoded") {
		if u, e := url.QueryUnescape(text); e == nil {
			text += "\n" + u
		}
	}
	return text
}
func (d indexDoc) content() string {
	rawURL := d.scheme + "://" + d.host + d.path
	decodedURL, _ := url.QueryUnescape(rawURL)
	return strings.Join([]string{fmt.Sprint(d.id), d.pkg, fmt.Sprint(d.uid), fmt.Sprint(d.respStatus), d.method, d.scheme, d.host, d.path, d.sni, d.dst, rawURL, decodedURL, d.reqHeaders, headerText(d.reqHeaders), d.respHeaders, headerText(d.respHeaders), decodeSearchBody(d.reqBody, d.reqHeaders), decodeSearchBody(d.respBody, d.respHeaders), d.errText}, "\n")
}
func initSearch(db *sql.DB) error {
	_, e := db.Exec(`PRAGMA busy_timeout=5000;
 CREATE VIRTUAL TABLE IF NOT EXISTS tpc_search_fts USING fts5(content, tokenize='trigram');
 CREATE TABLE IF NOT EXISTS tpc_search_pending(id INTEGER PRIMARY KEY);
 CREATE TABLE IF NOT EXISTS tpc_search_state(id INTEGER PRIMARY KEY CHECK(id=1), cursor INTEGER NOT NULL, max_id INTEGER NOT NULL);
 CREATE TRIGGER IF NOT EXISTS tpc_search_insert AFTER INSERT ON exchanges BEGIN INSERT OR IGNORE INTO tpc_search_pending(id) VALUES(new.id); END;
 CREATE TRIGGER IF NOT EXISTS tpc_search_delete AFTER DELETE ON exchanges BEGIN DELETE FROM tpc_search_pending WHERE id=old.id; DELETE FROM tpc_search_fts WHERE rowid=old.id; END;
 INSERT OR IGNORE INTO tpc_search_state(id,cursor,max_id) SELECT 1,0,coalesce(max(id),0) FROM exchanges;`)
	return e
}
func backfillSearch(db *sql.DB) error {
	for {
		var cursor, max int64
		if e := db.QueryRow(`SELECT cursor,max_id FROM tpc_search_state WHERE id=1`).Scan(&cursor, &max); e != nil {
			return e
		}
		if cursor >= max {
			return nil
		}
		rows, e := db.Query(`SELECT id FROM exchanges WHERE id>? AND id<=? ORDER BY id LIMIT 200`, cursor, max)
		if e != nil {
			return e
		}
		ids := make([]int64, 0, 200)
		for rows.Next() {
			var id int64
			if e = rows.Scan(&id); e != nil {
				rows.Close()
				return e
			}
			ids = append(ids, id)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		last := max
		if len(ids) > 0 {
			last = ids[len(ids)-1]
		}
		tx, e := db.Begin()
		if e != nil {
			return e
		}
		for _, id := range ids {
			if _, e = tx.Exec(`INSERT OR IGNORE INTO tpc_search_pending(id) VALUES(?)`, id); e != nil {
				tx.Rollback()
				return e
			}
		}
		if _, e = tx.Exec(`UPDATE tpc_search_state SET cursor=max(cursor,?) WHERE id=1`, last); e != nil {
			tx.Rollback()
			return e
		}
		if e = tx.Commit(); e != nil {
			return e
		}
	}
}
func syncSearch(db *sql.DB) (int64, error) {
	db.SetMaxOpenConns(1)
	if e := initSearch(db); e != nil {
		return 0, e
	}
	if e := backfillSearch(db); e != nil {
		return 0, e
	}
	var cutoff int64
	if e := db.QueryRow(`SELECT coalesce(max(id),0) FROM exchanges`).Scan(&cutoff); e != nil {
		return 0, e
	}
	for {
		rows, e := db.Query(`SELECT e.id,s.uid,coalesce(s.package,''),coalesce(e.method,''),coalesce(e.scheme,''),coalesce(e.host,''),coalesce(e.path,''),coalesce(e.sni,''),coalesce(e.dst,''),coalesce(e.req_headers,''),coalesce(e.resp_headers,''),e.req_body,e.resp_body,coalesce(e.error,''),coalesce(e.resp_status,0) FROM tpc_search_pending p JOIN exchanges e ON e.id=p.id JOIN sessions s ON s.id=e.session_id WHERE e.id<=? ORDER BY e.id LIMIT 8`, cutoff)
		if e != nil {
			return 0, e
		}
		docs := make([]indexDoc, 0, 8)
		for rows.Next() {
			var d indexDoc
			if e = rows.Scan(&d.id, &d.uid, &d.pkg, &d.method, &d.scheme, &d.host, &d.path, &d.sni, &d.dst, &d.reqHeaders, &d.respHeaders, &d.reqBody, &d.respBody, &d.errText, &d.respStatus); e != nil {
				rows.Close()
				return 0, e
			}
			docs = append(docs, d)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return 0, e
		}
		if len(docs) == 0 {
			return cutoff, nil
		}
		texts := make([]string, len(docs))
		for i := range docs {
			texts[i] = docs[i].content()
		}
		tx, e := db.Begin()
		if e != nil {
			return 0, e
		}
		for i, d := range docs {
			if _, e = tx.Exec(`INSERT OR REPLACE INTO tpc_search_fts(rowid,content) VALUES(?,?)`, d.id, texts[i]); e != nil {
				tx.Rollback()
				return 0, e
			}
			if _, e = tx.Exec(`DELETE FROM tpc_search_pending WHERE id=?`, d.id); e != nil {
				tx.Rollback()
				return 0, e
			}
		}
		if e = tx.Commit(); e != nil {
			return 0, e
		}
	}
}
