#include "recorder.h"

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

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
