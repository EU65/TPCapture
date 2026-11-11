package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

type localCA struct {
	cert        *x509.Certificate
	key         *ecdsa.PrivateKey
	pemBytes    []byte
	name        string
	pemPath     string
	hashPath    string
	fingerprint string
}

func atomicCAFile(dir, name string, data []byte) error {
	f, e := os.CreateTemp(dir, ".ca-")
	if e != nil {
		return e
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if e = f.Chmod(0600); e != nil {
		f.Close()
		return e
	}
	if _, e = f.Write(data); e != nil {
		f.Close()
		return e
	}
	if e = f.Sync(); e != nil {
		f.Close()
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	return os.Rename(tmp, filepath.Join(dir, name))
}
func ensureLocalCA(dir string) (*localCA, error) {
	if e := os.MkdirAll(dir, 0700); e != nil {
		return nil, e
	}
	lock, e := os.OpenFile(filepath.Join(dir, ".ca.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	defer lock.Close()
	if e = syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); e != nil {
		return nil, e
	}
	certPath, keyPath := filepath.Join(dir, "ca.pem"), filepath.Join(dir, "ca-key.pem")
	cb, ce := os.ReadFile(certPath)
	kb, ke := os.ReadFile(keyPath)
	if os.IsNotExist(ce) && os.IsNotExist(ke) {
		k, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if e != nil {
			return nil, e
		}
		serial, e := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
		if e != nil {
			return nil, e
		}
		if serial.Sign() == 0 {
			serial.SetInt64(1)
		}
		nameSuffix := fmt.Sprintf("%x", serial.Bytes())
		if len(nameSuffix) > 12 {
			nameSuffix = nameSuffix[:12]
		}
		now := time.Now()
		root := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "TPCapture Local CA " + nameSuffix}, NotBefore: now.Add(-time.Hour), NotAfter: now.AddDate(5, 0, 0), IsCA: true, BasicConstraintsValid: true, MaxPathLen: 0, MaxPathLenZero: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign}
		der, e := x509.CreateCertificate(rand.Reader, root, root, &k.PublicKey, k)
		if e != nil {
			return nil, e
		}
		priv, e := x509.MarshalPKCS8PrivateKey(k)
		if e != nil {
			return nil, e
		}
		kb = pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: priv})
		cb = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
		if e = atomicCAFile(dir, "ca-key.pem", kb); e != nil {
			return nil, e
		}
		if e = atomicCAFile(dir, "ca.pem", cb); e != nil {
			return nil, e
		}
	} else if ce != nil || ke != nil {
		return nil, errors.New("CA 文件不完整或不可读取；为避免覆盖现有私钥，已停止")
	}
	certBlock, _ := pem.Decode(cb)
	keyBlock, _ := pem.Decode(kb)
	if certBlock == nil || keyBlock == nil {
		return nil, errors.New("CA PEM 格式无效")
	}
	cert, e := x509.ParseCertificate(certBlock.Bytes)
	if e != nil {
		return nil, e
	}
	priv, e := x509.ParsePKCS8PrivateKey(keyBlock.Bytes)
	if e != nil {
		return nil, e
	}
	key, ok := priv.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("CA 私钥类型不支持")
	}
	pub, ok := cert.PublicKey.(*ecdsa.PublicKey)
	if !ok || !cert.IsCA || !pub.Equal(&key.PublicKey) {
		return nil, errors.New("CA 证书与私钥不匹配")
	}
	digest := md5.Sum(cert.RawSubject)
	hash := binary.LittleEndian.Uint32(digest[:4])
	filename := fmt.Sprintf("%08x.0", hash)
	hashPath := filepath.Join(dir, filename)
	if existing, e := os.ReadFile(hashPath); e == nil {
		if !bytes.Equal(existing, cb) {
			return nil, errors.New("同名 .0 文件与当前 CA 不一致，拒绝覆盖")
		}
	} else if os.IsNotExist(e) {
		if e = atomicCAFile(dir, filename, cb); e != nil {
			return nil, e
		}
	} else {
		return nil, e
	}
	fingerprint := sha256.Sum256(cert.Raw)
	return &localCA{cert: cert, key: key, pemBytes: cb, name: filename, pemPath: certPath, hashPath: hashPath, fingerprint: strings.ToUpper(hex.EncodeToString(fingerprint[:]))}, nil
}
func (c *localCA) publicInfo() map[string]any {
	return map[string]any{"pem": string(c.pemBytes), "name": c.name, "pem_path": c.pemPath, "hash_path": c.hashPath, "sha256": c.fingerprint}
}
