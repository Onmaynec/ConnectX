package bridge

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/proxy/socks5"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
)

const (
	CodeOK             = 0
	CodeInvalidInput   = 1
	CodeAlreadyRunning = 2
	CodeProxyInit      = 3
	CodeDeviceInit     = 4
	CodeStackInit      = 5
)

var upstreamCommit = "unknown"

// Session owns the duplicated Android TUN file descriptor passed to Start.
// The caller must not close that integer after ownership has been transferred.
type Session struct {
	device device.Device
	stack  *stack.Stack
	tunnel *tunnel.Tunnel
}

var (
	stateMu sync.Mutex
	active  *Session
)

func Version() string {
	return "connectx-go-bridge/0.2.0-alpha.5 upstream/" + upstreamCommit
}

// Start creates a userspace TCP/IP stack connected to an authenticated local
// SOCKS5 endpoint. Ownership of tunFD is transferred to this function even
// when startup fails, so every return path closes the descriptor exactly once.
func Start(
	tunFD int,
	mtu int,
	host string,
	port int,
	username string,
	password string,
) (int, error) {
	stateMu.Lock()
	defer stateMu.Unlock()

	if tunFD < 0 {
		return CodeInvalidInput, errors.New("invalid TUN file descriptor")
	}

	fdOwnedByBridge := false
	defer func() {
		if !fdOwnedByBridge {
			_ = unix.Close(tunFD)
		}
	}()

	if active != nil {
		return CodeAlreadyRunning, errors.New("native bridge is already running")
	}
	if mtu < 576 || mtu > 65535 {
		return CodeInvalidInput, fmt.Errorf("invalid MTU: %d", mtu)
	}
	if port < 1 || port > 65535 {
		return CodeInvalidInput, fmt.Errorf("invalid SOCKS5 port: %d", port)
	}
	if username == "" || password == "" {
		return CodeInvalidInput, errors.New("empty SOCKS5 credentials")
	}

	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return CodeInvalidInput, errors.New("SOCKS5 endpoint must use a numeric loopback address")
	}

	proxy, err := socks5.New(
		net.JoinHostPort(host, strconv.Itoa(port)),
		username,
		password,
	)
	if err != nil {
		return CodeProxyInit, fmt.Errorf("create SOCKS5 proxy: %w", err)
	}

	dev, err := fdbased.Open(strconv.Itoa(tunFD), uint32(mtu), 0)
	if err != nil {
		return CodeDeviceInit, fmt.Errorf("open TUN fd: %w", err)
	}
	fdOwnedByBridge = true

	manager := statistic.DefaultManager
	manager.ResetStatistic()
	transport := tunnel.New(proxy, manager)
	transport.ProcessAsync()

	netstack, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: transport,
	})
	if err != nil {
		transport.Close()
		dev.Close()
		return CodeStackInit, fmt.Errorf("create gVisor stack: %w", err)
	}

	active = &Session{
		device: dev,
		stack:  netstack,
		tunnel: transport,
	}
	return CodeOK, nil
}

// Stop is serialized with Start and idempotently releases the native stack,
// tunnel workers and duplicated TUN descriptor. The device is closed before
// stack.Wait so a blocking TUN reader cannot keep the stack shutdown alive.
func Stop() error {
	stateMu.Lock()
	defer stateMu.Unlock()

	session := active
	active = nil
	if session == nil {
		return nil
	}

	session.tunnel.Close()
	// Match tun2socks engine shutdown semantics: closing the fd-backed device
	// first interrupts its packet reader before waiting for gVisor workers.
	session.device.Close()
	session.stack.Close()
	session.stack.Wait()
	return nil
}

func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return active != nil
}
