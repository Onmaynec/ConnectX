#!/usr/bin/env python3
from pathlib import Path

PATH = Path("engine/go/bridge/session.go")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one occurrence, found {count}: {old!r}")
    text = text.replace(old, new, 1)


replace_once(
    '''type Session struct {
	device device.Device
	stack  *stack.Stack
	tunnel *tunnel.Tunnel
}
''',
    '''type Session struct {
	device device.Device
	stack  *stack.Stack
	tunnel *tunnel.Tunnel
	flows  *flowTracker
}
''',
)
replace_once(
    '''	proxy, err := socks5.New(
		net.JoinHostPort(host, strconv.Itoa(port)),
		username,
		password,
	)
''',
    '''	rawProxy, err := socks5.New(
		net.JoinHostPort(host, strconv.Itoa(port)),
		username,
		password,
	)
''',
)
replace_once(
    '''	manager := statistic.DefaultManager
	manager.ResetStatistic()
	transport := tunnel.New(proxy, manager)
''',
    '''	manager := statistic.DefaultManager
	manager.ResetStatistic()
	flows := newFlowTracker()
	transport := tunnel.New(
		&trackedDialer{
			delegate: rawProxy,
			tracker:  flows,
		},
		manager,
	)
''',
)
replace_once(
    '''	active = &Session{
		device: dev,
		stack:  netstack,
		tunnel: transport,
	}
''',
    '''	active = &Session{
		device: dev,
		stack:  netstack,
		tunnel: transport,
		flows:  flows,
	}
''',
)
replace_once(
    '''	// Closing the device first unblocks the reader owned by gVisor. Waiting on
	// the stack before closing the device can deadlock a real Android TUN stop.
	session.device.Close()
	session.stack.Close()
	session.stack.Wait()
	session.tunnel.Close()
	return nil
''',
    '''	// Closing the device first unblocks the reader owned by gVisor. Active
	// proxy-side connections are explicitly interrupted because upstream
	// Tunnel.Close only cancels the dispatcher and does not wait for per-flow
	// TCP/UDP copy workers.
	session.device.Close()
	session.flows.requestCloseAll()
	session.stack.Close()
	session.stack.Wait()

	// No new flow can be emitted after the stack has stopped. Cancel the
	// dispatcher, interrupt any connection that raced with the first snapshot,
	// and require every tunnel worker to reach its deferred connection Close.
	session.tunnel.Close()
	session.flows.requestCloseAll()
	if !session.flows.waitEmpty(flowDrainTimeout) {
		return fmt.Errorf(
			"timed out draining %d native remote flow(s)",
			session.flows.count(),
		)
	}
	return nil
''',
)
PATH.write_text(text, encoding="utf-8")

TEST = Path("engine/go/bridge/session_test.go")
test_text = TEST.read_text(encoding="utf-8")
test_text = test_text.replace(
    't.Fatalf("Version() = %q, expected alpha.5 release prefix", got)',
    't.Fatalf("Version() = %q, expected alpha.7 release prefix", got)',
)
TEST.write_text(test_text, encoding="utf-8")
