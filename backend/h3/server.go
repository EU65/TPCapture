// Package h3 provides an experimental transparent HTTP/3 receiver.
package h3

import (
	"context"
	"crypto/tls"
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type Config struct {
	Port          int
	DB            *sql.DB
	Bypass, Route uint32
	Certificate   func(*tls.ClientHelloInfo) (*tls.Certificate, error)
	Resolve       func(string, string) (int64, string, error)
}
type server struct {
	cfg      Config
	sequence atomic.Uint64
}
type stateKey struct{}
type connState struct {
	owner                           *server
	flow                            *flowAddr
	sni, alpn, version, tag, origin string
	sid                             int64
	up                              *http3.Transport
	udp                             net.PacketConn
	transport                       *quic.Transport
	mu                              sync.Mutex
	initialized, closed             bool
	err                             error
}

func (s *connState) init(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return fmt.Errorf("connection closed")
	}
	if s.initialized {
		return s.err
	}
	s.initialized = true
	network, local := "udp4", "0.0.0.0:0"
	if s.flow.original.IP.To4() == nil {
		network, local = "udp6", "[::]:0"
	}
	lc := net.ListenConfig{Control: func(_, _ string, c syscall.RawConn) error {
		var inner error
		e := c.Control(func(fd uintptr) { inner = markSocket(fd, s.owner.cfg.Bypass, s.owner.cfg.Route) })
		if e != nil {
			return e
		}
		return inner
	}}
	s.udp, s.err = lc.ListenPacket(ctx, network, local)
	if s.err != nil {
		return s.err
	}
	s.transport = &quic.Transport{Conn: s.udp}
	server := s.sni
	if server == "" {
		server = s.flow.original.IP.String()
	}
	s.up = &http3.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS13, ServerName: server}, DisableCompression: true, Dial: func(ctx context.Context, _ string, t *tls.Config, c *quic.Config) (*quic.Conn, error) {
		return s.transport.DialEarly(ctx, s.flow.original, t, c)
	}}
	return nil
}
func (s *connState) close() {
	s.mu.Lock()
	s.closed = true
	up, tr, udp := s.up, s.transport, s.udp
	s.mu.Unlock()
	if up != nil {
		_ = up.Close()
	}
	if tr != nil {
		_ = tr.Close()
	}
	if udp != nil {
		_ = udp.Close()
	}
}

type captureBuffer struct {
	mu    sync.Mutex
	b     []byte
	total int64
}

func (c *captureBuffer) Write(p []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.total += int64(len(p))
	n := (1 << 20) - len(c.b)
	if n > len(p) {
		n = len(p)
	}
	if n > 0 {
		c.b = append(c.b, p[:n]...)
	}
	return len(p), nil
}
func (c *captureBuffer) snapshot() ([]byte, int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]byte(nil), c.b...), c.total
}

type readCloser struct {
	io.Reader
	io.Closer
}

