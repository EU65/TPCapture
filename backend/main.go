// AppCapture experimental single-session IPv4 transparent HTTP/1 proxy.
package main

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"database/sql"
	_ "embed"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"
	"unsafe"

	_ "modernc.org/sqlite"
)

const bodyLimit = 1 << 20

//go:embed VERSION
var backendVersion string
var db *sql.DB
var ca *x509.Certificate
var caKey *ecdsa.PrivateKey
var caChain [][]byte

// PR #8500, fixed merge commit 169234499088fc2c414644a67efaed3d64ae2e3b.
// This embeds publicly disclosed, compromised CA material; lab use only.
//
//go:embed emudhra_bundle.pem
var emudhraBundle []byte

var certMu sync.Mutex
var certs = map[string]*tls.Certificate{}
var session int64
var uid int
var bypassMark uint
var pkg string
var logger = log.New(os.Stderr, "", log.LstdFlags)

type targetFlags []string

func (t *targetFlags) String() string     { return strings.Join(*t, ",") }
func (t *targetFlags) Set(v string) error { *t = append(*t, v); return nil }

func fatal(e error) {
	if e != nil {
		logger.Fatal(e)
	}
}
func caInit(dir string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	certPath := filepath.Join(dir, "ca.pem")
	keyPath := filepath.Join(dir, "ca-key.pem")
	_, ce := os.Stat(certPath)
	_, ke := os.Stat(keyPath)
	if ce == nil && ke == nil {
		return loadCA(certPath, keyPath)
	}
	if !os.IsNotExist(ce) || !os.IsNotExist(ke) {
		return errors.New("CA files incomplete or inaccessible; refusing to overwrite")
	}
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return err
	}
	c := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "AppCapture local CA"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(2, 0, 0), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign}
	der, err := x509.CreateCertificate(rand.Reader, c, c, &k.PublicKey, k)
	if err != nil {
		return err
	}
	kd, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return err
	}
	if err = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: kd}), 0600); err != nil {
		return err
	}
	if err = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); err != nil {
		os.Remove(keyPath)
		return err
	}
	return loadCA(certPath, keyPath)
}
func loadCA(cp, kp string) error {
	cb, e := os.ReadFile(cp)
	if e != nil {
		return e
	}
	kb, e := os.ReadFile(kp)
	if e != nil {
		return e
	}
	c, _ := pem.Decode(cb)
	k, _ := pem.Decode(kb)
	if c == nil || k == nil {
		return errors.New("invalid CA PEM")
	}
	ca, e = x509.ParseCertificate(c.Bytes)
	if e != nil {
		return e
	}
	v, e := x509.ParsePKCS8PrivateKey(k.Bytes)
	if e != nil {
		return e
	}
	var ok bool
	caKey, ok = v.(*ecdsa.PrivateKey)
	if !ok {
		return errors.New("CA must be ECDSA")
	}
	if !ca.IsCA || !ca.PublicKey.(*ecdsa.PublicKey).Equal(&caKey.PublicKey) {
		return errors.New("CA and key mismatch")
	}
	caChain = [][]byte{ca.Raw}
	return nil
}

