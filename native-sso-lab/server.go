package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"sync"
)

//go:embed dashboard/index.html
var dashboardHTML []byte

type scenarioInfo struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Requirement string `json:"requirement"`
	Expected    string `json:"expected"`
}

// serve runs the SSO Guardian dashboard: every scenario can be replayed against the live eSignet,
// showing the expected security property, what eSignet answered, and the requests it took.
func serve(env *Env, addr string) error {
	var mu sync.Mutex // one run at a time: scenarios share the eSignet under test
	mux := http.NewServeMux()

	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(dashboardHTML)
	})
	mux.HandleFunc("GET /api/scenarios", func(w http.ResponseWriter, _ *http.Request) {
		var out []scenarioInfo
		for _, s := range scenarios() {
			out = append(out, scenarioInfo{s.id, s.name, s.requirement, s.expected})
		}
		writeJSON(w, http.StatusOK, out)
	})
	mux.HandleFunc("POST /api/run", func(w http.ResponseWriter, r *http.Request) {
		if !mu.TryLock() {
			writeJSON(w, http.StatusConflict, map[string]string{"error": "a run is already in progress"})
			return
		}
		defer mu.Unlock()
		lab, err := setupLab(env)
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]string{"error": "setup failed: " + err.Error()})
			return
		}
		target := r.URL.Query().Get("id")
		var results []Result
		for _, s := range scenarios() {
			prerequisite := s.id == "H1" || s.id == "H2"
			if target != "" && target != "all" && !prerequisite && s.id != target {
				continue
			}
			res := runOne(lab, s)
			res.Setup = target != "" && target != "all" && s.id != target
			results = append(results, res)
			if s.id == "H1" && !res.Pass {
				break
			}
			if s.id == target {
				break
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{"results": results})
	})
	mux.HandleFunc("GET /api/mutation", func(w http.ResponseWriter, _ *http.Request) {
		raw, err := os.ReadFile("mutation-report.json")
		if err != nil {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "no mutation report yet"})
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(raw)
	})
	mux.HandleFunc("POST /api/mutation", func(w http.ResponseWriter, _ *http.Request) {
		if !mu.TryLock() {
			writeJSON(w, http.StatusConflict, map[string]string{"error": "a run is already in progress"})
			return
		}
		defer mu.Unlock()
		out, err := exec.Command("python3", "mutation/mutate.py", "--report", "mutation-report.json").CombinedOutput()
		if err != nil && len(out) == 0 {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		raw, _ := os.ReadFile("mutation-report.json")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(raw)
	})

	fmt.Printf("SSO Guardian dashboard on http://%s (eSignet %s)\n", addr, env.BaseURL)
	return http.ListenAndServe(addr, mux)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
