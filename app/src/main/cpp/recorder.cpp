#include "recorder.h"

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

constexpr char kMagic[8] = {'P', 'G', 'R', 'E', 'C', '0', '1', '\0'};

/** How long the writer sleeps when the ring is empty. A block is well under a millisecond. */
constexpr auto kIdle = std::chrono::milliseconds(10);

struct Header {
    char magic[8];
    int32_t sampleRate;
    int32_t channels;
    int64_t capacity;
    int64_t window;
    int64_t total;
    char reserved[Recorder::kHeaderBytes - 40];
};
static_assert(sizeof(Header) == Recorder::kHeaderBytes, "the header is 64 bytes on disk");

std::size_t powerOfTwo(std::size_t n) {
    std::size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

bool writeAll(int fd, const void *data, std::size_t bytes, off_t at) {
    const auto *p = static_cast<const char *>(data);
    while (bytes > 0) {
        const ssize_t n = ::pwrite(fd, p, bytes, at);
        if (n <= 0) return false;
        p += n;
        at += n;
        bytes -= static_cast<std::size_t>(n);
    }
    return true;
}

} // namespace

Recorder::Recorder(std::size_t ringFrames)
    : ring_(std::make_unique<float[]>(powerOfTwo(ringFrames) * kChannels)),
      ringFloats_(powerOfTwo(ringFrames) * kChannels) {}

Recorder::~Recorder() { close(); }

bool Recorder::open(const std::string &path, int32_t sampleRate, int64_t windowFrames, int64_t marginFrames) {
    close();
    if (sampleRate <= 0 || windowFrames <= 0 || marginFrames < 0) return false;

    fd_ = ::open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd_ < 0) return false;

    sampleRate_ = sampleRate;
    window_ = windowFrames;
    capacity_ = windowFrames + marginFrames;

    // Carry on from a file of this shape; start any other over.
    Header was{};
    const bool same = ::pread(fd_, &was, sizeof was, 0) == static_cast<ssize_t>(sizeof was) &&
                      std::memcmp(was.magic, kMagic, sizeof kMagic) == 0 &&
                      was.sampleRate == sampleRate && was.channels == kChannels &&
                      was.capacity == capacity_ && was.window == window_ && was.total >= 0;
    if (same) {
        total_.store(was.total, std::memory_order_release);
    } else {
        total_.store(0, std::memory_order_release);
        // Sized now, sparse until written, so a full disk says so at the start rather than
        // at some later block -- and so the file is the size it will be.
        if (::ftruncate(fd_, kHeaderBytes + capacity_ * kChannels * static_cast<off_t>(sizeof(float))) != 0) {
            ::close(fd_);
            fd_ = -1;
            return false;
        }
    }
    writeHeader();

    // Anything still in the ring predates this file. The writer owns the tail, and nothing
    // is writing yet, so skipping to the head is the consumer's move to make.
    tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release);
    stopping_.store(false, std::memory_order_release);
    writer_ = std::thread([this] { run(); });
    active_.store(true, std::memory_order_release);
    return true;
}

void Recorder::close() {
    active_.store(false, std::memory_order_release);
    if (writer_.joinable()) {
        stopping_.store(true, std::memory_order_release);
        writer_.join();
    }
    if (fd_ >= 0) {
        writeHeader();
        ::close(fd_);
        fd_ = -1;
    }
}

void Recorder::write(const float *interleaved, int32_t frames) {
    if (frames <= 0 || !active_.load(std::memory_order_acquire)) return;
    const std::size_t floats = static_cast<std::size_t>(frames) * kChannels;
    const std::size_t head = head_.load(std::memory_order_relaxed);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    if (ringFloats_ - (head - tail) < floats) {
        dropped_.fetch_add(frames, std::memory_order_relaxed);
        return;
    }
    const std::size_t at = head & (ringFloats_ - 1);
    const std::size_t first = std::min(floats, ringFloats_ - at);
    std::memcpy(&ring_[at], interleaved, first * sizeof(float));
    std::memcpy(&ring_[0], interleaved + first, (floats - first) * sizeof(float));
    head_.store(head + floats, std::memory_order_release);
}

void Recorder::run() {
    while (true) {
        const bool wrote = drain();
        if (wrote) {
            writeHeader();
            continue;
        }
        // Empty. Once asked to stop, empty is done: close() has already stopped the audio
        // thread adding anything, so what was in the ring is now on disk.
        if (stopping_.load(std::memory_order_acquire)) break;
        std::this_thread::sleep_for(kIdle);
    }
}