func loadEMudhra() error {
	var certsParsed []*x509.Certificate
	var key *ecdsa.PrivateKey
	rest := emudhraBundle
	for len(rest) > 0 {
		block, tail := pem.Decode(rest)
		if block == nil {
			break
		}
		rest = tail
		switch block.Type {
		case "CERTIFICATE":
			c, e := x509.ParseCertificate(block.Bytes)
			if e != nil {
				return e
			}
			certsParsed = append(certsParsed, c)
		case "PRIVATE KEY":
			k, e := x509.ParsePKCS8PrivateKey(block.Bytes)
			if e != nil {
				return e
			}
			var ok bool
			key, ok = k.(*ecdsa.PrivateKey)
			if !ok {
				return errors.New("not an EC key")
			}
		}
	}
	if len(certsParsed) < 2 || key == nil {
		return errors.New("incomplete eMudhra chain")
	}
	pub, ok := certsParsed[0].PublicKey.(*ecdsa.PublicKey)
	if !ok || !certsParsed[0].IsCA || !pub.Equal(&key.PublicKey) {
		return errors.New("eMudhra signer mismatch")
	}
	ca = certsParsed[0]
	caKey = key
	caChain = nil
	for _, c := range certsParsed {
		caChain = append(caChain, c.Raw)
	}
	certMu.Lock()
	certs = map[string]*tls.Certificate{}
	certMu.Unlock()
	return nil
}
func leaf(name string) (*tls.Certificate, error) {
	certMu.Lock()
	defer certMu.Unlock()
	if c := certs[name]; c != nil {
		return c, nil
	}
	k, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		return nil, e
	}
	sn, e := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if e != nil {
		return nil, e
	}
	c := &x509.Certificate{SerialNumber: sn, Subject: pkix.Name{CommonName: name}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	if ip := net.ParseIP(name); ip != nil {
		c.IPAddresses = []net.IP{ip}
	} else {
		c.DNSNames = []string{name}
	}
	der, e := x509.CreateCertificate(rand.Reader, c, ca, &k.PublicKey, caKey)
	if e != nil {
		return nil, e
	}
	chain := make([][]byte, 0, len(caChain)+1)
	chain = append(chain, der)
	chain = append(chain, caChain...)
	v := &tls.Certificate{Certificate: chain, PrivateKey: k}
	if len(certs) > 1024 {
		certs = map[string]*tls.Certificate{}
	}
	certs[name] = v
	return v, nil
}
func original(c *net.TCPConn) (string, error) {
	raw, e := c.SyscallConn()
	if e != nil {
		return "", e
	}
	var b [128]byte
	sz := uint32(len(b))
	var errno syscall.Errno
	e = raw.Control(func(fd uintptr) {
		_, _, errno = syscall.Syscall6(syscall.SYS_GETSOCKOPT, fd, syscall.SOL_IP, 80, uintptr(unsafe.Pointer(&b[0])), uintptr(unsafe.Pointer(&sz)), 0)
	})
	if e != nil {
		return "", e
	}
	if errno != 0 {
		return "", errno
	}
	if sz < 8 {
		return "", errors.New("short SO_ORIGINAL_DST")
	}
	port := binary.BigEndian.Uint16(b[2:4])
	return net.JoinHostPort(net.IP(b[4:8]).String(), strconv.Itoa(int(port))), nil
}

type capbuf struct {
	b     []byte
	total int64
}

func (c *capbuf) Write(p []byte) (int, error) {
	c.total += int64(len(p))
	n := bodyLimit - len(c.b)
	if n > len(p) {
		n = len(p)
	}
	if n > 0 {
		c.b = append(c.b, p[:n]...)
	}
	return len(p), nil
}

type readCloser struct {
	io.Reader
	io.Closer
}

