package main

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/andybalholm/brotli"
)

const rewriteBodyLimit = 8 << 20

type replacement struct {
	Pattern string `json:"pattern"`
	Replace string `json:"replace"`
	Regex   *bool  `json:"regex"`

	re *regexp.Regexp
}

func (r *replacement) compile() error {
	if r.Pattern == "" {
		return errors.New("empty pattern")
	}
	if r.Regex != nil && !*r.Regex {
		return nil
	}
	re, e := regexp.Compile(r.Pattern)
	if e != nil {
		return e
	}
	r.re = re
	return nil
}

func (r *replacement) apply(s string) string {
	if r.re != nil {
		return r.re.ReplaceAllString(s, r.Replace)
	}
	return strings.ReplaceAll(s, r.Pattern, r.Replace)
}

type blockAction struct {
	Status int    `json:"status"`
	Body   string `json:"body"`
	Type   string `json:"type"`
}

type sideAction struct {
	URL           *replacement      `json:"url"`
	SetHeaders    map[string]string `json:"setHeaders"`
	RemoveHeaders []string          `json:"removeHeaders"`
	Body          []replacement     `json:"body"`
	Status        int               `json:"status"`
}

func (s *sideAction) compile() error {
	if s == nil {
		return nil
	}
	if s.URL != nil {
		if e := s.URL.compile(); e != nil {
			return e
		}
	}
	for i := range s.Body {
		if e := s.Body[i].compile(); e != nil {
			return e
		}
	}
	return nil
}

func (s *sideAction) editsBody() bool { return s != nil && len(s.Body) > 0 }

type rewriteRule struct {
	Name     string       `json:"name"`
	Disabled bool         `json:"disabled"`
	UIDs     []int64      `json:"uids"`
	Hosts    []string     `json:"hosts"`
	Methods  []string     `json:"methods"`
	URLs     []string     `json:"urls"`
	Block    *blockAction `json:"block"`
	Request  *sideAction  `json:"request"`
	Response *sideAction  `json:"response"`

	urlRes []*regexp.Regexp
}

func (r *rewriteRule) compile() error {
	if e := r.Request.compile(); e != nil {
		return e
	}
	if e := r.Response.compile(); e != nil {
		return e
	}
	r.urlRes = r.urlRes[:0]
	for _, pattern := range r.URLs {
		re, e := regexp.Compile(pattern)
		if e != nil {
			return e
		}
		r.urlRes = append(r.urlRes, re)
	}
	return nil
}

func (r *rewriteRule) uidMatches(uid int64) bool {
	if len(r.UIDs) == 0 {
		return true
	}
	for _, v := range r.UIDs {
		if v == uid {
			return true
		}
	}
	return false
}

func hostMatches(patterns []string, host string) bool {
	if len(patterns) == 0 {
		return true
	}
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	if i := strings.LastIndex(host, ":"); i > 0 && !strings.Contains(host, "]") {
		host = host[:i]
	}
	for _, raw := range patterns {
		p := strings.ToLower(strings.TrimSpace(raw))
		if p == "" {
			continue
		}
		if strings.HasPrefix(p, "*.") {
			if strings.HasSuffix(host, p[1:]) {
				return true
			}
			continue
		}
		if p == host {
			return true
		}
	}
	return false
}

func (r *rewriteRule) matches(uid int64, host, method, fullURL string) bool {
	if r.Disabled {
		return false
	}
	if !r.uidMatches(uid) || !hostMatches(r.Hosts, host) {
		return false
	}
	if len(r.Methods) > 0 {
		ok := false
		for _, m := range r.Methods {
			if strings.EqualFold(m, method) {
				ok = true
				break
			}
		}
		if !ok {
			return false
		}
	}
	if len(r.urlRes) == 0 {
		return len(r.URLs) == 0
	}
	for _, re := range r.urlRes {
		if re.MatchString(fullURL) {
			return true
		}
	}
	return false
}

type ruleSet struct {
	Mode  string         `json:"mode"`
	Rules []*rewriteRule `json:"rules"`

	path  string
	mtime time.Time
}

func (s *ruleSet) modeWithEnv() string {
	if mode := os.Getenv("TPCAPTURE_REWRITE_MODE"); mode == "record" || mode == "matched" || mode == "rewrite-only" {
		return mode
	}
	if s == nil {
		return "record"
	}
	return s.Mode
}

