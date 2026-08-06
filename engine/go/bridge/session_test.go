package bridge

import (
	"strings"
	"testing"

	"golang.org/x/sys/unix"
)

func TestVersionContainsReleaseAndPinnedCommit(t *testing.T) {
	previous := upstreamCommit
	upstreamCommit = "test-commit"
	t.Cleanup(func() { upstreamCommit = previous })

	got := Version()
	if !strings.HasPrefix(got, "connectx-go-bridge/0.3.0-alpha.7 upstream/") {
		t.Fatalf("Version() = %q, expected alpha.7 release prefix", got)
	}
	if !strings.Contains(got, "test-commit") {
		t.Fatalf("Version() = %q, expected pinned commit", got)
	}
}

func TestStartRejectsInvalidDescriptor(t *testing.T) {
	code, err := Start(
		-1,
		1500,
		"127.0.0.1",
		1080,
		"connectx",
		"secret",
	)
	if code != CodeInvalidInput || err == nil {
		t.Fatalf("Start() = (%d, %v), expected invalid input", code, err)
	}
}

func TestRejectedStartClosesTransferredDescriptor(t *testing.T) {
	fds := []int{0, 0}
	if err := unix.Pipe(fds); err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fds[1])

	code, err := Start(
		fds[0],
		1500,
		"not-a-loopback-address",
		1080,
		"connectx",
		"secret",
	)
	if code != CodeInvalidInput || err == nil {
		t.Fatalf("Start() = (%d, %v), expected invalid input", code, err)
	}

	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err == nil {
		t.Fatal("transferred descriptor remains open after failed start")
	}
}

func TestStopIsIdempotent(t *testing.T) {
	if err := Stop(); err != nil {
		t.Fatalf("first Stop(): %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("second Stop(): %v", err)
	}
}
