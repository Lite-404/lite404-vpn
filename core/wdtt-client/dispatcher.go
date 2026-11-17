package main

import (
	"context"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

var pktPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, 2048)
	},
}

func getPktBuf(size int) []byte {
	b := pktPool.Get().([]byte)
	if cap(b) < size {
		b = make([]byte, size)
	}
	return b[:size]
}

func putPktBuf(b []byte) {
	if cap(b) < 2048 {
		return
	}
	pktPool.Put(b[:cap(b)])
}

const (
	returnChBuf = 512
	chunkSize   = 12
)

type WorkerSlot struct {
	ID     int
	SendCh chan []byte
	mu     sync.Mutex
	closed bool
}

func (w *WorkerSlot) enqueue(pkt []byte) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return false
	}
	select {
	case w.SendCh <- pkt:
		return true
	default:
		return false
	}
}

func (w *WorkerSlot) retire() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return
	}
	w.closed = true
	close(w.SendCh)
	for pkt := range w.SendCh {
		putPktBuf(pkt)
	}
}

type Dispatcher struct {
	localConn  net.PacketConn
	clientAddr atomic.Pointer[net.Addr]
	workers    atomic.Pointer[[]*WorkerSlot]
	mu         sync.Mutex
	rrIndex    atomic.Int32
	rrCount    atomic.Int32
	ReturnCh   chan []byte
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	stats      *Stats
}

func NewDispatcher(ctx context.Context, localConn net.PacketConn, stats *Stats) *Dispatcher {
	dctx, dcancel := context.WithCancel(ctx)
	d := &Dispatcher{
		localConn: localConn,
		ReturnCh:  make(chan []byte, returnChBuf),
		ctx:       dctx,
		cancel:    dcancel,
		stats:     stats,
	}

	empty := make([]*WorkerSlot, 0)
	d.workers.Store(&empty)

	d.wg.Add(2)
	go d.readLoop()
	go d.writeLoop()
	return d
}

func (d *Dispatcher) Shutdown() {
	d.cancel()
	d.wg.Wait()
}

func (d *Dispatcher) Register(w *WorkerSlot) {
	d.mu.Lock()
	defer d.mu.Unlock()
	oldWorkers := d.workers.Load()
	newWorkers := make([]*WorkerSlot, len(*oldWorkers)+1)
	copy(newWorkers, *oldWorkers)
	newWorkers[len(*oldWorkers)] = w
	d.workers.Store(&newWorkers)
	d.publishWorkerCount(len(newWorkers))
	log.Printf("[ДИСП] Воркер #%d зарегистрирован (всего: %d)", w.ID, len(newWorkers))
}

func (d *Dispatcher) Unregister(slot *WorkerSlot) {
	// Readers holding an older worker snapshot must also stop routing here.
	slot.retire()
	d.mu.Lock()
	defer d.mu.Unlock()
	oldWorkers := d.workers.Load()
	newWorkers := make([]*WorkerSlot, 0, len(*oldWorkers))
	for _, w := range *oldWorkers {
		if w != slot {
			newWorkers = append(newWorkers, w)
		}
	}
	d.workers.Store(&newWorkers)
	d.publishWorkerCount(len(newWorkers))
	log.Printf("[ДИСП] Воркер #%d отключён (осталось: %d)", slot.ID, len(newWorkers))
}

func (d *Dispatcher) publishWorkerCount(count int) {
	d.stats.ActiveConnections.Store(int32(count))
	if managedClient {
		emitStats(d.stats)
	}
}

func (d *Dispatcher) readLoop() {
	defer d.wg.Done()

	for {
		if err := d.ctx.Err(); err != nil {
			return
		}

		pkt := getPktBuf(2048)

		n, addr, err := d.localConn.ReadFrom(pkt)
		if err != nil {
			putPktBuf(pkt)
			if d.ctx.Err() != nil {
				return
			}
			time.Sleep(10 * time.Millisecond)
			continue
		}
		pkt = pkt[:n]

		d.clientAddr.Store(&addr)
		d.stats.TotalBytesUp.Add(int64(n))

		workersPtr := d.workers.Load()
		if workersPtr == nil || len(*workersPtr) == 0 {
			putPktBuf(pkt)
			continue
		}

		ws := *workersPtr
		nw := len(ws)

		sent := false
		idx := int(d.rrIndex.Load()) % nw

		if ws[idx].enqueue(pkt) {
			sent = true
			count := d.rrCount.Add(1)
			if count >= int32(chunkSize) {
				d.rrIndex.Store(int32((idx + 1) % nw))
				d.rrCount.Store(0)
			}
		} else {

			for i := 1; i < nw; i++ {
				altIdx := (idx + i) % nw
				if ws[altIdx].enqueue(pkt) {
					sent = true
					d.rrIndex.Store(int32(altIdx))
					d.rrCount.Store(1)
				}
				if sent {
					break
				}
			}
		}

		if !sent {

			d.rrIndex.Store(int32((idx + 1) % nw))
			d.rrCount.Store(0)
			putPktBuf(pkt)
		}
	}
}

func (d *Dispatcher) writeLoop() {
	defer d.wg.Done()

	for {
		select {
		case <-d.ctx.Done():
			return
		case pkt := <-d.ReturnCh:
			addrPtr := d.clientAddr.Load()
			if addrPtr == nil {
				putPktBuf(pkt)
				continue
			}
			addr := *addrPtr
			if _, err := d.localConn.WriteTo(pkt, addr); err != nil {
				if d.ctx.Err() != nil {
					putPktBuf(pkt)
					return
				}
			}
			d.stats.TotalBytesDown.Add(int64(len(pkt)))
			putPktBuf(pkt)
		}
	}
}
