// Origin lookup component for a single-entry transparent proxy.
// Read-only: it does not install routes or start a proxy.
package origin

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type Process struct {
	PID  int    `json:"pid"`
	Name string `json:"name"`
}
type Candidate struct {
	UID       uint32    `json:"uid"`
	Inode     uint64    `json:"inode"`
	Local     string    `json:"local"`
	Remote    string    `json:"remote"`
	Wildcard  bool      `json:"wildcard"`
	Processes []Process `json:"processes,omitempty"`
}
type Result struct {
	Method     string      `json:"method"`
	Candidates []Candidate `json:"candidates"`
	UID        *uint32     `json:"uid,omitempty"`
	Ambiguous  bool        `json:"ambiguous"`
	Note       string      `json:"note,omitempty"`
}

type tuple struct {
	source, destination netip.AddrPort
	network             string
}

func unspecified(a netip.Addr) bool { return !a.IsValid() || a.IsUnspecified() }
func normalized(a netip.Addr) netip.Addr {
	if !a.IsValid() {
		return a
	}
	return a.Unmap().WithZone("")
}
func matches(t tuple, local, remote netip.AddrPort) (bool, bool) {
	if local.Port() != t.source.Port() {
		return false, false
	}
	li, si := normalized(local.Addr()), normalized(t.source.Addr())
	if !unspecified(li) && li != si {
		return false, false
	}
	wildcard := unspecified(li)
	if t.network == "tcp" {
		if normalized(remote.Addr()) != normalized(t.destination.Addr()) || remote.Port() != t.destination.Port() {
			return false, false
		}
		return true, wildcard
	}
	if remote.Port() == 0 && unspecified(normalized(remote.Addr())) {
		return true, true
	}
	if normalized(remote.Addr()) != normalized(t.destination.Addr()) || remote.Port() != t.destination.Port() {
		return false, false
	}
	return true, wildcard
}
func diag(t tuple, family byte) ([]Candidate, error) {
	fd, e := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW|unix.SOCK_CLOEXEC, unix.NETLINK_SOCK_DIAG)
	if e != nil {
		return nil, e
	}
	defer unix.Close(fd)
	if e = unix.Bind(fd, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); e != nil {
		return nil, e
	}
	tv := unix.NsecToTimeval((250 * time.Millisecond).Nanoseconds())
	if e = unix.SetsockoptTimeval(fd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &tv); e != nil {
		return nil, e
	}
	req := make([]byte, 16+56)
	binary.NativeEndian.PutUint32(req[0:4], uint32(len(req)))
	binary.NativeEndian.PutUint16(req[4:6], 20)
	binary.NativeEndian.PutUint16(req[6:8], unix.NLM_F_REQUEST|unix.NLM_F_DUMP)
	binary.NativeEndian.PutUint32(req[8:12], 1)
	q := req[16:]
	q[0] = family
	if t.network == "tcp" {
		q[1] = unix.IPPROTO_TCP
	} else {
		q[1] = unix.IPPROTO_UDP
	}
	binary.NativeEndian.PutUint32(q[4:8], 0xffffffff)
	for i := 48; i < 56; i++ {
		q[i] = 0xff
	}
	if e = unix.Sendto(fd, req, 0, &unix.SockaddrNetlink{Family: unix.AF_NETLINK}); e != nil {
		return nil, e
	}
	buf := make([]byte, 256*1024)
	out := []Candidate{}
	for {
		n, _, e := unix.Recvfrom(fd, buf, 0)
		if e != nil {
			return nil, e
		}
		msgs, e := syscall.ParseNetlinkMessage(buf[:n])
		if e != nil {
			return nil, e
		}
		for _, msg := range msgs {
			if msg.Header.Seq != 1 {
				continue
			}
			switch msg.Header.Type {
			case unix.NLMSG_DONE:
				return out, nil
			case unix.NLMSG_ERROR:
				if len(msg.Data) >= 4 {
					v := int32(binary.NativeEndian.Uint32(msg.Data[:4]))
					if v != 0 {
						return nil, syscall.Errno(-v)
					}
				}
				continue
			}
			d := msg.Data
			if len(d) < 72 || d[0] != family {
				continue
			}
			sport := binary.BigEndian.Uint16(d[4:6])
			dport := binary.BigEndian.Uint16(d[6:8])
			var src, dst netip.Addr
			switch family {
			case unix.AF_INET:
				var a, b [4]byte
				copy(a[:], d[8:12])
				copy(b[:], d[24:28])
				src = netip.AddrFrom4(a)
				dst = netip.AddrFrom4(b)
			case unix.AF_INET6:
				var a, b [16]byte
				copy(a[:], d[8:24])
				copy(b[:], d[24:40])
				src = netip.AddrFrom16(a)
				dst = netip.AddrFrom16(b)
			default:
				continue
			}
			local := netip.AddrPortFrom(src, sport)
			remote := netip.AddrPortFrom(dst, dport)
			ok, wild := matches(t, local, remote)
			if !ok {
				continue
			}
			inode := uint64(binary.NativeEndian.Uint32(d[68:72]))
			if inode == 0 {
				continue
			}
			out = append(out, Candidate{UID: binary.NativeEndian.Uint32(d[64:68]), Inode: inode, Local: local.String(), Remote: remote.String(), Wildcard: wild})
		}
	}
}
func procAddr(s string, v6 bool) (netip.AddrPort, error) {
	parts := strings.Split(s, ":")
	if len(parts) != 2 {
		return netip.AddrPort{}, errors.New("bad address")
	}
	port, e := strconv.ParseUint(parts[1], 16, 16)
	if e != nil {
		return netip.AddrPort{}, e
	}
	b, e := hex.DecodeString(parts[0])
	if e != nil {
		return netip.AddrPort{}, e
	}
	if (!v6 && len(b) != 4) || (v6 && len(b) != 16) {
		return netip.AddrPort{}, errors.New("bad IP")
	}
	for i := 0; i < len(b); i += 4 {
		b[i], b[i+3] = b[i+3], b[i]
		b[i+1], b[i+2] = b[i+2], b[i+1]
	}
	if v6 {
		var a [16]byte
		copy(a[:], b)
		return netip.AddrPortFrom(netip.AddrFrom16(a), uint16(port)), nil
	}
	var a [4]byte
	copy(a[:], b)
	return netip.AddrPortFrom(netip.AddrFrom4(a), uint16(port)), nil
}
func procSockets(t tuple) ([]Candidate, error) {
	out := []Candidate{}
	var last error
	success := false
	for _, suffix := range []string{"", "6"} {
		path := "/proc/net/" + t.network + suffix
		b, e := os.ReadFile(path)
		if e != nil {
			last = e
			continue
		}
		success = true
		for _, line := range strings.Split(string(b), "\n") {
			f := strings.Fields(line)
			if len(f) < 10 {
				continue
			}
			local, e := procAddr(f[1], suffix == "6")
			if e != nil {
				continue
			}
			remote, e := procAddr(f[2], suffix == "6")
			if e != nil {
				continue
			}
			ok, wild := matches(t, local, remote)
			if !ok {
				continue
			}
			uid, e := strconv.ParseUint(f[7], 10, 32)
			if e != nil {
				continue
			}
			inode, e := strconv.ParseUint(f[9], 10, 64)
			if e != nil || inode == 0 {
				continue
			}
			out = append(out, Candidate{UID: uint32(uid), Inode: inode, Local: local.String(), Remote: remote.String(), Wildcard: wild})
		}
	}
	if !success || last != nil {
		return out, last
	}
	return out, nil
}