bool Recorder::drain() {
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    const std::size_t head = head_.load(std::memory_order_acquire);
    int64_t frames = static_cast<int64_t>((head - tail) / kChannels);
    if (frames == 0) return false;

    std::size_t from = tail;
    int64_t total = total_.load(std::memory_order_relaxed);
    constexpr std::size_t frameBytes = kChannels * sizeof(float);
    while (frames > 0) {
        // A run contiguous in both the ring and the file.
        const std::size_t ringAt = from & (ringFloats_ - 1);
        const int64_t ringRun = static_cast<int64_t>((ringFloats_ - ringAt) / kChannels);
        const int64_t fileAt = total % capacity_;
        const int64_t fileRun = capacity_ - fileAt;
        const int64_t run = std::min({frames, ringRun, fileRun});
        if (!writeAll(fd_, &ring_[ringAt], static_cast<std::size_t>(run) * frameBytes,
                      kHeaderBytes + static_cast<off_t>(fileAt) * static_cast<off_t>(frameBytes))) {
            // A full disk or a vanished file: counted as dropped and skipped, rather than
            // retried forever with the ring filling behind it.
            dropped_.fetch_add(run, std::memory_order_relaxed);
        } else {
            total += run;
        }
        from += static_cast<std::size_t>(run) * kChannels;
        frames -= run;
    }
    // The frames first, then the count: a reader that sees the count can read every frame.
    total_.store(total, std::memory_order_release);
    tail_.store(from, std::memory_order_release);
    return true;
}

void Recorder::writeHeader() {
    if (fd_ < 0) return;
    Header h{};
    std::memcpy(h.magic, kMagic, sizeof kMagic);
    h.sampleRate = sampleRate_;
    h.channels = kChannels;
    h.capacity = capacity_;
    h.window = window_;
    h.total = total_.load(std::memory_order_acquire);
    writeAll(fd_, &h, sizeof h, 0);
}

namespace recording {

namespace {

/** Below this a sample is silence, for finding where the sound starts: about -110dB. */
constexpr float kSilent = 3e-6f;

/** Frames read and converted at a time: 512KB of floats, a few hundred writes a window. */
constexpr int64_t kChunk = 1 << 16;

bool writeOut(int fd, const void *data, std::size_t bytes) {
    const auto *p = static_cast<const char *>(data);
    while (bytes > 0) {
        const ssize_t n = ::write(fd, p, bytes);
        if (n <= 0) return false;
        p += n;
        bytes -= static_cast<std::size_t>(n);
    }
    return true;
}

/** Up to [count] frames from frame [f] into [into], as far as the file runs without wrapping. */
int64_t readFrames(int fd, const Header &h, int64_t f, int64_t count, float *into) {
    const int64_t at = f % h.capacity;
    const int64_t n = std::min(count, h.capacity - at);
    const std::size_t bytes = static_cast<std::size_t>(n) * Recorder::kChannels * sizeof(float);
    const ssize_t got = ::pread(fd, into, bytes, Recorder::kHeaderBytes + at * Recorder::kChannels *
                                                         static_cast<off_t>(sizeof(float)));
    return got == static_cast<ssize_t>(bytes) ? n : -1;
}

void put16(std::vector<char> &out, int32_t v) {
    out.push_back(static_cast<char>(v & 0xFF));
    out.push_back(static_cast<char>((v >> 8) & 0xFF));
}

void put32(std::vector<char> &out, uint32_t v) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<char>((v >> (8 * i)) & 0xFF));
}

