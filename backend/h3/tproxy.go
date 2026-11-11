package h3

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
	"net"
	"sync"
	"syscall"
	"time"
)

type flowAddr struct{ client, original *net.UDPAddr }

func (a *flowAddr) Network() string { return "udp" }
func (a *flowAddr) String() string  { return a.client.String() + "|" + a.original.String() }

type replySocket struct {
	conn *net.UDPConn
	used time.Time
}
type packetAdapter struct {
	in            *net.UDPConn
	network       string
	bypass, route uint32
	mu            sync.Mutex
	reply         map[string]*replySocket
}

func markSocket(fd uintptr, bypass, route uint32) error {
	v, e := unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK)
	if e != nil {
		return e
	}
	return unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, int((uint32(v)|bypass)&^route))
}
func listenTransparent(ctx context.Context, network, address string, bypass, route uint32, receive bool) (*net.UDPConn, error) {
	lc := net.ListenConfig{Control: func(_, _ string, c syscall.RawConn) error {
		var inner error
		e := c.Control(func(fd uintptr) {
			if inner = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_REUSEADDR, 1); inner != nil {
				return
			}
			if network == "udp6" {
				if inner = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_V6ONLY, 1); inner != nil {
					return
				}
				if inner = unix.SetsockoptInt(int(fd), unix.SOL_IPV6, unix.IPV6_TRANSPARENT, 1); inner != nil {
					return
				}
				if receive {
					inner = unix.SetsockoptInt(int(fd), unix.SOL_IPV6, unix.IPV6_RECVORIGDSTADDR, 1)
				}
			} else {
				if inner = unix.SetsockoptInt(int(fd), unix.SOL_IP, unix.IP_TRANSPARENT, 1); inner != nil {
					return
				}
				if receive {
					inner = unix.SetsockoptInt(int(fd), unix.SOL_IP, unix.IP_RECVORIGDSTADDR, 1)
				}
			}
			if !receive && inner == nil {
				inner = markSocket(fd, bypass, route)
			}
		})
		if e != nil {
			return e
		}
		return inner
	}}
	p, e := lc.ListenPacket(ctx, network, address)
	if e != nil {
		return nil, e
	}
	u, ok := p.(*net.UDPConn)
	if !ok {
		p.Close()
		return nil, errors.New("not UDP")
	}
	return u, nil
}
func (p *packetAdapter) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		var oob [256]byte
		n, on, flags, src, e := p.in.ReadMsgUDP(b, oob[:])
		if e != nil {
			return 0, nil, e
		}
		if flags&(unix.MSG_TRUNC|unix.MSG_CTRUNC) != 0 {
			continue
		}
		msgs, e := unix.ParseSocketControlMessage(oob[:on])
		if e != nil {
			continue
		}
		for _, m := range msgs {
			var dst *net.UDPAddr
			if m.Header.Level == unix.SOL_IP && m.Header.Type == unix.IP_ORIGDSTADDR && len(m.Data) >= 8 && binary.NativeEndian.Uint16(m.Data[:2]) == unix.AF_INET {
				dst = &net.UDPAddr{IP: net.IPv4(m.Data[4], m.Data[5], m.Data[6], m.Data[7]), Port: int(binary.BigEndian.Uint16(m.Data[2:4]))}
			} else if m.Header.Level == unix.SOL_IPV6 && m.Header.Type == unix.IPV6_ORIGDSTADDR && len(m.Data) >= 28 && binary.NativeEndian.Uint16(m.Data[:2]) == unix.AF_INET6 {
				addr := make(net.IP, 16)
				copy(addr, m.Data[8:24])
				dst = &net.UDPAddr{IP: addr, Port: int(binary.BigEndian.Uint16(m.Data[2:4]))}
				scope := binary.NativeEndian.Uint32(m.Data[24:28])
				if scope > 0 {
					if iface, e := net.InterfaceByIndex(int(scope)); e == nil {
						dst.Zone = iface.Name
					} else {
						dst.Zone = fmt.Sprint(scope)
					}
				}
			}
			if dst != nil {
				return n, &flowAddr{client: src, original: dst}, nil
			}
		}
	}
}
func (p *packetAdapter) WriteTo(b []byte, a net.Addr) (int, error) {
	f, ok := a.(*flowAddr)
	if !ok {
		return 0, fmt.Errorf("unexpected address %T", a)
	}
	key := f.original.String()
	p.mu.Lock()
	defer p.mu.Unlock()
	entry := p.reply[key]
	if entry == nil {
		if len(p.reply) >= 256 {
			var old string
			var when time.Time
			for k, v := range p.reply {
				if old == "" || v.used.Before(when) {
					old = k
					when = v.used
				}
			}
			if old != "" {
				_ = p.reply[old].conn.Close()
				delete(p.reply, old)
			}
		}
		u, e := listenTransparent(context.Background(), p.network, key, p.bypass, p.route, false)
		if e != nil {
			return 0, e
		}
		entry = &replySocket{conn: u}
		p.reply[key] = entry
	}
	entry.used = time.Now()
	return entry.conn.WriteToUDP(b, f.client)
}
func (p *packetAdapter) Close() error {
	e := p.in.Close()
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, s := range p.reply {
		_ = s.conn.Close()
	}
	p.reply = map[string]*replySocket{}
	return e
}
func (p *packetAdapter) LocalAddr() net.Addr                { return p.in.LocalAddr() }
func (p *packetAdapter) SetDeadline(t time.Time) error      { return p.in.SetDeadline(t) }
func (p *packetAdapter) SetReadDeadline(t time.Time) error  { return p.in.SetReadDeadline(t) }
func (p *packetAdapter) SetWriteDeadline(t time.Time) error { return p.in.SetWriteDeadline(t) }
