package bridge

import (
	"context"
	"net"
	"sync"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

const flowDrainTimeout = 3 * time.Second

type trackedFlow interface {
	requestClose()
}

type flowTracker struct {
	mu    sync.Mutex
	flows map[trackedFlow]struct{}
}

func newFlowTracker() *flowTracker {
	return &flowTracker{flows: make(map[trackedFlow]struct{})}
}

func (t *flowTracker) add(flow trackedFlow) {
	t.mu.Lock()
	t.flows[flow] = struct{}{}
	t.mu.Unlock()
}

func (t *flowTracker) remove(flow trackedFlow) {
	t.mu.Lock()
	delete(t.flows, flow)
	t.mu.Unlock()
}

func (t *flowTracker) count() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.flows)
}

func (t *flowTracker) requestCloseAll() {
	t.mu.Lock()
	flows := make([]trackedFlow, 0, len(t.flows))
	for flow := range t.flows {
		flows = append(flows, flow)
	}
	t.mu.Unlock()

	for _, flow := range flows {
		flow.requestClose()
	}
}

func (t *flowTracker) waitEmpty(timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		if t.count() == 0 {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(10 * time.Millisecond)
	}
}

type trackedDialer struct {
	delegate proxy.Dialer
	tracker  *flowTracker
}

func (d *trackedDialer) DialContext(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
	conn, err := d.delegate.DialContext(ctx, metadata)
	if err != nil {
		return nil, err
	}
	tracked := &trackedNetConn{
		Conn:    conn,
		tracker: d.tracker,
	}
	d.tracker.add(tracked)
	return tracked, nil
}

func (d *trackedDialer) DialUDP(metadata *M.Metadata) (net.PacketConn, error) {
	conn, err := d.delegate.DialUDP(metadata)
	if err != nil {
		return nil, err
	}
	tracked := &trackedPacketConn{
		PacketConn: conn,
		tracker:    d.tracker,
	}
	d.tracker.add(tracked)
	return tracked, nil
}

type trackedNetConn struct {
	net.Conn
	tracker *flowTracker
	once    sync.Once
}

func (c *trackedNetConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { c.tracker.remove(c) })
	return err
}

// requestClose interrupts both directions without marking the worker complete.
// The flow is removed only when tun2socks reaches its deferred wrapper Close.
func (c *trackedNetConn) requestClose() {
	_ = c.Conn.Close()
}

type trackedPacketConn struct {
	net.PacketConn
	tracker *flowTracker
	once    sync.Once
}

func (c *trackedPacketConn) Close() error {
	err := c.PacketConn.Close()
	c.once.Do(func() { c.tracker.remove(c) })
	return err
}

func (c *trackedPacketConn) requestClose() {
	_ = c.PacketConn.Close()
}