func head(h http.Header) string { b, _ := json.Marshal(h); return string(b) }
func record(dst, sni, scheme string, req *http.Request, status int, rh http.Header, rb, sb *capbuf, errstr string, start time.Time) {
	_, e := db.Exec(`INSERT INTO exchanges(session_id,ts_ms,dst,sni,scheme,method,host,path,req_headers,req_body,req_size,resp_status,resp_headers,resp_body,resp_size,error,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, session, start.UnixMilli(), dst, sni, scheme, req.Method, req.Host, req.URL.RequestURI(), head(req.Header), rb.b, rb.total, status, head(rh), sb.b, sb.total, errstr, time.Since(start).Milliseconds())
	if e != nil {
		logger.Printf("database insert: %v", e)
	}
}
func event(dst, typ, msg string) {
	_, e := db.Exec(`INSERT INTO events(session_id,ts_ms,dst,type,message) VALUES(?,?,?,?,?)`, session, time.Now().UnixMilli(), dst, typ, msg)
	if e != nil {
		logger.Printf("database event: %v", e)
	}
}

var originalDst = original

func serveConn(c *net.TCPConn) {
	defer c.Close()
	dst, e := originalDst(c)
	if e != nil {
		logger.Printf("original dst: %v", e)
		return
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Minute))
	br := bufio.NewReader(c)
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
		tc := tls.Server(stream, &tls.Config{MinVersion: tls.VersionTLS12, NextProtos: []string{"http/1.1"}, GetCertificate: func(chi *tls.ClientHelloInfo) (*tls.Certificate, error) {
			sni = chi.ServerName
			if sni == "" {
				sni = host
			}
			return leaf(sni)
		}})
		if e = tc.Handshake(); e != nil {
			event(dst, "tls_error", e.Error())
			return
		}
		stream = tc
	}
	reader := bufio.NewReader(stream)
	for {
		req, e := http.ReadRequest(reader)
		if e != nil {
			if e != io.EOF {
				event(dst, "http_read_error", e.Error())
			}
			return
		}
		start := time.Now()
		if req.Method == "CONNECT" || req.Header.Get("Upgrade") != "" {
			event(dst, "unsupported", "CONNECT/Upgrade not supported")
			_, _ = io.WriteString(stream, "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
			req.Body.Close()
			return
		}
		rb, sb := &capbuf{}, &capbuf{}
		req.Body = &readCloser{Reader: io.TeeReader(req.Body, rb), Closer: req.Body}
		req.RequestURI = ""
		req.URL.Scheme = scheme
		req.URL.Host = req.Host
		if req.URL.Host == "" {
			req.URL.Host = dst
		}
		req.Header.Del("Proxy-Connection")
		req.Close = true
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
					sockErr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_MARK, currentMark|int(bypassMark))
				})
				if err != nil {
					return err
				}
				return sockErr
			}}
			return d.DialContext(ctx, "tcp4", dst)
		}, TLSHandshakeTimeout: 15 * time.Second, ResponseHeaderTimeout: 30 * time.Second}
		resp, e := tr.RoundTrip(req)
		if e != nil {
			record(dst, sni, scheme, req, 0, nil, rb, sb, e.Error(), start)
			event(dst, "upstream_error", e.Error())
			_, _ = io.WriteString(stream, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
			tr.CloseIdleConnections()
			return
		}
		resp.Close = true
		resp.Body = &readCloser{Reader: io.TeeReader(resp.Body, sb), Closer: resp.Body}
		e = resp.Write(stream)
		resp.Body.Close()
		tr.CloseIdleConnections()
		es := ""
		if e != nil {
			es = e.Error()
		}
		record(dst, sni, scheme, req, resp.StatusCode, resp.Header, rb, sb, es, start)
		return
	}
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }
func defaultDataDir() string {
	if d := os.Getenv("APPCAPTURE_DATA_DIR"); d != "" {
		return d
	}
	exe, e := os.Executable()
	if e != nil {
		logger.Fatalf("cannot locate executable: %v", e)
	}
	return filepath.Join(filepath.Dir(exe), "data")
}
func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: appcapture doctor|serve|list|show|events [flags]")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "mcp-token":
		f := flag.NewFlagSet("mcp-token", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		f.Parse(os.Args[2:])
		token, e := mcpToken(*dir)
		fatal(e)
		fmt.Println(token)
	case "mcp-serve":
		f := flag.NewFlagSet("mcp-serve", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		listen := f.String("listen", fmt.Sprintf("127.0.0.1:%d", defaultMCPPort), "loopback address")
		f.Parse(os.Args[2:])
		ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer cancel()
		fatal(serveMCP(ctx, *dir, *listen))
	case "events-json":
		f := flag.NewFlagSet("events-json", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		limit := f.Int("limit", 30, "maximum rows")
		f.Parse(os.Args[2:])
		if *limit < 1 || *limit > 100 {
			logger.Fatal("limit must be 1-100")
		}
		var e error
		var viewLock *os.File
		db, viewLock, e = openForView(*dir)
		fatal(e)
		defer func() {
			db.Close()
			if viewLock != nil {
				viewLock.Close()
			}
		}()
		rows, e := db.Query(`SELECT e.id,e.session_id,e.ts_ms,coalesce(e.dst,''),coalesce(e.type,''),coalesce(e.message,''),coalesce(s.uid,-1),coalesce(s.package,'') FROM events e LEFT JOIN sessions s ON s.id=e.session_id ORDER BY e.id DESC LIMIT ?`, *limit)
		fatal(e)
		defer rows.Close()
		enc := json.NewEncoder(os.Stdout)
		for rows.Next() {
			var id, sid, ts, uid int64
			var dst, kind, msg, pkg string
			fatal(rows.Scan(&id, &sid, &ts, &dst, &kind, &msg, &uid, &pkg))
			fatal(enc.Encode(map[string]any{"id": id, "session_id": sid, "timestamp_ms": ts, "destination": dst, "type": kind, "message": msg, "uid": uid, "target": pkg}))
		}
		fatal(rows.Err())
	case "sessions-json":
		f := flag.NewFlagSet("sessions-json", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		limit := f.Int("limit", 50, "maximum rows")
		f.Parse(os.Args[2:])
		if *limit < 1 || *limit > 100 {
			logger.Fatal("limit must be 1-100")
		}
		var e error
		var viewLock *os.File
		db, viewLock, e = openForView(*dir)
		fatal(e)
		defer func() {
			db.Close()
			if viewLock != nil {
				viewLock.Close()
			}
		}()
		rows, e := db.Query(`SELECT id,coalesce(package,''),uid,port,started_ms FROM sessions ORDER BY id DESC LIMIT ?`, *limit)
		fatal(e)
		defer rows.Close()
		enc := json.NewEncoder(os.Stdout)
		for rows.Next() {
			var id, uid, port, ts int64
			var pkg string
			fatal(rows.Scan(&id, &pkg, &uid, &port, &ts))
			fatal(enc.Encode(map[string]any{"id": id, "target": pkg, "uid": uid, "port": port, "started_ms": ts}))
		}
		fatal(rows.Err())
	case "serve-single":
		f := flag.NewFlagSet("serve-single", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		port := f.Int("port", 19001, "transparent port")
		mode := f.String("ca-mode", "emudhra", "CA mode")
		route := f.Uint("route-mark", 0x20000000, "routing mark")
		f.UintVar(&bypassMark, "mark", 0x80000000, "outbound bypass mark")
		f.Parse(os.Args[2:])
		if *route == 0 || *route > 0xffffffff || bypassMark == 0 || bypassMark > 0xffffffff || (*route&bypassMark) != 0 {
			logger.Fatal("invalid or overlapping routing/bypass marks")
		}
		fatal(runSingle(*dir, *port, *mode, uint32(*route)))
	case "rewrite-get":
		f := flag.NewFlagSet("rewrite-get", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		f.Parse(os.Args[2:])
		data, e := readRuleFile(*dir)
		fatal(e)
		os.Stdout.Write(data)
	case "rewrite-check":
		f := flag.NewFlagSet("rewrite-check", flag.ExitOnError)
		f.String("dir", "", "ignored")
		f.Parse(os.Args[2:])
		raw, e := io.ReadAll(io.LimitReader(os.Stdin, 1<<20))
		fatal(e)
		set, e := parseRuleSet(raw)
		fatal(e)
		fmt.Printf("规则有效：%d 条，模式 %s\n", len(set.Rules), set.Mode)
	case "rewrite-set":
		f := flag.NewFlagSet("rewrite-set", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		f.Parse(os.Args[2:])
		raw, e := io.ReadAll(io.LimitReader(os.Stdin, 1<<20))
		fatal(e)
		fatal(saveRuleFile(*dir, raw))
		fmt.Println("规则已保存，立即生效")
	case "repair-lock":
		f := flag.NewFlagSet("repair-lock", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		script := f.String("script", "", "control script")
		f.Parse(os.Args[2:])
		fatal(repairOperationLock(*dir, *script))
	case "ca-info":
		f := flag.NewFlagSet("ca-info", flag.ExitOnError)
		dir := f.String("dir", filepath.Join(defaultDataDir(), "ca"), "CA directory")
		f.Parse(os.Args[2:])
		c, e := ensureLocalCA(*dir)
		fatal(e)
		fatal(json.NewEncoder(os.Stdout).Encode(c.publicInfo()))
	case "databases":
		f := flag.NewFlagSet("databases", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		f.Parse(os.Args[2:])
		fatal(databases(*dir))
	case "archive-db":
		f := flag.NewFlagSet("archive-db", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		f.Parse(os.Args[2:])
		name, e := archiveCurrent(*dir)
		fatal(e)
		if name == "" {
			fmt.Println("no current database")
		} else {
			fmt.Println("archived=" + name)
		}
	case "version":
		fmt.Println(strings.TrimSpace(backendVersion))
	case "doctor":
		f := flag.NewFlagSet("doctor", flag.ExitOnError)
		rootPath := f.String("root", "/apex/com.android.conscrypt/cacerts/b74d2bd5.0", "Android platform root certificate path")
		f.Parse(os.Args[2:])
		fatal(loadEMudhra())
		b, e := os.ReadFile(*rootPath)
		fatal(e)
		block, _ := pem.Decode(b)
		if block == nil {
			logger.Fatal("root PEM not found")
		}
		root, e := x509.ParseCertificate(block.Bytes)
		fatal(e)
		l, e := leaf("example.test")
		fatal(e)
		end, e := x509.ParseCertificate(l.Certificate[0])
		fatal(e)
		i0, e := x509.ParseCertificate(caChain[0])
		fatal(e)
		i1, e := x509.ParseCertificate(caChain[1])
		fatal(e)
		fatal(end.CheckSignatureFrom(i0))
		fatal(i0.CheckSignatureFrom(i1))
		fatal(i1.CheckSignatureFrom(root))
		fatal(end.VerifyHostname("example.test"))
		fmt.Printf("Certificate signatures match device root subject: %s\n", root.Subject.CommonName)
		fmt.Println("Signature checks only: no Android trust, distrust/revocation, validity, or app pinning check performed.")
	case "ca-init":
		f := flag.NewFlagSet("ca-init", flag.ExitOnError)
		dir := f.String("dir", filepath.Join(defaultDataDir(), "ca"), "CA dir")
		f.Parse(os.Args[2:])
		fatal(caInit(*dir))
		fmt.Println(filepath.Join(*dir, "ca.pem"))
	case "serve":
		f := flag.NewFlagSet("serve", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		port := f.Int("port", 19001, "listen port")
		caMode := f.String("ca-mode", "emudhra", "emudhra or self")
		f.UintVar(&bypassMark, "mark", 0x80000000, "upstream bypass mark")
		f.IntVar(&uid, "uid", -1, "target UID")
		f.StringVar(&pkg, "package", "", "package name")
		f.Parse(os.Args[2:])
		if uid < 0 || pkg == "" {
			logger.Fatal("uid and package required")
		}
		fatal(os.MkdirAll(*dir, 0700))
		sessionLock, lockErr := acquireDBLock(*dir, false)
		fatal(lockErr)
		defer sessionLock.Close()
		switch *caMode {
		case "emudhra":
			fatal(loadEMudhra())
			logger.Print("WARNING: compromised public CA material; isolated authorized lab only")
		case "self":
			c, e := ensureLocalCA(filepath.Join(*dir, "ca"))
			fatal(e)
			ca = c.cert
			caKey = c.key
			caChain = [][]byte{ca.Raw}
			logger.Printf("CA mode=self certificate=%s", c.name)
		default:
			logger.Fatal("unknown CA mode")
		}
		var e error
		db, e = sql.Open("sqlite", filepath.Join(*dir, "capture.sqlite3"))
		fatal(e)
		defer db.Close()
		db.SetMaxOpenConns(1)
		_, e = db.Exec(`PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY, package TEXT, uid INTEGER, port INTEGER, started_ms INTEGER); CREATE TABLE IF NOT EXISTS exchanges(id INTEGER PRIMARY KEY,session_id INTEGER,ts_ms INTEGER,dst TEXT,sni TEXT,scheme TEXT,method TEXT,host TEXT,path TEXT,req_headers TEXT,req_body BLOB,req_size INTEGER,resp_status INTEGER,resp_headers TEXT,resp_body BLOB,resp_size INTEGER,error TEXT,duration_ms INTEGER); CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,session_id INTEGER,ts_ms INTEGER,dst TEXT,type TEXT,message TEXT);`)
		fatal(e)
		_, e = db.Exec(`INSERT INTO sessions(package,uid,port,started_ms) VALUES(?,?,?,?)`, pkg, uid, *port, time.Now().UnixMilli())
		fatal(e)
		fatal(db.QueryRow(`SELECT last_insert_rowid()`).Scan(&session))
		l, e := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", *port))
		fatal(e)
		defer l.Close()
		logger.Printf("READY session=%d uid=%d package=%s port=%d", session, uid, pkg, *port)
		for {
			c, e := l.Accept()
			if e != nil {
				logger.Print(e)
				continue
			}
			go serveConn(c.(*net.TCPConn))
		}
	case "show":
		f := flag.NewFlagSet("show", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		id := f.Int64("id", 0, "exchange id")
		f.Parse(os.Args[2:])
		if *id < 1 {
			logger.Fatal("positive id required")
		}
		var e error
		var viewLock *os.File
		db, viewLock, e = openForView(*dir)
		fatal(e)
		defer func() {
			db.Close()
			if viewLock != nil {
				viewLock.Close()
			}
		}()
		var sessionID, ts, requestSize, responseSize int64
		var dst, sni, scheme, method, host, path, requestHeaders, responseHeaders, errText string
		var status int
		var reqBody, respBody []byte
		e = db.QueryRow(`SELECT session_id,ts_ms,coalesce(dst,''),coalesce(sni,''),coalesce(scheme,''),coalesce(method,''),coalesce(host,''),coalesce(path,''),coalesce(req_headers,''),req_body,coalesce(req_size,0),coalesce(resp_status,0),coalesce(resp_headers,''),resp_body,coalesce(resp_size,0),coalesce(error,'') FROM exchanges WHERE id=?`, *id).Scan(&sessionID, &ts, &dst, &sni, &scheme, &method, &host, &path, &requestHeaders, &reqBody, &requestSize, &status, &responseHeaders, &respBody, &responseSize, &errText)
		fatal(e)
		out := map[string]any{"id": *id, "session_id": sessionID, "timestamp_ms": ts, "destination": dst, "sni": sni, "scheme": scheme, "method": method, "host": host, "path": path, "request_headers_json": requestHeaders, "request_body_base64": reqBody, "request_size": requestSize, "response_status": status, "response_headers_json": responseHeaders, "response_body_base64": respBody, "response_size": responseSize, "error": errText, "transport": "tcp"}
		var hasQuic int
		fatal(db.QueryRow(`SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='quic_exchange_meta')`).Scan(&hasQuic))
		if hasQuic != 0 {
			var conn, version, alpn, client string
			var stream int64
			err := db.QueryRow(`SELECT coalesce(conn_id,''),coalesce(stream_id,-1),coalesce(quic_version,''),coalesce(alpn,''),coalesce(client_addr,'') FROM quic_exchange_meta WHERE exchange_id=?`, *id).Scan(&conn, &stream, &version, &alpn, &client)
			if err == nil {
				out["transport"] = "udp"
				out["quic"] = map[string]any{"connection_id": conn, "stream_id": stream, "quic_version": version, "alpn": alpn, "client_address": client}
			} else if !errors.Is(err, sql.ErrNoRows) {
				fatal(err)
			}
		}
		var hasOrigin int
		fatal(db.QueryRow(`SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='tpc_origin_meta')`).Scan(&hasOrigin))
		if hasOrigin != 0 {
			var raw string
			err := db.QueryRow(`SELECT origin_json FROM tpc_origin_meta WHERE exchange_id=?`, *id).Scan(&raw)
			if err == nil {
				var attribution any
				if json.Unmarshal([]byte(raw), &attribution) == nil {
					out["origin"] = attribution
				}
			} else if !errors.Is(err, sql.ErrNoRows) {
				fatal(err)
			}
		}
		j, e := json.MarshalIndent(out, "", "  ")
		fatal(e)
		fmt.Println(string(j))
	case "events":
		f := flag.NewFlagSet("events", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		f.Parse(os.Args[2:])
		var e error
		var viewLock *os.File
		db, viewLock, e = openForView(*dir)
		fatal(e)
		defer func() {
			db.Close()
			if viewLock != nil {
				viewLock.Close()
			}
		}()
		rows, e := db.Query(`SELECT id,session_id,datetime(ts_ms/1000,'unixepoch'),coalesce(dst,''),type,coalesce(message,'') FROM events ORDER BY id DESC LIMIT 100`)
		fatal(e)
		defer rows.Close()
		for rows.Next() {
			var id, sessionID int64
			var ts, dst, typ, msg string
			fatal(rows.Scan(&id, &sessionID, &ts, &dst, &typ, &msg))
			fmt.Printf("%d\t%d\t%s\t%s\t%s\t%s\n", id, sessionID, ts, dst, typ, strings.ReplaceAll(msg, "\n", " "))
		}
		fatal(rows.Err())
	case "list-json":
		f := flag.NewFlagSet("list-json", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		limit := f.Int("limit", 100, "page size (1-1000)")
		before := f.Int64("before", 0, "return IDs smaller than this cursor; 0 starts at newest")
		after := f.Int64("after", -1, "return IDs greater than this cursor, oldest first; -1 disables")
		includeWatermark := f.Bool("watermark", false, "include a current database ID watermark")
		uidFilter := f.Int64("uid", -1, "exact UID; -1 disables")
		urlFilter := f.String("url", "", "literal URL substring")
		textFilter := f.String("text", "", "literal full-text substring")
		transportFilter := f.String("transport", "", "tcp or udp")
		methodFilter := f.String("method", "", "HTTP method")
		mediaOnly := f.Bool("media", false, "only media responses")
		var targets targetFlags
		var excludedHosts targetFlags
		f.Var(&targets, "target", "package name or uid:N; repeatable")
		f.Var(&excludedHosts, "exclude-host", "exclude a host and its subdomains; repeatable")
		f.Parse(os.Args[2:])
		if *limit < 1 || *limit > 1000 || *before < 0 || *after < -1 || *uidFilter < -1 || (*transportFilter != "" && *transportFilter != "tcp" && *transportFilter != "udp") {
			logger.Fatal("invalid pagination arguments")
		}
		var e error
		var viewLock *os.File
		db, viewLock, e = openForView(*dir)
		fatal(e)
		defer func() {
			db.Close()
			if viewLock != nil {
				viewLock.Close()
			}
		}()
		db.SetMaxOpenConns(1)
		_, e = db.Exec("PRAGMA busy_timeout=5000")
		fatal(e)
		var searchCutoff int64
		if *textFilter != "" {
			searchCutoff, e = syncSearch(db)
			fatal(e)
		}
		where := []string{}
		args := []any{}
		if *before > 0 {
			where = append(where, "e.id < ?")
			args = append(args, *before)
		}
		if *after >= 0 {
			where = append(where, "e.id > ?")
			args = append(args, *after)
		}
		if *uidFilter >= 0 {
			where = append(where, "s.uid = ?")
			args = append(args, *uidFilter)
		}
		if *urlFilter != "" {
			where = append(where, "instr(lower(coalesce(e.scheme,'') || '://' || coalesce(e.host,'') || coalesce(e.path,'')),lower(?)) > 0")
			args = append(args, *urlFilter)
		}
		if len(excludedHosts) > 0 {
			host := `coalesce(nullif(e.host,''),nullif(e.sni,''),'')`
			normalized := `lower(rtrim(CASE WHEN substr(` + host + `,1,1)='[' AND instr(` + host + `,']')>0 THEN substr(` + host + `,2,instr(` + host + `,']')-2) WHEN instr(` + host + `,':')>0 AND instr(substr(` + host + `,instr(` + host + `,':')+1),':')=0 THEN substr(` + host + `,1,instr(` + host + `,':')-1) ELSE ` + host + ` END,'.'))`
			for _, value := range excludedHosts {
				value = strings.ToLower(strings.TrimSpace(value))
				value = strings.TrimPrefix(value, "*.")
				value = strings.Trim(value, ".")
				if value == "" {
					continue
				}
				where = append(where, "NOT ("+normalized+" = ? OR substr("+normalized+", -length(?) - 1) = '.' || ?)")
				args = append(args, value, value, value)
			}
		}
		if *textFilter != "" {
			where = append(where, "e.id <= ?")
			args = append(args, searchCutoff)
			if utf8.RuneCountInString(*textFilter) >= 3 {
				where = append(where, "e.id IN (SELECT rowid FROM tpc_search_fts WHERE tpc_search_fts MATCH ?)")
				args = append(args, "\""+strings.ReplaceAll(*textFilter, "\"", "\"\"")+"\"")
			} else {
				where = append(where, "e.id IN (SELECT rowid FROM tpc_search_fts WHERE instr(lower(content),lower(?)) > 0)")
				args = append(args, *textFilter)
			}
		}
		if len(targets) > 0 {
			parts := []string{}
			for _, target := range targets {
				if strings.HasPrefix(target, "uid:") {
					uid, err := strconv.ParseInt(strings.TrimPrefix(target, "uid:"), 10, 32)
					if err != nil || uid < 0 {
						logger.Fatal("invalid target UID")
					}
					parts = append(parts, "s.uid = ?")
					args = append(args, uid)
				} else {
					parts = append(parts, "s.package = ?")
					args = append(args, target)
				}
			}
			where = append(where, "("+strings.Join(parts, " OR ")+")")
		}
		if *methodFilter != "" {
			where = append(where, "upper(coalesce(e.method,'')) = ?")
			args = append(args, strings.ToUpper(*methodFilter))
		}
		tx, e := db.Begin()
		fatal(e)
		defer tx.Rollback()
		var watermark int64
		if *includeWatermark && *after >= 0 {
			fatal(tx.QueryRow(`SELECT coalesce(max(id),0) FROM exchanges`).Scan(&watermark))
			if *textFilter != "" && searchCutoff < watermark {
				watermark = searchCutoff
			}
		}
		var hasQuic int
		fatal(tx.QueryRow(`SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='quic_exchange_meta')`).Scan(&hasQuic))
		transportExpr := "'tcp'"
		joinQuic := ""
		if hasQuic != 0 {
			transportExpr = "CASE WHEN q.exchange_id IS NULL THEN 'tcp' ELSE 'udp' END"
			joinQuic = " LEFT JOIN quic_exchange_meta q ON q.exchange_id=e.id"
		}
		contentType := `lower(coalesce((SELECT CASE WHEN h.type='array' THEN coalesce(json_extract(h.value,'$[0]'),'') ELSE coalesce(h.value,'') END FROM json_each(CASE WHEN json_valid(e.resp_headers) THEN e.resp_headers ELSE '{}' END) h WHERE lower(h.key)='content-type' LIMIT 1),''))`
		cleanPath := `lower(CASE WHEN instr(coalesce(e.path,''),'?')>0 THEN substr(e.path,1,instr(e.path,'?')-1) ELSE coalesce(e.path,'') END)`
		query := `WITH base AS (SELECT e.id AS id,e.session_id AS session_id,e.ts_ms AS ts_ms,s.uid AS uid,s.package AS package,coalesce(e.method,'') AS method,coalesce(e.scheme,'') AS scheme,coalesce(e.host,'') AS host,coalesce(e.path,'') AS path,coalesce(e.resp_status,0) AS status,coalesce(e.error,'') AS error,` + transportExpr + ` AS transport,` + contentType + ` AS ct,` + cleanPath + ` AS clean_path,substr(e.resp_body,1,12) AS magic FROM exchanges e JOIN sessions s ON s.id=e.session_id` + joinQuic
		if len(where) > 0 {
			query += " WHERE " + strings.Join(where, " AND ")
		}
		query += `), tagged AS (SELECT *, CASE
          WHEN ct LIKE '%mpegurl%' OR ct LIKE '%dash+xml%' OR clean_path LIKE '%.m3u8' OR clean_path LIKE '%.mpd' THEN 'playlist'
          WHEN ct LIKE 'video/%' OR clean_path LIKE '%.mp4' OR clean_path LIKE '%.m4v' OR clean_path LIKE '%.webm' OR clean_path LIKE '%.flv' OR substr(magic,1,3)=X'464C56' THEN 'video'
          WHEN ct LIKE 'audio/%' OR clean_path LIKE '%.mp3' OR clean_path LIKE '%.aac' OR clean_path LIKE '%.m4a' OR clean_path LIKE '%.wav' OR substr(magic,1,3)=X'494433' THEN 'audio'
          WHEN ct LIKE 'image/%' OR clean_path LIKE '%.jpg' OR clean_path LIKE '%.jpeg' OR clean_path LIKE '%.png' OR clean_path LIKE '%.gif' OR clean_path LIKE '%.webp' OR clean_path LIKE '%.heic' THEN 'image'
          WHEN substr(magic,5,4)=X'66747970' THEN 'media'
          ELSE '' END AS media_type FROM base) SELECT id,session_id,ts_ms,uid,package,method,scheme,host,path,status,error,transport,media_type FROM tagged`
		outer := []string{}
		if *transportFilter != "" {
			outer = append(outer, "transport = ?")
			args = append(args, *transportFilter)
		}
		if *mediaOnly {
			outer = append(outer, "media_type <> ''")
		}
		if len(outer) > 0 {
			query += " WHERE " + strings.Join(outer, " AND ")
		}
		if *after >= 0 {
			query += " ORDER BY id ASC LIMIT ?"
		} else {
			query += " ORDER BY id DESC LIMIT ?"
		}
		args = append(args, *limit)
		rows, e := tx.Query(query, args...)
		fatal(e)
		defer rows.Close()
		encoder := json.NewEncoder(os.Stdout)
		for rows.Next() {
			var id, sessionID, ts int64
			var uid, status int
			var pkg, method, scheme, host, path, errText, transport, mediaType string
			fatal(rows.Scan(&id, &sessionID, &ts, &uid, &pkg, &method, &scheme, &host, &path, &status, &errText, &transport, &mediaType))
			fatal(encoder.Encode(map[string]any{"id": id, "session_id": sessionID, "timestamp_ms": ts, "uid": uid, "package": pkg, "method": method, "scheme": scheme, "host": host, "path": path, "response_status": status, "error": errText, "transport": transport, "media_type": mediaType}))
		}
		fatal(rows.Err())
		if *includeWatermark && *after >= 0 {
			fatal(encoder.Encode(map[string]any{"_watermark": watermark}))
		}

	case "list":
		f := flag.NewFlagSet("list", flag.ExitOnError)
		dir := f.String("dir", defaultDataDir(), "data dir")
		packageFilter := f.String("package", "", "filter by package")
		limit := f.Int("limit", 100, "maximum rows (1-1000)")
		f.Parse(os.Args[2:])
		var e error
		var viewLock *os.File
		db, viewLock, e = openForView(*dir)
		fatal(e)
		defer func() {
			db.Close()
			if viewLock != nil {
				viewLock.Close()
			}
		}()
		if *limit < 1 || *limit > 1000 {
			logger.Fatal("limit must be 1-1000")
		}
		rows, e := db.Query(`SELECT e.id,e.session_id,datetime(e.ts_ms/1000,'unixepoch'),e.method,e.host,e.path,e.resp_status,coalesce(e.error,'') FROM exchanges e JOIN sessions s ON s.id=e.session_id WHERE (?='' OR s.package=?) ORDER BY e.id DESC LIMIT ?`, *packageFilter, *packageFilter, *limit)
		fatal(e)
		defer rows.Close()
		for rows.Next() {
			var id, ss, status int
			var t, m, h, p, er string
			fatal(rows.Scan(&id, &ss, &t, &m, &h, &p, &status, &er))
			fmt.Printf("%d\t%d\t%s\t%s\t%s%s\t%d\t%s\n", id, ss, t, m, h, p, status, strings.ReplaceAll(er, "\n", " "))
		}
		fatal(rows.Err())
	default:
		logger.Fatal("unknown command")
	}
}
