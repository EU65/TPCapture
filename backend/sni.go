package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"time"
)

const helloLimit = 60 << 10

// peekHello returns the first TLS handshake message without consuming it.
func peekHello(br *bufio.Reader) ([]byte, error) {
	total := 0
	for {
		hdr, e := br.Peek(total + 5)
		if e != nil {
			return nil, e
		}
		n := int(binary.BigEndian.Uint16(hdr[total+3 : total+5]))
		if n <= 0 || n > 16384+2048 {
			return nil, errors.New("invalid TLS record")
		}
		total += 5 + n
		if total > helloLimit {
			return nil, errors.New("ClientHello too large")
		}
		buf, e := br.Peek(total)
		if e != nil {
			return nil, e
		}
		if total >= 9 {
			need := 9 + int(buf[6])<<16 + int(buf[7])<<8 + int(buf[8])
			if total >= need {
				return buf[5:need], nil
			}
		}
	}
}

func parseSNI(hello []byte) (string, bool) {
	if len(hello) < 4 || hello[0] != 1 {
		return "", false
	}
	p := 4 + 2 + 32
	if p >= len(hello) {
		return "", false
	}
	p += 1 + int(hello[p])
	if p+2 > len(hello) {
		return "", false
	}
	p += 2 + int(binary.BigEndian.Uint16(hello[p:]))
	if p >= len(hello) {
		return "", false
	}
	p += 1 + int(hello[p])
	if p+2 > len(hello) {
		return "", false
	}
	p += 2
	end := p + int(binary.BigEndian.Uint16(hello[p-2:]))
	if end > len(hello) {
		end = len(hello)
	}
	for p+4 <= end {
		typ := binary.BigEndian.Uint16(hello[p:])
		l := int(binary.BigEndian.Uint16(hello[p+2:]))
		p += 4
		if p+l > end {
			return "", false
		}
		if typ == 0 && l >= 5 {
			name := hello[p : p+l]
			size := int(binary.BigEndian.Uint16(name[3:]))
			if 5+size <= len(name) {
				return string(name[5 : 5+size]), true
			}
		}
		p += l
	}
	return "", false
}

func pipeTransparent(c *net.TCPConn, br *bufio.Reader, dst, sni string) {
	_ = c.SetDeadline(time.Time{})
	up, e := dialUpstream(dst, sni, "raw")
	if e != nil {
		return
	}
	defer up.Close()
	if n := br.Buffered(); n > 0 {
		pending, e := br.Peek(n)
		if e != nil {
			return
		}
		if _, e = up.Write(pending); e != nil {
			return
		}
		if _, e = br.Discard(n); e != nil {
			return
		}
	}
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(up, c)
		if tcp, ok := up.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		close(done)
	}()
	_, _ = io.Copy(c, up)
	<-done
}
