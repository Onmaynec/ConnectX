package bridge

import (
	"net"
	"testing"
	"time"
)

func TestTrackedNetConnNormalCloseRemovesFlow(t *testing.T) {
	tracker := newFlowTracker()
	client, server := net.Pipe()
	defer server.Close()

	tracked := &trackedNetConn{Conn: client, tracker: tracker}
	tracker.add(tracked)
	if got := tracker.count(); got != 1 {
		t.Fatalf("flow count = %d, want 1", got)
	}

	_ = tracked.Close()
	if !tracker.waitEmpty(100 * time.Millisecond) {
		t.Fatalf("normal Close left %d tracked flow(s)", tracker.count())
	}
}

func TestShutdownRequestWaitsForTunnelWorkerClose(t *testing.T) {
	tracker := newFlowTracker()
	client, server := net.Pipe()
	defer server.Close()

	tracked := &trackedNetConn{Conn: client, tracker: tracker}
	tracker.add(tracked)
	tracker.requestCloseAll()

	if got := tracker.count(); got != 1 {
		t.Fatalf("shutdown request removed flow before worker exit: count=%d", got)
	}
	if tracker.waitEmpty(20 * time.Millisecond) {
		t.Fatal("tracker reported drained before tunnel worker called Close")
	}

	_ = tracked.Close()
	if !tracker.waitEmpty(100 * time.Millisecond) {
		t.Fatalf("worker Close left %d tracked flow(s)", tracker.count())
	}
}

func TestRequestCloseAllInterruptsRemoteSocket(t *testing.T) {
	tracker := newFlowTracker()
	client, server := net.Pipe()
	defer server.Close()

	tracked := &trackedNetConn{Conn: client, tracker: tracker}
	tracker.add(tracked)
	tracker.requestCloseAll()

	if _, err := tracked.Write([]byte("data")); err == nil {
		t.Fatal("remote socket remained writable after shutdown request")
	}
	_ = tracked.Close()
}
