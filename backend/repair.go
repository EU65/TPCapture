package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Only clears a dead controller's own known lock files; never changes rules or proxy processes.
func repairOperationLock(dir, script string) error {
	lock := filepath.Join(dir, ".capture-lock")
	st, e := os.Lstat(lock)
	if os.IsNotExist(e) {
		fmt.Println("没有残留操作锁")
		return nil
	}
	if e != nil {
		return e
	}
	if !st.IsDir() {
		return errors.New("锁路径不是目录，拒绝修改")
	}
	entries, e := os.ReadDir("/proc")
	if e != nil {
		return e
	}
	active := map[string]bool{"start": true, "stop": true, "reload": true, "new-session-start": true, "cleanup": true}
	for _, entry := range entries {
		pid, e := strconv.Atoi(entry.Name())
		if e != nil || pid == os.Getpid() {
			continue
		}
		b, e := os.ReadFile(filepath.Join("/proc", entry.Name(), "cmdline"))
		if e != nil || len(b) == 0 {
			continue
		}
		raw := bytes.Split(b, []byte{0})
		args := make([]string, 0, len(raw))
		for _, v := range raw {
			if len(v) > 0 {
				args = append(args, string(v))
			}
		}
		if len(args) < 3 {
			continue
		}
		exe := filepath.Base(args[0])
		if exe != "sh" && exe != "mksh" && exe != "bash" {
			continue
		}
		control := args[1]
		if (filepath.Clean(control) == filepath.Clean(script) || filepath.Base(control) == "appcapture.sh") && active[args[2]] {
			return fmt.Errorf("仍有启停任务运行（PID %d），请等待完成，未清理锁", pid)
		}
	}
	if b, e := os.ReadFile(filepath.Join(lock, "owner.pid")); e == nil {
		pid, e := strconv.Atoi(strings.TrimSpace(string(b)))
		if e == nil && pid > 0 {
			if info, e := os.ReadFile(filepath.Join("/proc", strconv.Itoa(pid), "stat")); e == nil {
				end := bytes.LastIndexByte(info, ')')
				state := ""
				if end >= 0 {
					fields := strings.Fields(string(info[end+1:]))
					if len(fields) > 0 {
						state = fields[0]
					}
				}
				if state != "Z" && state != "X" {
					return fmt.Errorf("锁持有进程仍存在（PID %d），未清理锁", pid)
				}
			}
		}
	}
	files, e := os.ReadDir(lock)
	if e != nil {
		return e
	}
	allowed := map[string]bool{"owner.pid": true, "targets": true, "pids": true, "packages": true, "packages.tmp": true, "newtargets": true, "oldtargets": true, "rules4": true, "rules6": true}
	for _, f := range files {
		if !allowed[f.Name()] || f.IsDir() {
			return fmt.Errorf("锁目录含未知文件 %q，拒绝清理", f.Name())
		}
	}
	for _, f := range files {
		if e = os.Remove(filepath.Join(lock, f.Name())); e != nil {
			return e
		}
	}
	if e = os.Remove(lock); e != nil {
		return e
	}
	fmt.Println("已清理残留操作锁；未修改现有代理进程和网络规则")
	return nil
}
