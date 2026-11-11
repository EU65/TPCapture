package main

import (
	"bufio"
	"crypto/rand"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"syscall"
	"time"
)

const wsFrameLimit = 32 << 20

type wsMeta struct {
	source, dst, sni, scheme, host, path string
}

func isWebSocket(req *http.Request) bool {
	return strings.EqualFold(strings.TrimSpace(req.Header.Get("Upgrade")), "websocket")
}

func dialUpstream(dst, sni, scheme string) (net.Conn, error) {
	d := &net.Dialer{Timeout: 15 * time.Second, Control: func(network, address string, raw syscall.RawConn) error {
		var inner error
		if e := raw.Control(func(fd uintptr) {
			currentMark, err := syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK)
			if err != nil {
				inner = err
				return
			}
			inner = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK, (currentMark|int(bypassMark))&^int(singleRouteMark))
		}); e != nil {
			return e
		}
		return inner
	}}
	c, e := d.Dial("tcp", dst)
	if e != nil {
		return nil, e
	}
	if scheme != "https" {
		return c, nil
	}
	name := sni
	if name == "" {
		name, _, _ = net.SplitHostPort(dst)
	}
	tc := tls.Client(c, &tls.Config{MinVersion: tls.VersionTLS12, ServerName: name, InsecureSkipVerify: true, NextProtos: []string{"http/1.1"}})
	_ = tc.SetDeadline(time.Now().Add(15 * time.Second))
	if e := tc.Handshake(); e != nil {
		c.Close()
		return nil, e
	}
	_ = tc.SetDeadline(time.Time{})
	return tc, nil
}

func serveWebSocket(sid int64, sourceJSON, dst, sni, scheme string, req *http.Request, reader *bufio.Reader, stream net.Conn) {
	start := time.Now()
	fail := func(message string) {
		singleEvent(sid, dst, "upstream_error", message)
		_, _ = io.WriteString(stream, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
	}
	up, e := dialUpstream(dst, sni, scheme)
	if e != nil {
		fail(e.Error())
		return
	}
	defer up.Close()
	req.RequestURI = ""
	req.URL.Scheme = scheme
	if req.URL.Host == "" {
		req.URL.Host = req.Host
	}
	req.Header.Del("Proxy-Connection")
	req.Close = false
	if e = req.Write(up); e != nil {
		fail(e.Error())
		return
	}
	upReader := bufio.NewReader(up)
	resp, e := http.ReadResponse(upReader, req)
	if e != nil {
		fail(e.Error())
		return
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		sb := &capbuf{}
		resp.Close = true
		resp.Body = &readCloser{Reader: io.TeeReader(resp.Body, sb), Closer: resp.Body}
		_ = resp.Write(stream)
		resp.Body.Close()
		singleRecord(sid, sourceJSON, dst, sni, scheme, req, resp.StatusCode, resp.Header, &capbuf{}, sb, "", start)
		return
	}
	var out strings.Builder
	fmt.Fprintf(&out, "HTTP/1.1 %s\r\n", resp.Status)
	_ = resp.Header.Write(&out)
	out.WriteString("\r\n")
	if _, e = io.WriteString(stream, out.String()); e != nil {
		return
	}
	singleRecord(sid, sourceJSON, dst, sni, scheme, req, resp.StatusCode, resp.Header, &capbuf{}, &capbuf{}, "", start)
	meta := wsMeta{source: sourceJSON, dst: dst, sni: sni, scheme: scheme, host: req.Host, path: req.URL.RequestURI()}
	_ = stream.SetDeadline(time.Time{})
	_ = up.SetDeadline(time.Time{})

	var once sync.Once
	stop := func() {
		once.Do(func() {
			_ = stream.SetDeadline(time.Now())
			_ = up.SetDeadline(time.Now())
		})
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); wsPump(sid, meta, reader, up, true, stop) }()
	go func() { defer wg.Done(); wsPump(sid, meta, upReader, stream, false, stop) }()
	wg.Wait()
}

type wsFrame struct {
	fin     bool
	op      byte
	payload []byte
}

