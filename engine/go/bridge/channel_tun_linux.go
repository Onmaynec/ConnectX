//go:build linux

package bridge

import (
	"context"
	"fmt"
	"strconv"
	"sync"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/xjasonlyu/tun2socks/v2/core/device"
)

const (
	channelTunQueueSize    = 256
	channelTunPollMillis   = 100
	channelTunPacketBuffer = 65535
)

// channelTun keeps all kernel descriptor ownership inside ConnectX. Unlike the
// generic gVisor fdbased endpoint, it does not allocate persistent eventfd,
// epoll or processor-manager descriptors for every recreated evidence session.
// Two bounded workers move raw IP packets between the Android TUN descriptor
// and a pure-Go channel endpoint.
type channelTun struct {
	*channel.Endpoint
	fd int

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
	once   sync.Once
}

func openChannelTun(fd int, mtu uint32) (device.Device, error) {
	if fd < 0 {
		return nil, fmt.Errorf("invalid TUN descriptor: %d", fd)
	}
	if err := unix.SetNonblock(fd, true); err != nil {
		return nil, fmt.Errorf("set TUN descriptor nonblocking: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	dev := &channelTun{
		Endpoint: channel.New(channelTunQueueSize, mtu, ""),
		fd:       fd,
		ctx:      ctx,
		cancel:   cancel,
	}
	dev.wg.Add(2)
	go dev.readTunLoop()
	go dev.writeTunLoop()
	return dev, nil
}

func (d *channelTun) Name() string {
	return strconv.Itoa(d.fd)
}

func (*channelTun) Type() string {
	return "fd-channel"
}

func (d *channelTun) Close() {
	d.once.Do(func() {
		// Cancellation and queue closure unblock the writer immediately. The
		// reader uses a bounded poll interval, so wait before closing the numeric
		// descriptor to prevent a concurrent goroutine from observing FD reuse.
		d.cancel()
		d.Endpoint.Close()
		d.wg.Wait()
		_ = unix.Close(d.fd)
	})
}

func (d *channelTun) readTunLoop() {
	defer d.wg.Done()
	packet := make([]byte, channelTunPacketBuffer)
	pollFDs := []unix.PollFd{{Fd: int32(d.fd), Events: unix.POLLIN}}

	for {
		if d.ctx.Err() != nil {
			return
		}
		pollFDs[0].Revents = 0
		ready, err := unix.Poll(pollFDs, channelTunPollMillis)
		if err == unix.EINTR {
			continue
		}
		if err != nil || ready == 0 {
			if err != nil {
				return
			}
			continue
		}
		if pollFDs[0].Revents&(unix.POLLERR|unix.POLLHUP|unix.POLLNVAL) != 0 {
			return
		}
		if pollFDs[0].Revents&unix.POLLIN == 0 {
			continue
		}

		n, readErr := unix.Read(d.fd, packet)
		if readErr == unix.EINTR || readErr == unix.EAGAIN || readErr == unix.EWOULDBLOCK {
			continue
		}
		if readErr != nil || n <= 0 {
			return
		}
		protocol, ok := networkProtocolForPacket(packet[:n])
		if !ok {
			continue
		}
		payload := append([]byte(nil), packet[:n]...)
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(payload),
		})
		d.Endpoint.InjectInbound(protocol, pkt)
		pkt.DecRef()
	}
}

func (d *channelTun) writeTunLoop() {
	defer d.wg.Done()
	for {
		pkt := d.Endpoint.ReadContext(d.ctx)
		if pkt == nil {
			if d.ctx.Err() != nil {
				return
			}
			continue
		}
		err := d.writePacket(pkt)
		pkt.DecRef()
		if err != nil {
			return
		}
	}
}

func (d *channelTun) writePacket(pkt *stack.PacketBuffer) error {
	view := pkt.ToView()
	defer view.Release()
	data := view.AsSlice()
	written := 0
	pollFDs := []unix.PollFd{{Fd: int32(d.fd), Events: unix.POLLOUT}}

	for written < len(data) {
		if d.ctx.Err() != nil {
			return d.ctx.Err()
		}
		n, err := unix.Write(d.fd, data[written:])
		if err == unix.EINTR {
			continue
		}
		if err == unix.EAGAIN || err == unix.EWOULDBLOCK {
			pollFDs[0].Revents = 0
			ready, pollErr := unix.Poll(pollFDs, channelTunPollMillis)
			if pollErr == unix.EINTR || ready == 0 {
				continue
			}
			if pollErr != nil {
				return pollErr
			}
			if pollFDs[0].Revents&(unix.POLLERR|unix.POLLHUP|unix.POLLNVAL) != 0 {
				return fmt.Errorf("TUN descriptor became unavailable while writing")
			}
			continue
		}
		if err != nil {
			return err
		}
		if n <= 0 {
			return fmt.Errorf("TUN write made no progress")
		}
		written += n
	}
	return nil
}

func networkProtocolForPacket(packet []byte) (tcpip.NetworkProtocolNumber, bool) {
	if len(packet) == 0 {
		return 0, false
	}
	switch packet[0] >> 4 {
	case header.IPv4Version:
		return header.IPv4ProtocolNumber, true
	case header.IPv6Version:
		return header.IPv6ProtocolNumber, true
	default:
		return 0, false
	}
}

var _ device.Device = (*channelTun)(nil)
