package main

import (
	"flag"
	"fmt"
	"os"
)

func main() {
	base := flag.String("base", "http://localhost:8080", "eSignet base URL (must equal its issuer)")
	mock := flag.String("mock", "http://localhost:8082", "mock identity system URL")
	verbose := flag.Bool("v", false, "print every HTTP exchange")
	report := flag.String("report", "", "write the scenario results as JSON to this file")
	flag.Parse()

	env := newEnv(*base, *mock, *verbose)
	if flag.Arg(0) == "serve" {
		addr := flag.Arg(1)
		if addr == "" {
			addr = "localhost:8099"
		}
		if err := serve(env, addr); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	lab, err := setupLab(env)
	if err != nil {
		fmt.Fprintln(os.Stderr, "setup failed:", err)
		os.Exit(1)
	}

	mode := flag.Arg(0)
	if mode == "" {
		mode = "all"
	}
	results := runScenarios(lab, mode)
	printResults(results)
	if *report != "" {
		if err := writeReport(*report, lab, results); err != nil {
			fmt.Fprintln(os.Stderr, "write report:", err)
		}
	}
	for _, r := range results {
		if !r.Pass {
			os.Exit(1)
		}
	}
}
