//go:build linux

package bridge

import (
	"fmt"
	"strconv"
	"sync"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	"github.com/xjasonlyu/tun2socks/v2/core/device"
)

// singleProcessorTun is a source-built equivalent of tun2socks' fd device
// with one deliberate lifecycle constraint: evidence traffic is processed by
// exactly one gVisor packet processor. The upstream helper leaves
// ProcessorsPerChannel at zero, which expands to GOMAXPROCS and allocates a
// process-lifetime set of sleeper/poller descriptors for every recreated TUN.
type singleProcessorTun struct {
	stack.LinkEndpoint
	fd   int
	once sync.Once
}

func openSingleProcessorTun(fd int, mtu uint32) (device.Device, error) {
	endpoint, err := fdbased.New(&fdbased.Options{
		FDs:                  []int{fd},
		MTU:                  mtu,
		EthernetHeader:       false,
		ProcessorsPerChannel: 1,
	})
	if err != nil {
		return nil, fmt.Errorf("create single-processor TUN endpoint: %w", err)
	}
	return &singleProcessorTun{
		LinkEndpoint: endpoint,
		fd:           fd,
	}, nil
}

func (d *singleProcessorTun) Name() string {
	return strconv.Itoa(d.fd)
}

func (*singleProcessorTun) Type() string {
	return "fd-single-processor"
}

func (d *singleProcessorTun) Close() {
	d.once.Do(func() {
		_ = unix.Close(d.fd)
		d.LinkEndpoint.Close()
	})
}

var _ device.Device = (*singleProcessorTun)(nil)
