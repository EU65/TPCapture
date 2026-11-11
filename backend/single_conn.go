package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"syscall"
	"time"
)

func serveSingleConn(c *net.TCPConn, sid int64, sourceJSON string, uid int64) {
	defer c.Close()
	dst := c.LocalAddr().String()
	var e error
	_ = c.SetDeadline(time.Now().Add(5 * time.Minute))
	br := bufio.NewReaderSize(c, helloLimit+5)
	first, e := br.Peek(1)
	if e != nil {
		return
	}
	var stream net.Conn = &bufferedConn{Conn: c, r: br}
	scheme := "http"
	sni := ""
	if first[0] == 0x16 {
		scheme = "https"
		host, _, _ := net.SplitHostPort(dst)
		if hello, he := peekHello(br); he == nil {
			if name, ok := parseSNI(hello); ok {
				sni = name
			}
		}
		if sni != "" && loadRules(runDir, false).passthrough(uid, sni) {
			pipeTransparent(c, br, dst, sni)
			return
		}
		tc := tls.Server(stream, &tls.Config{MinVersion: tls.VersionTLS12, NextProtos: []string{"http/1.1"}, GetCertificate: func(chi *tls.ClientHelloInfo) (*tls.Certificate, error) {
			if chi.ServerName != "" {
				sni = chi.ServerName
			}
			if sni == "" {
				sni = host
			}
			return leaf(sni)
		}})
		if e = tc.Handshake(); e != nil {
			singleEvent(sid, dst, "tls_error", e.Error())
			return
		}
		stream = tc
	}
	reader := bufio.NewReader(stream)
	for {
		req, e := http.ReadRequest(reader)
		if e != nil {
			if e != io.EOF {
				singleEvent(sid, dst, "http_read_error", e.Error())
			}
			return
		}
		start := time.Now()
		if isWebSocket(req) {
			req.Body.Close()
			serveWebSocket(sid, sourceJSON, dst, sni, scheme, req, reader, stream)
			return
		}
		if req.Method == "CONNECT" || req.Header.Get("Upgrade") != "" {
			singleEvent(sid, dst, "unsupported", "CONNECT/Upgrade not supported")
			_, _ = io.WriteString(stream, "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
			req.Body.Close()
			return
		}
		host := req.Host
		if host == "" {
			host = sni
		}
		plan := loadRules(runDir, false).evaluate(uid, host, req.Method, scheme+"://"+host+req.URL.RequestURI())
		matched := plan.matched()
		if plan.block != nil {
			serveBlocked(sid, sourceJSON, dst, sni, scheme, req, stream, plan.block, matched, start)
			req.Body.Close()
			return
		}
		rb, sb := &capbuf{}, &capbuf{}
		req.RequestURI = ""
		req.URL.Scheme = scheme
		req.URL.Host = req.Host
		if req.URL.Host == "" {
			req.URL.Host = dst
		}
		req.Header.Del("Proxy-Connection")
		req.Close = true
		plan.rewriteRequest(req)
		if plan.respBody {
			req.Header.Del("Accept-Encoding")
		}
		if plan.reqBody {
			if body, ok := readEditable(req.Body); ok {
				req.Body.Close()
				if plain, ok2 := decodeEncoded(body, req.Header.Get("Content-Encoding")); ok2 {
					body = plain
				}
				req.Header.Del("Content-Encoding")
				body = replaceBody(body, plan.requestEdits())
				req.Body = io.NopCloser(bytes.NewReader(body))
				req.ContentLength = int64(len(body))
			}
		}
		req.Body = &readCloser{Reader: io.TeeReader(req.Body, rb), Closer: req.Body}
		serverName := sni
		if serverName == "" {
			serverName, _, _ = net.SplitHostPort(dst)
		}
		tr := &http.Transport{DisableKeepAlives: true, ForceAttemptHTTP2: false, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: serverName}, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := &net.Dialer{Timeout: 15 * time.Second, Control: func(network, address string, raw syscall.RawConn) error {
				var sockErr error
				err := raw.Control(func(fd uintptr) {
					currentMark, err := syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK)
					if err != nil {
						sockErr = err
						return
					}
					sockErr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK, (currentMark|int(bypassMark))&^int(singleRouteMark))
				})
				if err != nil {
					return err
				}
				return sockErr
			}}
			return d.DialContext(ctx, "tcp", dst)
		}, TLSHandshakeTimeout: 15 * time.Second, ResponseHeaderTimeout: 30 * time.Second}
		resp, e := tr.RoundTrip(req)
		if e != nil {
			if shouldRecord(matched) {
				singleRecord(sid, sourceJSON, dst, sni, scheme, req, 0, nil, rb, sb, e.Error(), start)
			}
			singleEvent(sid, dst, "upstream_error", e.Error())
			_, _ = io.WriteString(stream, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
			tr.CloseIdleConnections()
			return
		}
		resp.Close = true
		plan.rewriteResponse(resp)
		if plan.respBody {
			if body, ok := readEditable(resp.Body); ok {
				resp.Body.Close()
				if plain, ok2 := decodeEncoded(body, resp.Header.Get("Content-Encoding")); ok2 {
					body = plain
				}
				body = replaceBody(body, plan.responseEdits())
				resp.Body = io.NopCloser(bytes.NewReader(body))
				resp.ContentLength = int64(len(body))
				resp.Header.Del("Content-Encoding")
				resp.Header.Del("Content-MD5")
				resp.Header.Del("Etag")
				resp.Header.Set("Content-Length", strconv.Itoa(len(body)))
				resp.TransferEncoding = nil
			}
		}
		resp.Body = &readCloser{Reader: io.TeeReader(resp.Body, sb), Closer: resp.Body}
		e = resp.Write(stream)
		resp.Body.Close()
		tr.CloseIdleConnections()
		es := ""
		if e != nil {
			es = e.Error()
		}
		if shouldRecord(matched) {
			singleRecord(sid, sourceJSON, dst, sni, scheme, req, resp.StatusCode, resp.Header, rb, sb, es, start)
		}
		return
	}
}

func readEditable(body io.Reader) ([]byte, bool) {
	data, e := io.ReadAll(io.LimitReader(body, rewriteBodyLimit+1))
	if e != nil || len(data) > rewriteBodyLimit {
		return nil, false
	}
	return data, true
}

func serveBlocked(sid int64, sourceJSON, dst, sni, scheme string, req *http.Request, stream net.Conn, block *blockAction, matched bool, start time.Time) {
	status := block.Status
	if status < 200 || status > 599 {
		status = http.StatusForbidden
	}
	body := []byte(block.Body)
	contentType := block.Type
	if contentType == "" {
		contentType = "text/plain; charset=utf-8"
	}
	resp := &http.Response{StatusCode: status, Status: fmt.Sprintf("%d %s", status, http.StatusText(status)), Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1, Header: http.Header{}, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body)), Close: true, Request: req}
	resp.Header.Set("Content-Type", contentType)
	resp.Header.Set("Content-Length", strconv.Itoa(len(body)))
	_ = stream.SetWriteDeadline(time.Now().Add(15 * time.Second))
	_ = resp.Write(stream)
	if shouldRecord(matched) {
		singleRecord(sid, sourceJSON, dst, sni, scheme, req, status, resp.Header, &capbuf{}, &capbuf{b: body, total: int64(len(body))}, "", start)
	}
}