func (s *ruleSet) active() bool {
	if s == nil {
		return false
	}
	for _, r := range s.Rules {
		if !r.Disabled {
			return true
		}
	}
	return false
}

func (s *ruleSet) passthrough(uid int64, host string) bool {
	if s.modeWithEnv() == "record" || !s.active() {
		return false
	}
	for _, r := range s.Rules {
		if r.Disabled {
			continue
		}
		if r.uidMatches(uid) && hostMatches(r.Hosts, host) {
			return false
		}
	}
	return true
}

type plan struct {
	rules    []*rewriteRule
	block    *blockAction
	reqBody  bool
	respBody bool
}

func (p plan) matched() bool { return len(p.rules) > 0 }

func (s *ruleSet) evaluate(uid int64, host, method, fullURL string) plan {
	var out plan
	if s == nil {
		return out
	}
	for _, r := range s.Rules {
		if !r.matches(uid, host, method, fullURL) {
			continue
		}
		out.rules = append(out.rules, r)
		if r.Block != nil && out.block == nil {
			out.block = r.Block
		}
		if r.Request.editsBody() {
			out.reqBody = true
		}
		if r.Response.editsBody() {
			out.respBody = true
		}
	}
	return out
}

func (p plan) rewriteURL(req *http.Request) {
	target := req.URL.RequestURI()
	for _, r := range p.rules {
		if r.Request == nil || r.Request.URL == nil {
			continue
		}
		target = r.Request.URL.apply(target)
	}
	if target == req.URL.RequestURI() {
		return
	}
	u, e := url.Parse(target)
	if e != nil {
		return
	}
	req.URL.Path = u.Path
	req.URL.RawPath = u.RawPath
	req.URL.RawQuery = u.RawQuery
}

func applyHeaders(h http.Header, set map[string]string, remove []string) {
	for _, k := range remove {
		h.Del(k)
	}
	for k, v := range set {
		h.Set(k, v)
	}
}

func (p plan) rewriteRequest(req *http.Request) {
	p.rewriteURL(req)
	for _, r := range p.rules {
		if r.Request == nil {
			continue
		}
		applyHeaders(req.Header, r.Request.SetHeaders, r.Request.RemoveHeaders)
	}
}

func (p plan) rewriteResponse(resp *http.Response) {
	for _, r := range p.rules {
		if r.Response == nil {
			continue
		}
		if r.Response.Status > 0 {
			resp.StatusCode = r.Response.Status
			resp.Status = http.StatusText(r.Response.Status)
			if resp.Status == "" {
				resp.Status = "Rewritten"
			}
		}
		applyHeaders(resp.Header, r.Response.SetHeaders, r.Response.RemoveHeaders)
	}
}

func replaceBody(body []byte, edits []replacement) []byte {
	text := string(body)
	for _, r := range edits {
		text = r.apply(text)
	}
	return []byte(text)
}

func (p plan) requestEdits() []replacement {
	var out []replacement
	for _, r := range p.rules {
		if r.Request != nil {
			out = append(out, r.Request.Body...)
		}
	}
	return out
}

func (p plan) responseEdits() []replacement {
	var out []replacement
	for _, r := range p.rules {
		if r.Response != nil {
			out = append(out, r.Response.Body...)
		}
	}
	return out
}

func decodeEncoded(data []byte, encoding string) ([]byte, bool) {
	switch strings.ToLower(strings.TrimSpace(strings.Split(encoding, ",")[0])) {
	case "", "identity":
		return data, true
	case "gzip", "x-gzip":
		r, e := gzip.NewReader(bytes.NewReader(data))
		if e != nil {
			return nil, false
		}
		defer r.Close()
		out, e := io.ReadAll(io.LimitReader(r, rewriteBodyLimit))
		if e != nil {
			return nil, false
		}
		return out, true
	case "deflate":
		out, e := io.ReadAll(io.LimitReader(flate.NewReader(bytes.NewReader(data)), rewriteBodyLimit))
		if e != nil {
			out2, e2 := io.ReadAll(io.LimitReader(flate.NewReader(bytes.NewReader(data[2:])), rewriteBodyLimit))
			if e2 != nil {
				return nil, false
			}
			return out2, true
		}
		return out, true
	case "br":
		out, e := io.ReadAll(io.LimitReader(brotli.NewReader(bytes.NewReader(data)), rewriteBodyLimit))
		if e != nil {
			return nil, false
		}
		return out, true
	default:
		return nil, false
	}
}

