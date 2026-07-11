// healthcheck는 Docker HEALTHCHECK 지시어 전용 바이너리다.
//
// distroless/static 런타임 이미지에는 셸도 curl/wget도 없어 `HEALTHCHECK CMD curl ...`
// 형태를 쓸 수 없다. 대신 이 프로그램을 빌드 스테이지에서 정적으로 컴파일해 최종
// 이미지에 함께 넣고, `HEALTHCHECK CMD ["/healthcheck"]`(exec form)로 실행한다.
// 컨테이너 내부 localhost로 /health/live를 호출해 200이면 exit 0(healthy),
// 그 외에는 exit 1(unhealthy)을 반환한다.
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
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}
