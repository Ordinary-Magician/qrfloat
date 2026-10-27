package main

import (
	"embed"
	"fmt"
	"net"
	"net/http"
	"os/exec"
	"runtime"
	"time"
)

//go:embed index.html
var content embed.FS

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		b, err := content.ReadFile("index.html")
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(b)
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Println("启动失败:", err)
		return
	}
	port := ln.Addr().(*net.TCPAddr).Port
	url := fmt.Sprintf("http://127.0.0.1:%d/", port)

	fmt.Println("========================================")
	fmt.Println("  码传 · 电脑接收端 已启动")
	fmt.Println("  浏览器会自动打开；若没打开，请手动访问：")
	fmt.Println("  " + url)
	fmt.Println("  关闭此窗口即退出程序")
	fmt.Println("========================================")

	go func() {
		time.Sleep(400 * time.Millisecond)
		openBrowser(url)
	}()

	if err := http.Serve(ln, nil); err != nil {
		fmt.Println("服务结束:", err)
	}
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}
