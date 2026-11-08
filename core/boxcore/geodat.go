package boxcore

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"google.golang.org/protobuf/encoding/protowire"
)

// Файлы geosite.dat / geoip.dat в формате Xray (v2fly). sing-box их не читает, поэтому нужные категории
// достаём сами и подставляем в правила списком доменов/подсетей.

// GeoDomains — домены одной категории geosite, разложенные по видам совпадения sing-box.
type GeoDomains struct {
	Full    []string // domain — точное имя
	Suffix  []string // domain_suffix — имя и поддомены
	Keyword []string // domain_keyword — подстрока
	Regex   []string // domain_regex
}

func (g *GeoDomains) empty() bool {
	return len(g.Full)+len(g.Suffix)+len(g.Keyword)+len(g.Regex) == 0
}

type geoCacheKey struct {
	path string
	mod  int64 // время изменения и размер: обновлённый файл читается заново
	size int64
	tag  string
}

var (
	geoMu        sync.Mutex
	geoSiteCache = map[geoCacheKey]*GeoDomains{}
	geoIPCache   = map[geoCacheKey][]string{}
	geoRaw       = map[geoCacheKey][]byte{} // содержимое файлов (tag пустой) — на время одного перевода конфига
)

// geoKey — ключ кэша для файла name в каталоге dir (имя без каталогов).
func geoKey(dir, name, tag string) (geoCacheKey, error) {
	if name == "" || strings.ContainsAny(name, `/\`) || name == "." || name == ".." {
		return geoCacheKey{}, fmt.Errorf("geo: недопустимое имя файла %q", name)
	}
	p := filepath.Join(dir, name)
	st, err := os.Stat(p)
	if err != nil {
		return geoCacheKey{}, fmt.Errorf("geo: %v", err)
	}
	return geoCacheKey{p, st.ModTime().UnixNano(), st.Size(), strings.ToLower(tag)}, nil
}

func geoData(key geoCacheKey) ([]byte, error) {
	fk := key
	fk.tag = ""
	if b, ok := geoRaw[fk]; ok {
		return b, nil
	}
	b, err := os.ReadFile(key.path)
	if err != nil {
		return nil, fmt.Errorf("geo: %v", err)
	}
	geoRaw[fk] = b
	return b, nil
}

// splitTag — "category@attr" → категория и атрибут (атрибут может отсутствовать).
func splitTag(tag string) (string, string) {
	code, attr, _ := strings.Cut(tag, "@")
	return strings.ToUpper(strings.TrimSpace(code)), strings.ToLower(strings.TrimSpace(attr))
}

// LoadGeoSite — категория tag ("ru", "category-ads-all@ads") из файла name в каталоге dir.
func LoadGeoSite(dir, name, tag string) (*GeoDomains, error) {
	geoMu.Lock()
	defer geoMu.Unlock()
	key, err := geoKey(dir, name, tag)
	if err != nil {
		return nil, err
	}
	if d, ok := geoSiteCache[key]; ok {
		return d, nil
	}
	data, err := geoData(key)
	if err != nil {
		return nil, err
	}
	code, attr := splitTag(tag)
	d, err := parseGeoSite(data, code, attr)
	if err != nil {
		return nil, fmt.Errorf("geo %s:%s: %v", name, tag, err)
	}
	geoSiteCache[key] = d
	return d, nil
}

// LoadGeoIP — подсети категории tag из файла name в каталоге dir.
func LoadGeoIP(dir, name, tag string) ([]string, error) {
	geoMu.Lock()
	defer geoMu.Unlock()
	key, err := geoKey(dir, name, tag)
	if err != nil {
		return nil, err
	}
	if c, ok := geoIPCache[key]; ok {
		return c, nil
	}
	data, err := geoData(key)
	if err != nil {
		return nil, err
	}
	code, _ := splitTag(tag)
	c, err := parseGeoIP(data, code)
	if err != nil {
		return nil, fmt.Errorf("geo %s:%s: %v", name, tag, err)
	}
	geoIPCache[key] = c
	return c, nil
}

// releaseGeoFiles — содержимое файлов больше не нужно (категории уже разобраны и лежат в кэше).
func releaseGeoFiles() {
	geoMu.Lock()
	geoRaw = map[geoCacheKey][]byte{}
	geoMu.Unlock()
}

var errNotFound = errors.New("категория не найдена")

// eachEntry — записи списка (поле 1, сообщения) с кодом страны (поле 1 записи); fn получает тело записи.
func eachEntry(data []byte, code string, fn func(entry []byte) error) error {
	found := false
	for len(data) > 0 {
		num, typ, n := protowire.ConsumeTag(data)
		if n < 0 {
			return protowire.ParseError(n)
		}
		data = data[n:]
		if num != 1 || typ != protowire.BytesType {
			n = protowire.ConsumeFieldValue(num, typ, data)
			if n < 0 {
				return protowire.ParseError(n)
			}
			data = data[n:]
			continue
		}
		entry, n := protowire.ConsumeBytes(data)
		if n < 0 {
			return protowire.ParseError(n)
		}
		data = data[n:]
		if strings.EqualFold(entryCode(entry), code) {
			found = true
			if err := fn(entry); err != nil {
				return err
			}
		}
	}
	if !found {
		return errNotFound
	}
	return nil
}

func entryCode(entry []byte) string {
	for len(entry) > 0 {
		num, typ, n := protowire.ConsumeTag(entry)
		if n < 0 {
			return ""
		}
		entry = entry[n:]
		if num == 1 && typ == protowire.BytesType {
			v, n := protowire.ConsumeBytes(entry)
			if n < 0 {
				return ""
			}
			return string(v)
		}
		n = protowire.ConsumeFieldValue(num, typ, entry)
		if n < 0 {
			return ""
		}
		entry = entry[n:]
	}
	return ""
}

// fields — поля сообщения по порядку.
func fields(msg []byte, fn func(num protowire.Number, typ protowire.Type, v []byte, u uint64) error) error {
	for len(msg) > 0 {
		num, typ, n := protowire.ConsumeTag(msg)
		if n < 0 {
			return protowire.ParseError(n)
		}
		msg = msg[n:]
		switch typ {
		case protowire.BytesType:
			v, n := protowire.ConsumeBytes(msg)
			if n < 0 {
				return protowire.ParseError(n)
			}
			msg = msg[n:]
			if err := fn(num, typ, v, 0); err != nil {
				return err
			}
		case protowire.VarintType:
			u, n := protowire.ConsumeVarint(msg)
			if n < 0 {
				return protowire.ParseError(n)
			}
			msg = msg[n:]
			if err := fn(num, typ, nil, u); err != nil {
				return err
			}
		default:
			n = protowire.ConsumeFieldValue(num, typ, msg)
			if n < 0 {
				return protowire.ParseError(n)
			}
			msg = msg[n:]
		}
	}
	return nil
}

// GeoSite { country_code = 1; repeated Domain domain = 2 }
// Domain { Type type = 1 (Plain=0 Regex=1 Domain=2 Full=3); string value = 2; repeated Attribute attribute = 3 }
// Attribute { string key = 1; ... }
func parseGeoSite(data []byte, code, attr string) (*GeoDomains, error) {
	d := &GeoDomains{}
	err := eachEntry(data, code, func(entry []byte) error {
		return fields(entry, func(num protowire.Number, typ protowire.Type, v []byte, _ uint64) error {
			if num != 2 || typ != protowire.BytesType {
				return nil
			}
			var kind uint64
			var value string
			hasAttr := attr == ""
			err := fields(v, func(num protowire.Number, typ protowire.Type, v []byte, u uint64) error {
				switch {
				case num == 1 && typ == protowire.VarintType:
					kind = u
				case num == 2 && typ == protowire.BytesType:
					value = string(v)
				case num == 3 && typ == protowire.BytesType && !hasAttr:
					return fields(v, func(num protowire.Number, typ protowire.Type, v []byte, _ uint64) error {
						if num == 1 && typ == protowire.BytesType && strings.EqualFold(string(v), attr) {
							hasAttr = true
						}
						return nil
					})
				}
				return nil
			})
			if err != nil || !hasAttr || value == "" {
				return err
			}
			switch kind {
			case 0:
				d.Keyword = append(d.Keyword, value)
			case 1:
				d.Regex = append(d.Regex, value)
			case 2:
				d.Suffix = append(d.Suffix, value)
			case 3:
				d.Full = append(d.Full, value)
			}
			return nil
		})
	})
	return d, err
}

// GeoIP { country_code = 1; repeated CIDR cidr = 2; bool reverse_match = 3 }  CIDR { bytes ip = 1; uint32 prefix = 2 }
func parseGeoIP(data []byte, code string) ([]string, error) {
	var out []string
	err := eachEntry(data, code, func(entry []byte) error {
		return fields(entry, func(num protowire.Number, typ protowire.Type, v []byte, u uint64) error {
			if num == 3 && typ == protowire.VarintType && u != 0 {
				return errors.New("reverse_match не поддерживается")
			}
			if num != 2 || typ != protowire.BytesType {
				return nil
			}
			var ip []byte
			var bits uint64
			if err := fields(v, func(num protowire.Number, typ protowire.Type, v []byte, u uint64) error {
				if num == 1 && typ == protowire.BytesType {
					ip = v
				}
				if num == 2 && typ == protowire.VarintType {
					bits = u
				}
				return nil
			}); err != nil {
				return err
			}
			addr, ok := netip.AddrFromSlice(ip)
			if !ok || int(bits) > addr.BitLen() {
				return nil
			}
			if addr.Is4In6() && bits >= 96 {
				addr, bits = addr.Unmap(), bits-96
			}
			out = append(out, netip.PrefixFrom(addr, int(bits)).Masked().String())
			return nil
		})
	})
	return out, err
}