var pidIndex = struct {
	sync.Mutex
	at    time.Time
	byUID map[uint32][]int
}{byUID: make(map[uint32][]int)}

func processUID(pid int) (uint32, bool) {
	b, e := os.ReadFile(filepath.Join("/proc", strconv.Itoa(pid), "status"))
	if e != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "Uid:") {
			f := strings.Fields(line)
			if len(f) > 1 {
				n, e := strconv.ParseUint(f[1], 10, 32)
				return uint32(n), e == nil
			}
			break
		}
	}
	return 0, false
}
func pidsForUID(uid uint32) []int {
	pidIndex.Lock()
	defer pidIndex.Unlock()
	if time.Since(pidIndex.at) > 2*time.Second {
		next := make(map[uint32][]int)
		entries, e := os.ReadDir("/proc")
		if e == nil {
			for _, entry := range entries {
				pid, e := strconv.Atoi(entry.Name())
				if e != nil || pid <= 0 {
					continue
				}
				if id, ok := processUID(pid); ok {
					next[id] = append(next[id], pid)
				}
			}
			pidIndex.byUID = next
			pidIndex.at = time.Now()
		}
	}
	return append([]int(nil), pidIndex.byUID[uid]...)
}
func processes(inode uint64, uid uint32) []Process {
	needle := fmt.Sprintf("socket:[%d]", inode)
	out := []Process{}
	for _, pid := range pidsForUID(uid) {
		id, ok := processUID(pid)
		if !ok || id != uid {
			continue
		}
		base := filepath.Join("/proc", strconv.Itoa(pid))
		fds, e := os.ReadDir(filepath.Join(base, "fd"))
		if e != nil {
			continue
		}
		found := false
		for _, fd := range fds {
			link, e := os.Readlink(filepath.Join(base, "fd", fd.Name()))
			if e == nil && link == needle {
				found = true
				break
			}
		}
		if found {
			b, e := os.ReadFile(filepath.Join(base, "cmdline"))
			name := ""
			if e == nil {
				if n := bytes.IndexByte(b, 0); n >= 0 {
					b = b[:n]
				}
				name = string(b)
			}
			out = append(out, Process{PID: pid, Name: name})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].PID < out[j].PID })
	return out
}
func lookup(t tuple) Result {
	found, procErr := procSockets(t)
	all := append([]Candidate(nil), found...)
	var diagErrors []string
	method := "procfs"
	if procErr != nil || len(found) == 0 {
		method = "inet_diag+procfs"
		for _, family := range []byte{unix.AF_INET, unix.AF_INET6} {
			extra, e := diag(t, family)
			if e != nil {
				diagErrors = append(diagErrors, e.Error())
			} else {
				all = append(all, extra...)
			}
		}
		if procErr != nil && len(diagErrors) == 2 && len(all) == 0 {
			return Result{Method: "unknown", Candidates: []Candidate{}, Note: "socket lookup failed"}
		}
	}
	dedup := map[string]bool{}
	chosen := []Candidate{}
	for _, c := range all {
		key := fmt.Sprintf("%d:%d", c.UID, c.Inode)
		if dedup[key] {
			continue
		}
		dedup[key] = true
		c.Processes = processes(c.Inode, c.UID)
		chosen = append(chosen, c)
	}
	result := Result{Method: method, Candidates: chosen}
	incomplete := procErr != nil && len(diagErrors) > 0
	if len(chosen) == 0 {
		result.Note = "no matching socket; process may have exited or namespace/permissions differ"
		return result
	}
	first := chosen[0].UID
	same := true
	for _, c := range chosen[1:] {
		if c.UID != first {
			same = false
			break
		}
	}
	if !same {
		result.Ambiguous = true
		result.Note = "multiple matching UIDs; no UID guessed"
		return result
	}
	result.UID = &first
	if incomplete {
		result.UID = nil
		result.Ambiguous = true
		result.Note = "some socket tables could not be inspected; attribution may be incomplete"
	}
	for _, c := range chosen {
		if c.Wildcard {
			result.UID = nil
			break
		}
	}
	if len(chosen) > 1 {
		result.Ambiguous = true
		if result.Note != "" {
			result.Note += "; "
		}
		result.Note += "UID is shared by multiple matching sockets; process attribution is ambiguous"
		return result
	}
	if len(chosen[0].Processes) == 0 {
		if result.Note != "" {
			result.Note += "; "
		}
		result.Note += "UID found; process unavailable or socket owner exited"
	} else if len(chosen[0].Processes) > 1 {
		result.Ambiguous = true
		if result.Note != "" {
			result.Note += "; "
		}
		result.Note += "socket is referenced by multiple processes"
	}
	if chosen[0].Wildcard {
		result.UID = nil
		result.Ambiguous = true
		if result.Note != "" {
			result.Note += "; "
		}
		result.Note += "unconnected or wildcard socket match is not conclusive"
	}
	return result
}

// Lookup identifies candidate originating sockets. Ambiguous results must not be attributed to one process.
func Lookup(network string, source, destination netip.AddrPort) Result {
	return lookup(tuple{source: source, destination: destination, network: network})
}
