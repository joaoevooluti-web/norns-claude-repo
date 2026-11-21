#include "NornsSpscFloatRing.h"
#include <atomic>
#include <cassert>
#include <thread>

int main() {
    {
        SpscFloatRing<8> ring;
        for (int i = 0; i < 8; ++i) assert(ring.push(static_cast<float>(i)));
        assert(!ring.push(8.0f));
        assert(ring.droppedFrames() == 1);
        assert(ring.acceptedFrames() == 8);
        assert(ring.queuedFrames() == 8);
        float out[4]{};
        assert(ring.read(out, 4, true) == 4);
        for (int i = 0; i < 4; ++i) assert(out[i] == static_cast<float>(i + 4));
        assert(ring.skippedFrames() == 4);
        assert(ring.requestedFrames() == 4);
        assert(ring.read(out, 4) == 0);
        for (float sample : out) assert(sample == 0.0f);
        assert(ring.underrunFrames() == 4);
        assert(ring.requestedFrames() == 8);
        assert(ring.queuedFrames() == 0);
    }
    {
        // Producer runs 1% faster than the consumer. Keep one block queued and
        // trim only the small excess, rather than dropping the entire backlog.
        SpscFloatRing<512> ring;
        float out[100]{};
        for (int cycle = 0; cycle < 200; ++cycle) {
            for (int i = 0; i < 101; ++i) assert(ring.push(static_cast<float>(cycle * 101 + i)));
            assert(ring.readWithBacklogLimit(out, 100, 100) == 100);
            assert(ring.queuedFrames() <= 100);
        }
        assert(ring.requestedFrames() == 20000);
        assert(ring.skippedFrames() <= 100);
        assert(ring.droppedFrames() == 0);
        assert(ring.underrunFrames() == 0);
    }
    {
        // Bursty callback cadence: 80-frame and 120-frame producer bursts average
        // the 100-frame consumer demand. A one-block prefill prevents false startup
        // underruns while proving that bounded backlog preserves sample order.
        SpscFloatRing<512> ring;
        float out[100]{};
        int produced = 0;
        for (int i = 0; i < 100; ++i) assert(ring.push(static_cast<float>(produced++)));
        for (int cycle = 0; cycle < 100; ++cycle) {
            const int burst = (cycle % 2 == 0) ? 80 : 120;
            for (int i = 0; i < burst; ++i) assert(ring.push(static_cast<float>(produced++)));
            assert(ring.readWithBacklogLimit(out, 100, 100) == 100);
            for (int i = 0; i < 100; ++i)
                assert(out[i] == static_cast<float>(cycle * 100 + i));
            assert(ring.queuedFrames() <= 100);
        }
        assert(ring.acceptedFrames() == 10100);
        assert(ring.requestedFrames() == 10000);
        assert(ring.skippedFrames() == 0);
        assert(ring.droppedFrames() == 0);
        assert(ring.underrunFrames() == 0);
    }
    {
        SpscFloatRing<1024> ring;
        constexpr int count = 200000;
        std::atomic<bool> done{false};
        std::thread producer([&] {
            for (int i = 0; i < count;) {
                if (ring.push(static_cast<float>(i))) ++i;
                else std::this_thread::yield();
            }
            done.store(true, std::memory_order_release);
        });
        int expected = 0;
        float block[37]{};
        while (!done.load(std::memory_order_acquire) || expected < count) {
            int read = ring.read(block, 37);
            for (int i = 0; i < read; ++i) assert(block[i] == static_cast<float>(expected++));
            if (read == 0) std::this_thread::yield();
        }
        producer.join();
        assert(expected == count);
    }
    return 0;
}