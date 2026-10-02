// The always-on recorder, on the host: a ring, a writer thread and a circular file, with no
// Android in it -- which is why it can be tested here at all.

#include "recorder.h"
#include "test_support.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
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

// ------------------------------------------------------------------ saving a window

/** A window made by the recorder itself: [total] frames, each frame's samples [sample] of it. */
std::string recorded(const char *name, int64_t capacityLessMargin, int64_t margin, int64_t total,
                     float (*sample)(int64_t)) {
    const std::string path = tempPath(name);
    Recorder r(4096);
    r.open(path, 1000, capacityLessMargin, margin);
    std::vector<float> block(64);
    for (int64_t f = 0; f < total; f += 32) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(32, total - f));
        for (int32_t i = 0; i < n; ++i) {
            block[static_cast<std::size_t>(i) * 2] = sample(f + i);
            block[static_cast<std::size_t>(i) * 2 + 1] = -sample(f + i);
        }
        r.write(block.data(), n);
        if ((f / 32) % 4 == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    r.close();
    return path;
}

/** Exports [path] at [depth] into a file, and returns the frames and the file's bytes. */
std::pair<int64_t, std::vector<unsigned char>> exported(const std::string &path, recording::Depth depth) {
    const std::string wav = path + ".wav";
    std::FILE *out = std::fopen(wav.c_str(), "wb");
    recording::Progress progress;
    const int64_t frames = recording::exportWav(path, fileno(out), depth, &progress);
    std::fclose(out);
    std::ifstream in(wav, std::ios::binary);
    std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::remove(wav.c_str());
    if (frames > 0) check(progress.done.load() == frames && progress.total.load() == frames, "progress ends at the end");
    return {frames, bytes};
}

int32_t le32(const std::vector<unsigned char> &b, std::size_t at) {
    return static_cast<int32_t>(b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (static_cast<uint32_t>(b[at + 3]) << 24));
}
int32_t le16(const std::vector<unsigned char> &b, std::size_t at) {
    return static_cast<int16_t>(b[at] | (b[at + 1] << 8));
}
int32_t le24(const std::vector<unsigned char> &b, std::size_t at) {
    const int32_t v = b[at] | (b[at + 1] << 8) | (b[at + 2] << 16);
    return (v & 0x800000) != 0 ? v - 0x1000000 : v;
}
float leFloat(const std::vector<unsigned char> &b, std::size_t at) {
    float f;
    std::memcpy(&f, &b[at], 4);
    return f;
}

void aSaveReadsTheWindowOldestFirstAcrossTheWrap() {
    std::printf("a save reads the window oldest first, across the wrap, as float\n");
    const auto path = recorded("save-float", 1000, 200, 2500, [](int64_t f) { return f / 4096.0f; });
    const auto [frames, b] = exported(path, recording::Depth::Float32);
    check(frames == 1000, "the window's thousand frames, got " + std::to_string(frames));
    check(std::memcmp(b.data(), "RIFF", 4) == 0 && std::memcmp(b.data() + 8, "WAVE", 4) == 0, "a WAV");
    check(le16(b, 20) == 3 && le16(b, 22) == 2 && le32(b, 24) == 1000 && le16(b, 34) == 32,
          "IEEE float, stereo, at the window's rate");
    check(std::memcmp(b.data() + 36, "fact", 4) == 0 && le32(b, 44) == 1000, "with the fact chunk float asks for");
    check(std::memcmp(b.data() + 48, "data", 4) == 0 && le32(b, 52) == 8000, "and its data");
    check(le32(b, 4) == static_cast<int32_t>(b.size()) - 8, "the RIFF size is the file's");
    bool inOrder = true;
    for (int i = 0; i < 1000 && inOrder; ++i) {
        const float f = static_cast<float>(1500 + i);
        inOrder = leFloat(b, 56 + i * 8) == f / 4096.0f && leFloat(b, 60 + i * 8) == -f / 4096.0f;
        if (!inOrder) std::printf("  frame %d\n", 1500 + i);
    }
    check(inOrder, "frames 1500 to 2499, in order, exactly");
    std::remove(path.c_str());
}

