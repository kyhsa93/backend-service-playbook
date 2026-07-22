// healthcheck is a binary dedicated to the Docker HEALTHCHECK directive.
//
// The distroless/static runtime image has neither a shell nor curl/wget, so
// a `HEALTHCHECK CMD curl ...` form cannot be used. Instead, this program is
// statically compiled in the build stage, bundled into the final image, and
// run via `HEALTHCHECK CMD ["/healthcheck"]` (exec form). It calls
// /health/live on localhost inside the container and returns exit 0
// (healthy) on 200, or exit 1 (unhealthy) otherwise.
package main

import (
	"net/http"
	"os"
	"time"
)

func main() {
	client := &http.Client{Timeout: 2 * time.Second}

	resp, err := client.Get("http://localhost:8080/health/live")
	if err != nil {
		os.Exit(1)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}