func readWSFrame(r *bufio.Reader) (wsFrame, error) {
	var h [2]byte
	if _, e := io.ReadFull(r, h[:]); e != nil {
		return wsFrame{}, e
	}
	f := wsFrame{fin: h[0]&0x80 != 0, op: h[0] & 0x0f}
	if h[0]&0x70 != 0 {
		return f, fmt.Errorf("reserved bits set")
	}
	n := int64(h[1] & 0x7f)
	switch n {
	case 126:
		var b [2]byte
		if _, e := io.ReadFull(r, b[:]); e != nil {
			return f, e
		}
		n = int64(b[0])<<8 | int64(b[1])
	case 127:
		var b [8]byte
		if _, e := io.ReadFull(r, b[:]); e != nil {
			return f, e
		}
		n = 0
		for _, v := range b {
			n = n<<8 | int64(v)
		}
	}
	if n < 0 || n > wsFrameLimit {
		return f, fmt.Errorf("frame too large: %d", n)
	}
	var mask [4]byte
	if h[1]&0x80 != 0 {
		if _, e := io.ReadFull(r, mask[:]); e != nil {
			return f, e
		}
	}
	f.payload = make([]byte, n)
	if _, e := io.ReadFull(r, f.payload); e != nil {
		return f, e
	}
	if h[1]&0x80 != 0 {
		for i := range f.payload {
			f.payload[i] ^= mask[i%4]
		}
	}
	return f, nil
}

func writeWSFrame(w io.Writer, fin bool, op byte, payload []byte, mask bool) error {
	head := make([]byte, 0, 14)
	b := op & 0x0f
	if fin {
		b |= 0x80
	}
	head = append(head, b)
	n := len(payload)
	flag := byte(0)
	if mask {
		flag = 0x80
	}
	switch {
	case n < 126:
		head = append(head, flag|byte(n))
	case n <= 0xffff:
		head = append(head, flag|126, byte(n>>8), byte(n))
	default:
		head = append(head, flag|127, 0, 0, 0, 0, byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
	}
	if mask {
		var key [4]byte
		if _, e := rand.Read(key[:]); e != nil {
			return e
		}
		head = append(head, key[:]...)
		masked := make([]byte, n)
		for i := range payload {
			masked[i] = payload[i] ^ key[i%4]
		}
		if _, e := w.Write(head); e != nil {
			return e
		}
		_, e := w.Write(masked)
		return e
	}
	if _, e := w.Write(head); e != nil {
		return e
	}
	_, e := w.Write(payload)
	return e
}

func wsPump(sid int64, m wsMeta, in *bufio.Reader, out io.Writer, toUpstream bool, stop func()) {
	defer stop()
	method := "WS↓"
	if toUpstream {
		method = "WS↑"
	}
	var acc []byte
	total := 0
	for {
		f, e := readWSFrame(in)
		if e != nil {
			return
		}
		if e = writeWSFrame(out, f.fin, f.op, f.payload, toUpstream); e != nil {
			return
		}
		switch f.op {
		case 0x8:
			return
		case 0x9, 0xa:
			continue
		case 0x0:
		case 0x1, 0x2:
			acc = nil
			total = 0
		default:
			return
		}
		total += len(f.payload)
		if room := bodyLimit - len(acc); room > 0 {
			if len(f.payload) <= room {
				acc = append(acc, f.payload...)
			} else {
				acc = append(acc, f.payload[:room]...)
			}
		}
		if f.fin {
			recordWS(sid, m, method, acc, total)
			acc = nil
			total = 0
		}
	}
}

func recordWS(sid int64, m wsMeta, method string, body []byte, total int) {
	tx, e := db.Begin()
	if e != nil {
		logger.Printf("ws record: %v", e)
		return
	}
	defer tx.Rollback()
	var reqBody, respBody []byte
	reqSize, respSize := 0, 0
	if method == "WS↑" {
		reqBody, reqSize = body, total
	} else {
		respBody, respSize = body, total
	}
	r, e := tx.Exec(`INSERT INTO exchanges(session_id,ts_ms,dst,sni,scheme,method,host,path,req_headers,req_body,req_size,resp_status,resp_headers,resp_body,resp_size,error,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, sid, time.Now().UnixMilli(), m.dst, m.sni, m.scheme, method, m.host, m.path, "", reqBody, reqSize, 101, "", respBody, respSize, "", 0)
	if e != nil {
		logger.Printf("ws record: %v", e)
		return
	}
	id, e := r.LastInsertId()
	if e != nil {
		return
	}
	if _, e = tx.Exec(`INSERT INTO tpc_origin_meta(exchange_id,origin_json) VALUES(?,?)`, id, m.source); e != nil {
		logger.Printf("ws origin record: %v", e)
		return
	}
	if e = tx.Commit(); e != nil {
		logger.Printf("ws record commit: %v", e)
	}
}
