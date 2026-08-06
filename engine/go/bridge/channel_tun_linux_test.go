//go:build linux

package bridge

import (
	"testing"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/header"
)

func TestNetworkProtocolForPacket(t *testing.T) {
	tests := []struct {
		name   string
		packet []byte
		want   uint32
		ok     bool
	}{
		{name: "ipv4", packet: []byte{0x45}, want: uint32(header.IPv4ProtocolNumber), ok: true},
		{name: "ipv6", packet: []byte{0x60}, want: uint32(header.IPv6ProtocolNumber), ok: true},
		{name: "empty", packet: nil, ok: false},
		{name: "unknown", packet: []byte{0x10}, ok: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, ok := networkProtocolForPacket(test.packet)
			if ok != test.ok {
				t.Fatalf("networkProtocolForPacket(%x) ok=%v, want %v", test.packet, ok, test.ok)
			}
			if ok && uint32(got) != test.want {
				t.Fatalf("networkProtocolForPacket(%x)=%d, want %d", test.packet, got, test.want)
			}
		})
	}
}

func TestChannelTunCloseIsIdempotentAndClosesTransferredDescriptor(t *testing.T) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])

	dev, err := openChannelTun(fds[0], 1500)
	if err != nil {
		unix.Close(fds[0])
		t.Fatal(err)
	}
	dev.Close()
	dev.Close()

	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err == nil {
		t.Fatal("transferred descriptor remains open after channelTun.Close")
	}
}

func TestRepeatedChannelTunLifecycleDoesNotRetainDescriptors(t *testing.T) {
	const sessions = 8
	for i := 0; i < sessions; i++ {
		fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
		if err != nil {
			t.Fatal(err)
		}
		dev, err := openChannelTun(fds[0], 1500)
		if err != nil {
			unix.Close(fds[0])
			unix.Close(fds[1])
			t.Fatal(err)
		}
		dev.Close()
		unix.Close(fds[1])
		if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err == nil {
			t.Fatalf("session %d retained transferred descriptor", i+1)
		}
	}
}