/** A canonical header: PCM for the integer depths, IEEE float with its `fact` chunk otherwise. */
std::vector<char> wavHeader(int32_t rate, Depth depth, int64_t frames) {
    const bool isFloat = depth == Depth::Float32;
    const int32_t bytes = depth == Depth::Pcm16 ? 2 : depth == Depth::Pcm24 ? 3 : 4;
    const int32_t channels = Recorder::kChannels;
    const auto data = static_cast<uint32_t>(frames * channels * bytes);
    std::vector<char> out;
    out.insert(out.end(), {'R', 'I', 'F', 'F'});
    put32(out, 36u + (isFloat ? 12u : 0u) + data);
    out.insert(out.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    put32(out, 16);
    put16(out, isFloat ? 3 : 1);
    put16(out, channels);
    put32(out, static_cast<uint32_t>(rate));
    put32(out, static_cast<uint32_t>(rate * channels * bytes));
    put16(out, channels * bytes);
    put16(out, bytes * 8);
    if (isFloat) {
        out.insert(out.end(), {'f', 'a', 'c', 't'});
        put32(out, 4);
        put32(out, static_cast<uint32_t>(frames));
    }
    out.insert(out.end(), {'d', 'a', 't', 'a'});
    put32(out, data);
    return out;
}

/** xorshift32: the dither's noise, and not rand(), whose Bionic implementation takes a lock. */
struct Noise {
    uint32_t state = 0x9E3779B9u;
    float next() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return static_cast<float>(state >> 8) * (1.0f / 16777216.0f);
    }
};

} // namespace

int64_t exportWav(const std::string &path, int fd, Depth depth) {
    const int in = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    struct Closer {
        int fd;
        ~Closer() { ::close(fd); }
    } closer{in};

    Header h{};
    if (::pread(in, &h, sizeof h, 0) != static_cast<ssize_t>(sizeof h) ||
        std::memcmp(h.magic, kMagic, sizeof kMagic) != 0 || h.channels != Recorder::kChannels ||
        h.sampleRate <= 0 || h.capacity <= 0 || h.window <= 0 || h.window > h.capacity || h.total < 0) {
        return -1;
    }
    const int64_t available = std::min(h.total, h.window);
    std::vector<float> frames(static_cast<std::size_t>(kChunk) * Recorder::kChannels);

    // Where the sound starts.
    int64_t first = -1;
    for (int64_t f = h.total - available; f < h.total && first < 0;) {
        const int64_t n = readFrames(in, h, f, std::min(kChunk, h.total - f), frames.data());
        if (n < 0) return -1;
        for (int64_t i = 0; i < n * Recorder::kChannels; ++i) {
            if (std::fabs(frames[static_cast<std::size_t>(i)]) > kSilent) {
                first = f + i / Recorder::kChannels;
                break;
            }
        }
        f += n;
    }
    if (first < 0) return 0;

    const int64_t count = h.total - first;
    const auto header = wavHeader(h.sampleRate, depth, count);
    if (!writeOut(fd, header.data(), header.size())) return -1;

    const int32_t bytes = depth == Depth::Pcm16 ? 2 : depth == Depth::Pcm24 ? 3 : 4;
    std::vector<unsigned char> out(static_cast<std::size_t>(kChunk) * Recorder::kChannels * bytes);
    Noise noise;
    for (int64_t f = first; f < h.total;) {
        const int64_t n = readFrames(in, h, f, std::min(kChunk, h.total - f), frames.data());
        if (n < 0) return -1;
        const std::size_t samples = static_cast<std::size_t>(n) * Recorder::kChannels;
        unsigned char *o = out.data();
        for (std::size_t i = 0; i < samples; ++i) {
            const float s = frames[i];
            switch (depth) {
                case Depth::Float32:
                    std::memcpy(o, &s, 4); // little-endian, as every Android ABI is
                    o += 4;
                    break;
                case Depth::Pcm24: {
                    const auto v = static_cast<int32_t>(std::lrint(std::clamp(s, -1.0f, 1.0f) * 8388607.0f));
                    *o++ = static_cast<unsigned char>(v & 0xFF);
                    *o++ = static_cast<unsigned char>((v >> 8) & 0xFF);
                    *o++ = static_cast<unsigned char>((v >> 16) & 0xFF);
                    break;
                }
                case Depth::Pcm16: {
                    // Triangular dither of one step, so a quiet fade ends as noise rather than
                    // as the stepped distortion truncating to sixteen bits makes of it.
                    const float dither = noise.next() - noise.next();
                    const auto v = static_cast<int32_t>(std::clamp<long>(
                            std::lrint(std::clamp(s, -1.0f, 1.0f) * 32767.0f + dither), -32768L, 32767L));
                    *o++ = static_cast<unsigned char>(v & 0xFF);
                    *o++ = static_cast<unsigned char>((v >> 8) & 0xFF);
                    break;
                }
            }
        }
        if (!writeOut(fd, out.data(), static_cast<std::size_t>(o - out.data()))) return -1;
        f += n;
    }
    return count;
}

} // namespace recording

