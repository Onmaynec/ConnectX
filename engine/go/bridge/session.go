package bridge

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
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

const bridgeReleaseVersion = "0.3.0-alpha.5"

var upstreamCommit = "unknown"

var (
	tcpFlowCount atomic.Uint64
	udpFlowCount atomic.Uint64
)

// Session owns the duplicated Android TUN file descriptor passed to Start.
// The caller must not close that integer after ownership has been transferred.
type Session struct {
	device device.Device
	stack  *stack.Stack
	tunnel *tunnel.Tunnel
}

type countingTransportHandler struct {
	delegate adapter.TransportHandler
}

func (h *countingTransportHandler) HandleTCP(conn adapter.TCPConn) {
	tcpFlowCount.Add(1)
	h.delegate.HandleTCP(conn)
}

func (h *countingTransportHandler) HandleUDP(conn adapter.UDPConn) {
	udpFlowCount.Add(1)
	h.delegate.HandleUDP(conn)
}

var (
	stateMu sync.Mutex
	active  *Session
)

func Version() string {
	return "connectx-go-bridge/" + bridgeReleaseVersion + " upstream/" + upstreamCommit
}

// TransportDiagnostics is deliberately payload-free and exposes only the
// number of TCP/UDP flows delivered by gVisor to the transport handler.
func TransportDiagnostics() string {
	return fmt.Sprintf(
		"tcpFlows=%d,udpFlows=%d",
		tcpFlowCount.Load(),
		udpFlowCount.Load(),
	)
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

	tcpFlowCount.Store(0)
	udpFlowCount.Store(0)
	manager := statistic.DefaultManager
	manager.ResetStatistic()
	transport := tunnel.New(proxy, manager)
	transport.ProcessAsync()
	countingHandler := &countingTransportHandler{delegate: transport}

	netstack, err := core.CreateStack(&core.Config{
		LinkEndpoint:     dev,
		TransportHandler: countingHandler,
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

func Stop() error {
	stateMu.Lock()
	session := active
	active = nil
	stateMu.Unlock()

	if session == nil {
		return nil
	}

	// Closing the device first unblocks the reader owned by gVisor. Waiting on
	// the stack before closing the device can deadlock a real Android TUN stop.
	session.device.Close()
	session.stack.Close()
	session.stack.Wait()
	session.tunnel.Close()
	return nil
}

func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return active != nil
}
