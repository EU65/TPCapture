package main

import (
	"context"
	"crypto/tls"
	"database/sql"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"appcapture/h3"
	"appcapture/origin"
	"golang.org/x/sys/unix"
)

var singleRouteMark uint32
var singleSessions = struct {
	sync.Mutex
	ids map[int64]int64
}{ids: make(map[int64]int64)}

func singleSession(uid int64, port int) (int64, error) {
	singleSessions.Lock()
	defer singleSessions.Unlock()
	if id, ok := singleSessions.ids[uid]; ok {
		return id, nil
	}
	name := fmt.Sprintf("uid:%d", uid)
	if uid < 0 {
		name = "uid:unknown"
	}
	res, e := db.Exec(`INSERT INTO sessions(package,uid,port,started_ms) VALUES(?,?,?,?)`, name, uid, port, time.Now().UnixMilli())
	if e != nil {
		return 0, e
	}
	id, e := res.LastInsertId()
	if e == nil {
		singleSessions.ids[uid] = id
	}
	return id, e
}
func singleEvent(sid int64, dst, typ, msg string) {
	if _, e := db.Exec(`INSERT INTO events(session_id,ts_ms,dst,type,message) VALUES(?,?,?,?,?)`, sid, time.Now().UnixMilli(), dst, typ, msg); e != nil {
		logger.Printf("event: %v", e)
	}
}
func singleRecord(sid int64, source, dst, sni, scheme string, req *http.Request, status int, rh http.Header, rb, sb *capbuf, errstr string, start time.Time) {
	tx, e := db.Begin()
	if e != nil {
		logger.Printf("record: %v", e)
		return
	}
	defer tx.Rollback()
	r, e := tx.Exec(`INSERT INTO exchanges(session_id,ts_ms,dst,sni,scheme,method,host,path,req_headers,req_body,req_size,resp_status,resp_headers,resp_body,resp_size,error,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, sid, start.UnixMilli(), dst, sni, scheme, req.Method, req.Host, req.URL.RequestURI(), head(req.Header), rb.b, rb.total, status, head(rh), sb.b, sb.total, errstr, time.Since(start).Milliseconds())
	if e != nil {
		logger.Printf("record: %v", e)
		return
	}
	id, e := r.LastInsertId()
	if e != nil {
		logger.Printf("record id: %v", e)
		return
	}
	if _, e = tx.Exec(`INSERT INTO tpc_origin_meta(exchange_id,origin_json) VALUES(?,?)`, id, source); e != nil {
		logger.Printf("origin record: %v", e)
		return
	}
	if e = tx.Commit(); e != nil {
		logger.Printf("record commit: %v", e)
	}
}
func transparentTCP(ctx context.Context, network, address string, routeMark uint32) (net.Listener, error) {
	lc := net.ListenConfig{Control: func(network, address string, c syscall.RawConn) error {
		var inner error
		e := c.Control(func(fd uintptr) {
			oldMark, err := unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK)
			if err != nil {
				inner = err
				return
			}
			if inner = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, int((uint32(oldMark)|uint32(bypassMark))&^routeMark)); inner != nil {
				return
			}
			if network == "tcp6" {
				if inner = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_V6ONLY, 1); inner == nil {
					inner = unix.SetsockoptInt(int(fd), unix.SOL_IPV6, unix.IPV6_TRANSPARENT, 1)
				}
			} else {
				inner = unix.SetsockoptInt(int(fd), unix.SOL_IP, unix.IP_TRANSPARENT, 1)
			}
		})
		if e != nil {
			return e
		}
		return inner
	}}
	return lc.Listen(ctx, network, address)
}
func runSingle(dir string, port int, mode string, routeMark uint32) error {
	singleRouteMark = routeMark
	if port < 1024 || port > 65535 {
		return fmt.Errorf("invalid listen port")
	}
	if e := os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	lock, e := acquireDBLock(dir, false)
	if e != nil {
		return e
	}
	defer lock.Close()
	switch mode {
	case "emudhra":
		if e = loadEMudhra(); e != nil {
			return e
		}
	case "self":
		c, e := ensureLocalCA(filepath.Join(dir, "ca"))
		if e != nil {
			return e
		}
		ca, caKey, caChain = c.cert, c.key, [][]byte{c.cert.Raw}
	default:
		return fmt.Errorf("unknown CA mode")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	v4, e := transparentTCP(ctx, "tcp4", fmt.Sprintf("127.0.0.1:%d", port), routeMark)
	if e != nil {
		return e
	}
	defer v4.Close()
	v6, e := transparentTCP(ctx, "tcp6", fmt.Sprintf("[::1]:%d", port), routeMark)
	if e != nil {
		return e
	}
	defer v6.Close()
	db, e = sql.Open("sqlite", filepath.Join(dir, "capture.sqlite3"))
	if e != nil {
		return e
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	if _, e = db.Exec(`PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, package TEXT, uid INTEGER, port INTEGER, started_ms INTEGER); CREATE TABLE IF NOT EXISTS exchanges(id INTEGER PRIMARY KEY,session_id INTEGER,ts_ms INTEGER,dst TEXT,sni TEXT,scheme TEXT,method TEXT,host TEXT,path TEXT,req_headers TEXT,req_body BLOB,req_size INTEGER,resp_status INTEGER,resp_headers TEXT,resp_body BLOB,resp_size INTEGER,error TEXT,duration_ms INTEGER); CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,session_id INTEGER,ts_ms INTEGER,dst TEXT,type TEXT,message TEXT); CREATE TABLE IF NOT EXISTS tpc_origin_meta(exchange_id INTEGER PRIMARY KEY,origin_json TEXT); CREATE TABLE IF NOT EXISTS quic_exchange_meta(exchange_id INTEGER PRIMARY KEY,conn_id TEXT,stream_id INTEGER,quic_version TEXT,alpn TEXT,client_addr TEXT);`); e != nil {
		return e
	}
	runDir = dir
	resolve := func(network, client, destination string) (int64, string, int64, error) {
		src, e1 := netip.ParseAddrPort(client)
		dst, e2 := netip.ParseAddrPort(destination)
		var source origin.Result
		uid := int64(-1)
		if e1 == nil && e2 == nil {
			source = origin.Lookup(network, src, dst)
			if source.UID != nil {
				uid = int64(*source.UID)
			}
		} else {
			source.Note = "could not parse socket addresses"
		}
		b, _ := json.Marshal(source)
		sid, e := singleSession(uid, port)
		return sid, string(b), uid, e
	}
	closeH3, e := h3.Start(ctx, h3.Config{Port: port, DB: db, Bypass: uint32(bypassMark), Route: routeMark, Certificate: func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
		if hello.ServerName == "" {
			return nil, fmt.Errorf("QUIC ClientHello without SNI is unsupported")
		}
		return leaf(hello.ServerName)
	}, Resolve: func(src, dst string) (int64, string, error) {
		sid, source, _, e := resolve("udp", src, dst)
		return sid, source, e
	}})
	if e != nil {
		return e
	}
	defer closeH3()
	var wg, active sync.WaitGroup
	var clients sync.Map
	accept := func(l net.Listener) {
		defer wg.Done()
		for {
			c, e := l.Accept()
			if e != nil {
				if ctx.Err() != nil {
					return
				}
				logger.Printf("accept: %v", e)
				continue
			}
			active.Add(1)
			clients.Store(c, struct{}{})
			go func() {
				defer active.Done()
				defer clients.Delete(c)
				tcp, ok := c.(*net.TCPConn)
				if !ok {
					c.Close()
					return
				}
				sid, sourceJSON, uid, e := resolve("tcp", tcp.RemoteAddr().String(), tcp.LocalAddr().String())
				if e != nil {
					logger.Printf("session: %v", e)
					tcp.Close()
					return
				}
				serveSingleConn(tcp, sid, sourceJSON, uid)
			}()
		}
	}
	wg.Add(2)
	go accept(v4)
	go accept(v6)
	logger.Printf("READY mode=tproxy-single protocols=tcp,udp port=%d", port)
	<-ctx.Done()
	v4.Close()
	v6.Close()
	wg.Wait()
	clients.Range(func(key, value any) bool {
		if c, ok := key.(net.Conn); ok {
			c.Close()
		}
		return true
	})
	active.Wait()
	return nil
}
