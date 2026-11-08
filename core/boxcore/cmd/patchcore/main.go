// patchcore — ядро sing-box-extended с правками lite404 из core/boxcore/_singbox: подстановка домена
// из TLS/HTTP в соединение (как Xray sniffing routeOnly=false — без неё сервер получает IP вместо домена).
//
// Копирует модуль ядра из кэша Go в core/boxcore/_singbox/build/sing-box (вне git), кладёт поверх
// исправленные файлы и пишет рядом копию go.mod модуля (boxcore.mod / desktop.mod), где ядро заменено
// этой копией. Печатает путь к ней — для `go build -modfile=…` (go.mod в репозитории не меняется).
//
//	cd core/boxcore && go run ./cmd/patchcore            → …/_singbox/build/boxcore.mod
//	cd core/boxcore && go run ./cmd/patchcore ../../desktop → …/_singbox/build/desktop.mod
//
// Сборки: build-aar.sh, desktop/pack. Тесты: go test -modfile=$(go run ./cmd/patchcore) …
// Без правки ядро не примет конфиг (неизвестное поле override_destination) — тихо собрать без неё нельзя.
//
// Обновили sing-box-extended — исходные файлы изменились, программа остановится: перенести правки
// (комментарии «lite404:» в _singbox) на новые файлы и обновить хеши ниже.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const module = "github.com/sagernet/sing-box"

// файл ядра → SHA-256 исходника, на котором сделана правка
var files = map[string]string{
	"option/rule_action.go":     "229f00611bf88d47e92d165bb4b136a3474de40de25912817975b4c1936e5267",
	"route/rule/rule_action.go": "df316ac1c1f1aeeee06666e0b63f4df71e72c8237e73f91d2a9a935315c0ef63",
	"route/route.go":            "3153f2e804a60c8e10c8cec32fb44827cb5404d1735224c39ab395bdcd3dfcb5",
	"route/conn.go":             "5a48c8ecacd69add2382a392129df6baca45b867b1c6ef8d4a2b95b91f5079fc",
}

func main() {
	target := "."
	if len(os.Args) > 1 {
		target = os.Args[1]
	}
	path, err := run(target)
	if err != nil {
		fmt.Fprintln(os.Stderr, "patchcore:", err)
		os.Exit(1)
	}
	fmt.Print(path)
}

func run(target string) (string, error) {
	patch, err := filepath.Abs("_singbox")
	if err != nil {
		return "", err
	}
	target, err = filepath.Abs(target)
	if err != nil {
		return "", err
	}
	cmd := exec.Command("go", "list", "-m", "-f", "{{.Dir}}", module)
	cmd.Dir = target
	cmd.Env = append(os.Environ(), "GOFLAGS=")
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("go list: %v", err)
	}
	src := strings.TrimSpace(string(out))
	if src == "" {
		return "", fmt.Errorf("нет исходников ядра (go mod download)")
	}
	for name, want := range files {
		data, err := os.ReadFile(filepath.Join(src, filepath.FromSlash(name)))
		if err != nil {
			return "", err
		}
		sum := sha256.Sum256(data)
		if got := hex.EncodeToString(sum[:]); got != want {
			return "", fmt.Errorf("%s в ядре изменился (%s) — перенести правки lite404 из _singbox и обновить хеш", name, got)
		}
	}

	// копия модуля + исправленные файлы; метка — исходник и правки, с которыми копия сделана
	build := filepath.Join(patch, "build")
	dst := filepath.Join(build, "sing-box")
	h := sha256.New()
	h.Write([]byte(src))
	for _, name := range sortedNames() {
		data, err := os.ReadFile(filepath.Join(patch, filepath.FromSlash(name)))
		if err != nil {
			return "", err
		}
		h.Write([]byte(name))
		h.Write(data)
	}
	stamp := hex.EncodeToString(h.Sum(nil))
	stampFile := filepath.Join(build, "sing-box.stamp")
	if old, _ := os.ReadFile(stampFile); string(old) != stamp {
		if err := copyPatched(src, dst, patch); err != nil {
			return "", err
		}
		if err := os.WriteFile(stampFile, []byte(stamp), 0o644); err != nil {
			return "", err
		}
	}
	return writeModfile(target, build, dst)
}

func sortedNames() []string {
	var names []string
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func copyPatched(src, dst, patch string) error {
	if err := os.RemoveAll(dst); err != nil {
		return err
	}
	err := filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		if d.IsDir() {
			return os.MkdirAll(filepath.Join(dst, rel), 0o755)
		}
		data, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		return os.WriteFile(filepath.Join(dst, rel), data, 0o644)
	})
	if err != nil {
		return err
	}
	for name := range files {
		data, err := os.ReadFile(filepath.Join(patch, filepath.FromSlash(name)))
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dst, filepath.FromSlash(name)), data, 0o644); err != nil {
			return err
		}
	}
	return nil
}

// writeModfile — go.mod модуля-сборщика: ядро → копия, относительные replace → абсолютные пути
func writeModfile(target, build, dst string) (string, error) {
	mod, err := os.ReadFile(filepath.Join(target, "go.mod"))
	if err != nil {
		return "", err
	}
	sum, err := os.ReadFile(filepath.Join(target, "go.sum"))
	if err != nil {
		return "", err
	}
	text := string(mod)
	re := regexp.MustCompile(`(?m)^replace ` + regexp.QuoteMeta(module) + ` => .*$`)
	if !re.MatchString(text) {
		return "", fmt.Errorf("в %s/go.mod нет replace %s", target, module)
	}
	text = re.ReplaceAllLiteralString(text, "replace "+module+" => "+filepath.ToSlash(dst))
	rel := regexp.MustCompile(`(?m)^(replace \S+ => )(\.\.?/\S*)\s*$`)
	text = rel.ReplaceAllStringFunc(text, func(line string) string {
		m := rel.FindStringSubmatch(line)
		return m[1] + filepath.ToSlash(filepath.Join(target, filepath.FromSlash(m[2])))
	})
	name := filepath.Base(target)
	if name == "." || name == string(filepath.Separator) {
		name = "main"
	}
	modfile := filepath.Join(build, name+".mod")
	if err := os.WriteFile(modfile, []byte(text), 0o644); err != nil {
		return "", err
	}
	if err := os.WriteFile(strings.TrimSuffix(modfile, ".mod")+".sum", sum, 0o644); err != nil {
		return "", err
	}
	return modfile, nil
}