void aSaveStartsWhereTheSoundDoesAndKeepsAGap() {
    std::printf("a save starts where the sound does, and keeps a silence in the middle\n");
    const auto path = recorded("save-24", 2000, 0, 1000, [](int64_t f) {
        return (f >= 400 && f < 600) || f >= 800 ? 0.5f : 0.0f;
    });
    const auto [frames, b] = exported(path, recording::Depth::Pcm24);
    check(frames == 600, "from 400 to the end, got " + std::to_string(frames));
    check(le16(b, 20) == 1 && le16(b, 34) == 24 && le32(b, 40) == 600 * 6, "24-bit PCM, sized for 600");
    check(std::abs(le24(b, 44) - 4194304) <= 1 && std::abs(le24(b, 47) + 4194304) <= 1, "half scale, both ways");
    check(le24(b, 44 + 200 * 6) == 0, "the gap in the middle is kept");
    std::remove(path.c_str());
}

void silenceSavesNothingAndAFileThatIsNotAWindowSavesNothingEither() {
    std::printf("silence saves nothing, and a file that is not a window is refused\n");
    const auto path = recorded("save-silent", 2000, 0, 1000, [](int64_t) { return 0.0f; });
    check(exported(path, recording::Depth::Pcm16).first == 0, "only silence: nothing");
    std::remove(path.c_str());
    const std::string junk = tempPath("save-junk");
    std::ofstream(junk) << "hello";
    check(exported(junk, recording::Depth::Pcm16).first == -1, "not a window: refused");
    check(exported(tempPath("save-missing"), recording::Depth::Pcm16).first == -1, "no file: refused");
    std::remove(junk.c_str());
}

void sixteenBitsIsDitheredByAStepAndClipped() {
    std::printf("sixteen bits is dithered by a step at most, and clipped rather than wrapped\n");
    const auto path = recorded("save-16", 2000, 0, 1000, [](int64_t f) { return f < 500 ? 0.25f : 1.5f; });
    const auto [frames, b] = exported(path, recording::Depth::Pcm16);
    check(frames == 1000 && le16(b, 34) == 16, "all of it, at 16 bits");
    bool near = true;
    bool clipped = true;
    bool dithered = false;
    for (int i = 0; i < 500; ++i) {
        const int v = le16(b, 44 + i * 4);
        near = near && std::abs(v - 8192) <= 1;
        dithered = dithered || v != le16(b, 44);
    }
    for (int i = 500; i < 1000; ++i) {
        clipped = clipped && le16(b, 44 + i * 4) >= 32766 && le16(b, 46 + i * 4) <= -32766;
    }
    check(near, "a quarter is within a step of a quarter");
    check(dithered, "and not the same number every time: it is dithered");
    check(clipped, "over full scale is full scale, both ways");

    // And at 24, where three bytes of an unclipped 1.5 wrap to a large negative number.
    const auto [frames24, b24] = exported(path, recording::Depth::Pcm24);
    bool clipped24 = frames24 == 1000;
    for (int i = 500; i < 1000 && clipped24; ++i) {
        clipped24 = le24(b24, 44 + i * 6) == 8388607 && le24(b24, 47 + i * 6) == -8388607;
    }
    check(clipped24, "24 bits clips too");
    std::remove(path.c_str());
}

} // namespace

int main() {
    theWindowIsTheLastFramesInOrder();
    reopeningCarriesOnAndAnotherShapeStartsOver();
    aFullRingDropsAndCountsRatherThanWaiting();
    closedItTakesNothing();
    aSaveReadsTheWindowOldestFirstAcrossTheWrap();
    aSaveStartsWhereTheSoundDoesAndKeepsAGap();
    silenceSavesNothingAndAFileThatIsNotAWindowSavesNothingEither();
    sixteenBitsIsDitheredByAStepAndClipped();
    return testing::report("recorder");
}
