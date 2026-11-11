package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"time"
)

var archiveName = regexp.MustCompile(`^capture-[0-9]{8}-[0-9]{6}\.[0-9]{9}-[0-9]+\.sqlite3$`)

func acquireDBLock(dir string, exclusive bool) (*os.File, error) {
	if e := os.MkdirAll(dir, 0700); e != nil {
		return nil, e
	}
	f, e := os.OpenFile(filepath.Join(dir, ".database.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	op := syscall.LOCK_SH
	if exclusive {
		op = syscall.LOCK_EX
	}
	if e = syscall.Flock(int(f.Fd()), op|syscall.LOCK_NB); e != nil {
		f.Close()
		return nil, fmt.Errorf("数据库正被使用，请等待请求或搜索完成后重试: %w", e)
	}
	return f, nil
}
func selectedDB(dir string) (string, bool, error) {
	name := os.Getenv("TPCAPTURE_DB")
	if name == "" || name == "current" {
		return filepath.Join(dir, "capture.sqlite3"), true, nil
	}
	if !archiveName.MatchString(name) {
		return "", false, errors.New("无效的历史数据库名称")
	}
	path := filepath.Join(dir, "history", name)
	info, e := os.Lstat(path)
	if e != nil {
		return "", false, e
	}
	if !info.Mode().IsRegular() {
		return "", false, errors.New("历史数据库不是普通文件")
	}
	return path, false, nil
}
func openForView(dir string) (*sql.DB, *os.File, error) {
	path, current, e := selectedDB(dir)
	if e != nil {
		return nil, nil, e
	}
	var lock *os.File
	if current {
		lock, e = acquireDBLock(dir, false)
		if e != nil {
			return nil, nil, e
		}
	}
	if os.Getenv("TPCAPTURE_NO_CREATE") == "1" {
		info, err := os.Stat(path)
		if err != nil {
			if lock != nil {
				lock.Close()
			}
			return nil, nil, err
		}
		if !info.Mode().IsRegular() {
			if lock != nil {
				lock.Close()
			}
			return nil, nil, errors.New("database is not a regular file")
		}
	}
	db, e := sql.Open("sqlite", path)
	if e != nil {
		if lock != nil {
			lock.Close()
		}
		return nil, nil, e
	}
	return db, lock, nil
}
func archiveCurrent(dir string) (string, error) {
	lock, e := acquireDBLock(dir, true)
	if e != nil {
		return "", e
	}
	defer lock.Close()
	src := filepath.Join(dir, "capture.sqlite3")
	info, e := os.Lstat(src)
	if os.IsNotExist(e) {
		if _, we := os.Stat(src + "-wal"); we == nil {
			return "", errors.New("数据库文件不存在，但 WAL 仍存在，拒绝归档")
		}
		return "", nil
	}
	if e != nil {
		return "", e
	}
	if !info.Mode().IsRegular() {
		return "", errors.New("当前数据库不是普通文件")
	}
	db, e := sql.Open("sqlite", src)
	if e != nil {
		return "", e
	}
	db.SetMaxOpenConns(1)
	if _, e = db.Exec(`PRAGMA busy_timeout=5000`); e != nil {
		db.Close()
		return "", e
	}
	var busy, logFrames, checkpointed int
	if e = db.QueryRow(`PRAGMA wal_checkpoint(TRUNCATE)`).Scan(&busy, &logFrames, &checkpointed); e != nil {
		db.Close()
		return "", e
	}
	if busy != 0 {
		db.Close()
		return "", errors.New("数据库仍有活动连接，无法安全归档")
	}
	if e = db.Close(); e != nil {
		return "", e
	}
	if wal, e := os.Stat(src + "-wal"); e == nil && wal.Size() != 0 {
		return "", errors.New("WAL 不为空，拒绝归档")
	}
	for _, suffix := range []string{"-wal", "-shm"} {
		if e = os.Remove(src + suffix); e != nil && !os.IsNotExist(e) {
			return "", e
		}
	}
	history := filepath.Join(dir, "history")
	if e = os.MkdirAll(history, 0700); e != nil {
		return "", e
	}
	name := fmt.Sprintf("capture-%s-%d.sqlite3", time.Now().Format("20060102-150405.000000000"), os.Getpid())
	dst := filepath.Join(history, name)
	if _, e = os.Lstat(dst); e == nil {
		return "", errors.New("历史数据库文件已存在")
	} else if !os.IsNotExist(e) {
		return "", e
	}
	if e = os.Rename(src, dst); e != nil {
		return "", e
	}
	return name, nil
}
func databases(dir string) error {
	type item struct {
		ID       string `json:"id"`
		Label    string `json:"label"`
		Size     int64  `json:"size"`
		Modified int64  `json:"modified_ms"`
	}
	enc := json.NewEncoder(os.Stdout)
	if e := enc.Encode(item{ID: "current", Label: "当前会话"}); e != nil {
		return e
	}
	entries, e := os.ReadDir(filepath.Join(dir, "history"))
	if os.IsNotExist(e) {
		return nil
	}
	if e != nil {
		return e
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if archiveName.MatchString(entry.Name()) && entry.Type().IsRegular() {
			names = append(names, entry.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	for _, name := range names {
		info, e := os.Stat(filepath.Join(dir, "history", name))
		if e != nil {
			return e
		}
		label := strings.TrimSuffix(strings.TrimPrefix(name, "capture-"), ".sqlite3")
		if e = enc.Encode(item{ID: name, Label: label, Size: info.Size(), Modified: info.ModTime().UnixMilli()}); e != nil {
			return e
		}
	}
	return nil
}