func headers(h http.Header) string { b, _ := json.Marshal(h); return string(b) }
func (s *server) event(c *connState, kind, msg string) {
	if _, e := s.cfg.DB.Exec(`INSERT INTO events(session_id,ts_ms,dst,type,message) VALUES(?,?,?,?,?)`, c.sid, time.Now().UnixMilli(), c.flow.original.String(), kind, msg); e != nil {
		log.Printf("h3 event: %v", e)
	}
}
func (s *server) record(c *connState, streamID int64, r *http.Request, status int, rh http.Header, reqBody, respBody *captureBuffer, errText string, start time.Time) {
	rb, rs := reqBody.snapshot()
	sb, ss := respBody.snapshot()
	tx, e := s.cfg.DB.Begin()
	if e != nil {
		log.Printf("h3 begin: %v", e)
		return
	}
	defer tx.Rollback()
	res, e := tx.Exec(`INSERT INTO exchanges(session_id,ts_ms,dst,sni,scheme,method,host,path,req_headers,req_body,req_size,resp_status,resp_headers,resp_body,resp_size,error,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, c.sid, start.UnixMilli(), c.flow.original.String(), c.sni, "https", r.Method, r.Host, r.URL.RequestURI(), headers(r.Header), rb, rs, status, headers(rh), sb, ss, errText, time.Since(start).Milliseconds())
	if e != nil {
		log.Printf("h3 record: %v", e)
		return
	}
	id, e := res.LastInsertId()
	if e != nil {
		return
	}
	if _, e = tx.Exec(`INSERT INTO quic_exchange_meta(exchange_id,conn_id,stream_id,quic_version,alpn,client_addr) VALUES(?,?,?,?,?,?)`, id, c.tag, streamID, c.version, c.alpn, c.flow.client.String()); e != nil {
		log.Printf("h3 metadata: %v", e)
		return
	}
	if _, e = tx.Exec(`INSERT INTO tpc_origin_meta(exchange_id,origin_json) VALUES(?,?)`, id, c.origin); e != nil {
		log.Printf("h3 origin: %v", e)
		return
	}
	if e = tx.Commit(); e != nil {
		log.Printf("h3 commit: %v", e)
	}
}
func (s *server) handle(w http.ResponseWriter, r *http.Request) {
	c, _ := r.Context().Value(stateKey{}).(*connState)
	if c == nil || c.flow == nil || c.sid < 1 {
		http.Error(w, "missing original destination or session", 502)
		return
	}
	start := time.Now()
	rb, sb := &captureBuffer{}, &captureBuffer{}
	streamID := int64(-1)
	if stream, ok := r.Body.(interface{ StreamID() quic.StreamID }); ok {
		streamID = int64(stream.StreamID())
	}
	if r.Method == "CONNECT" {
		s.event(c, "quic_unsupported", "CONNECT unsupported")
		http.Error(w, "unsupported", 501)
		s.record(c, streamID, r, 501, nil, rb, sb, "CONNECT unsupported", start)
		return
	}
	if e := c.init(r.Context()); e != nil {
		s.event(c, "quic_upstream_error", e.Error())
		http.Error(w, "upstream unavailable", 502)
		s.record(c, streamID, r, 0, nil, rb, sb, e.Error(), start)
		return
	}
	req := r.Clone(r.Context())
	req.RequestURI = ""
	req.URL.Scheme = "https"
	req.URL.Host = r.Host
	req.Header = r.Header.Clone()
	req.Body = &readCloser{Reader: io.TeeReader(r.Body, rb), Closer: r.Body}
	resp, e := c.up.RoundTrip(req)
	if e != nil {
		s.event(c, "quic_upstream_error", e.Error())
		http.Error(w, "upstream unavailable", 502)
		s.record(c, streamID, r, 0, nil, rb, sb, e.Error(), start)
		return
	}
	defer resp.Body.Close()
	for k, values := range resp.Header {
		for _, v := range values {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, e = io.Copy(w, io.TeeReader(resp.Body, sb))
	msg := ""
	if e != nil {
		msg = e.Error()
	}
	s.record(c, streamID, r, resp.StatusCode, resp.Header, rb, sb, msg, start)
}
func Start(ctx context.Context, cfg Config) (func(), error) {
	s := &server{cfg: cfg}
	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS13, NextProtos: []string{http3.NextProtoH3}, SessionTicketsDisabled: true, GetCertificate: cfg.Certificate}
	server := &http3.Server{Handler: http.HandlerFunc(s.handle), ConnContext: func(ctx context.Context, c *quic.Conn) context.Context {
		f, _ := c.RemoteAddr().(*flowAddr)
		cs := &connState{owner: s, flow: f, tag: fmt.Sprintf("%d", s.sequence.Add(1))}
		if f != nil {
			var e error
			cs.sid, cs.origin, e = cfg.Resolve(f.client.String(), f.original.String())
			if e != nil {
				log.Printf("h3 source: %v", e)
			}
		}
		st := c.ConnectionState()
		cs.sni, cs.alpn, cs.version = st.TLS.ServerName, st.TLS.NegotiatedProtocol, fmt.Sprint(st.Version)
		go func() { <-c.Context().Done(); cs.close() }()
		return context.WithValue(ctx, stateKey{}, cs)
	}}
	type running struct {
		packet    *packetAdapter
		transport *quic.Transport
		listener  *quic.Listener
	}
	var list []running
	cleanup := func() {
		_ = server.Close()
		for _, v := range list {
			_ = v.listener.Close()
			_ = v.transport.Close()
			_ = v.packet.Close()
		}
	}
	for _, spec := range []struct{ network, host string }{{"udp4", "127.0.0.1"}, {"udp6", "::1"}} {
		u, e := listenTransparent(ctx, spec.network, net.JoinHostPort(spec.host, strconv.Itoa(cfg.Port)), cfg.Bypass, cfg.Route, true)
		if e != nil {
			cleanup()
			return nil, e
		}
		packet := &packetAdapter{in: u, network: spec.network, bypass: cfg.Bypass, route: cfg.Route, reply: map[string]*replySocket{}}
		tr := &quic.Transport{Conn: packet}
		listener, e := tr.Listen(tlsCfg, &quic.Config{MaxIdleTimeout: 45 * time.Second})
		if e != nil {
			packet.Close()
			cleanup()
			return nil, e
		}
		list = append(list, running{packet, tr, listener})
		go func(l *quic.Listener) {
			for {
				c, e := l.Accept(ctx)
				if e != nil {
					if ctx.Err() != nil {
						return
					}
					if strings.Contains(e.Error(), "closed") {
						return
					}
					log.Printf("h3 accept: %v", e)
					continue
				}
				go func() {
					if e := server.ServeQUICConn(c); e != nil && !strings.Contains(e.Error(), "closed") {
						log.Printf("h3 conn: %v", e)
					}
					_ = c.CloseWithError(0, "")
				}()
			}
		}(listener)
	}
	return cleanup, nil
}