var ruleEngine atomic.Pointer[ruleSet]
var ruleLoad struct {
	sync.Mutex
	checked time.Time
}

func rewritePath(dir string) string { return dir + "/rewrite.json" }

func loadRules(dir string, force bool) *ruleSet {
	ruleLoad.Lock()
	defer ruleLoad.Unlock()
	if !force && time.Since(ruleLoad.checked) < time.Second {
		return ruleEngine.Load()
	}
	ruleLoad.checked = time.Now()
	path := rewritePath(dir)
	info, e := os.Stat(path)
	if e != nil {
		ruleEngine.Store(nil)
		return nil
	}
	if cur := ruleEngine.Load(); cur != nil && cur.path == path && cur.mtime.Equal(info.ModTime()) {
		return cur
	}
	data, e := os.ReadFile(path)
	if e != nil {
		logger.Printf("rewrite rules: %v", e)
		return ruleEngine.Load()
	}
	set := &ruleSet{Mode: "record"}
	if e = json.Unmarshal(data, set); e != nil {
		logger.Printf("rewrite rules: %v", e)
		return ruleEngine.Load()
	}
	switch set.Mode {
	case "record", "matched", "rewrite-only":
	case "":
		set.Mode = "record"
	default:
		logger.Printf("rewrite rules: unknown mode %q", set.Mode)
		return ruleEngine.Load()
	}
	for i, r := range set.Rules {
		if r == nil {
			continue
		}
		if e = r.compile(); e != nil {
			logger.Printf("rewrite rule %d (%s): %v", i, r.Name, e)
			return ruleEngine.Load()
		}
	}
	set.path = path
	set.mtime = info.ModTime()
	ruleEngine.Store(set)
	return set
}

var runDir string

func rewriteMode() string { return loadRules(runDir, false).modeWithEnv() }

func shouldRecord(matched bool) bool {
	mode := rewriteMode()
	if mode == "rewrite-only" {
		return false
	}
	if mode == "matched" {
		return matched
	}
	return true
}

func readRuleFile(dir string) ([]byte, error) {
	data, e := os.ReadFile(rewritePath(dir))
	if e != nil {
		if os.IsNotExist(e) {
			return []byte(`{"mode":"record","rules":[]}`), nil
		}
		return nil, e
	}
	return data, nil
}

func parseRuleSet(raw []byte) (*ruleSet, error) {
	set := &ruleSet{Mode: "record"}
	if e := json.Unmarshal(raw, set); e != nil {
		return nil, fmt.Errorf("JSON 无法解析：%v", e)
	}
	switch set.Mode {
	case "record", "matched", "rewrite-only":
	case "":
		set.Mode = "record"
	default:
		return nil, fmt.Errorf("未知模式：%s", set.Mode)
	}
	for i, r := range set.Rules {
		if r == nil {
			return nil, fmt.Errorf("第 %d 条规则为空", i+1)
		}
		if e := r.compile(); e != nil {
			return nil, fmt.Errorf("第 %d 条规则%s：%v", i+1, r.Name, e)
		}
		if r.Block != nil && (r.Block.Status < 200 || r.Block.Status > 599) {
			return nil, fmt.Errorf("第 %d 条规则%s：拦截状态码需在 200-599", i+1, r.Name)
		}
	}
	return set, nil
}

func saveRuleFile(dir string, raw []byte) error {
	set, e := parseRuleSet(raw)
	if e != nil {
		return e
	}
	encoded, e := json.MarshalIndent(set, "", "  ")
	if e != nil {
		return e
	}
	if e = os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	tmp := rewritePath(dir) + ".tmp"
	if e = os.WriteFile(tmp, append(encoded, '\n'), 0600); e != nil {
		return e
	}
	if e = os.Rename(tmp, rewritePath(dir)); e != nil {
		os.Remove(tmp)
		return e
	}
	loadRules(dir, true)
	return nil
}
