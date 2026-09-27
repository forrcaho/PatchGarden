// The always-on recorder, on the host: a ring, a writer thread and a circular file, with no
// Android in it -- which is why it can be tested here at all.

#include "recorder.h"
#include "test_support.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#include <unistd.h>

using testing::check;

namespace {

struct Header {
    char magic[8];
    int32_t sampleRate;
    int32_t channels;
    int64_t capacity;
    int64_t window;
    int64_t total;
};

std::string tempPath(const char *name) {
    const char *dir = std::getenv("TMPDIR");
    std::string path = std::string(dir != nullptr ? dir : "/tmp") + "/patchgarden-" + name + "-" +
                       std::to_string(::getpid()) + ".raw";
    std::remove(path.c_str());
    return path;
}

Header readHeader(const std::string &path) {
    Header h{};
    std::ifstream in(path, std::ios::binary);
    in.read(reinterpret_cast<char *>(&h), sizeof h);
    return h;
}

/** Frame f, as the file holds it: left and right, each saying which frame it is. */
std::pair<float, float> frameAt(const std::string &path, const Header &h, int64_t f) {
    std::ifstream in(path, std::ios::binary);
    in.seekg(Recorder::kHeaderBytes + (f % h.capacity) * 8);
    float lr[2] = {-1.0f, -1.0f};
    in.read(reinterpret_cast<char *>(lr), sizeof lr);
    return {lr[0], lr[1]};
}

/** Frames [from, from + count), each frame's samples its own number and its negative. */
void feed(Recorder &r, int64_t from, int64_t count, int32_t block = 32) {
    std::vector<float> buffer(static_cast<std::size_t>(block) * 2);
    for (int64_t f = from; f < from + count; f += block) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(block, from + count - f));
        for (int32_t i = 0; i < n; ++i) {
            buffer[static_cast<std::size_t>(i) * 2] = static_cast<float>(f + i);
            buffer[static_cast<std::size_t>(i) * 2 + 1] = -static_cast<float>(f + i);
        }
        r.write(buffer.data(), n);
        // Paced like a stream, so the ring is drained rather than overrun: at most 128
        // frames a millisecond, against a writer that wakes every ten and a ring of 4096.
        if ((f / block) % 4 == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

bool windowHolds(const std::string &path, int64_t from, int64_t to) {
    const Header h = readHeader(path);
    for (int64_t f = from; f < to; ++f) {
        const auto [l, r] = frameAt(path, h, f);
        if (l != static_cast<float>(f) || r != -static_cast<float>(f)) {
            std::printf("  frame %lld reads %g %g\n", static_cast<long long>(f), l, r);
            return false;
        }
    }
    return true;
}

void theWindowIsTheLastFramesInOrder() {
    std::printf("the window is the last frames, in order, after the file has wrapped\n");
    const std::string path = tempPath("window");
    Recorder r(4096);
    check(r.open(path, 1000, 1000, 200), "opens");
    feed(r, 0, 5000);
    r.close();

    const Header h = readHeader(path);
    check(std::memcmp(h.magic, "PGREC01", 8) == 0, "the header says what the file is");
    check(h.sampleRate == 1000 && h.channels == 2, "and its rate and channels");
    check(h.capacity == 1200 && h.window == 1000, "and its shape: the window and the margin");
    check(h.total == 5000, "every frame was written, got " + std::to_string(h.total));
    check(r.droppedFrames() == 0, "none dropped at a stream's pace");
    check(windowHolds(path, 4000, 5000), "the last window's worth reads back in order");
    std::remove(path.c_str());
}

void reopeningCarriesOnAndAnotherShapeStartsOver() {
    std::printf("reopening carries on where it stopped; another shape starts over\n");
    const std::string path = tempPath("resume");
    Recorder r(4096);
    r.open(path, 1000, 1000, 200);
    feed(r, 0, 700);
    r.close();
    r.open(path, 1000, 1000, 200);
    check(r.totalFrames() == 700, "carried on from 700, got " + std::to_string(r.totalFrames()));
    feed(r, 700, 900);
    r.close();
    check(readHeader(path).total == 1600, "and went on counting");
    check(windowHolds(path, 600, 1600), "across the stop, as if it never happened");

    r.open(path, 1000, 2000, 200);
    check(r.totalFrames() == 0, "a longer window is a new file");
    r.close();
    r.open(path, 48000, 2000, 200);
    check(r.totalFrames() == 0, "so is another rate, whose frames would play at the wrong speed");
    r.close();
    std::remove(path.c_str());
}

void aFullRingDropsAndCountsRatherThanWaiting() {
    std::printf("a full ring drops and counts, rather than waiting\n");
    const std::string path = tempPath("drops");
    Recorder r(64);
    r.open(path, 1000, 1000, 200);
    // More in one go than the ring holds: the audio thread cannot wait for the writer.
    std::vector<float> block(200 * 2, 0.5f);
    r.write(block.data(), 200);
    check(r.droppedFrames() == 200, "the block that does not fit is dropped whole");
    r.close();
    std::remove(path.c_str());
}

void closedItTakesNothing() {
    std::printf("closed, it takes nothing\n");
    Recorder r(64);
    std::vector<float> block(32 * 2, 0.5f);
    r.write(block.data(), 32);
    check(r.totalFrames() == 0 && r.droppedFrames() == 0, "not recorded and not counted as dropped");
    const std::string path = tempPath("closed");
    r.open(path, 1000, 1000, 200);
    r.close();
    r.write(block.data(), 32);
    r.open(path, 1000, 1000, 200);
    r.close();
    check(readHeader(path).total == 0, "nor written into the next file it opens");
    std::remove(path.c_str());
}

} // namespace

int main() {
    theWindowIsTheLastFramesInOrder();
    reopeningCarriesOnAndAnotherShapeStartsOver();
    aFullRingDropsAndCountsRatherThanWaiting();
    closedItTakesNothing();
    return testing::report("recorder");
}
