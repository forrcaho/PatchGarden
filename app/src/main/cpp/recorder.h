#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>

/**
 * Always recording: the last few minutes of what reached the stream, kept on disk so that
 * something found while exploring can be saved rather than reconstructed.
 *
 * On disk rather than in memory, as Bespoke keeps its thirty minutes: ten minutes of float
 * stereo at 48kHz is about 230MB, a backgrounded process that size is the first thing the
 * low-memory killer takes -- exactly when you have gone to do something else -- and a file
 * also survives a crash. The window is a circular file behind a small header, and saving is
 * the interface's job: it reads the header and the frames, and never has to ask this class.
 *
 * The audio thread copies each block into a lock-free ring and returns; a writer thread
 * drains the ring into the file. So the callback never touches I/O, and a stalled disk costs
 * dropped frames, counted, rather than a missed deadline.
 *
 * The file:
 *   0  char[8]  "PGREC01"
 *   8  int32    sample rate
 *   12 int32    channels, always 2
 *   16 int64    capacity, in frames: the window and a margin
 *   24 int64    window, in frames: how much a save may read back
 *   32 int64    frames written since the file began
 *   64 ...      frames, frame f at 64 + (f % capacity) * 8
 *
 * The capacity is the window **and a margin**, and a save reads only the window. A save reads
 * oldest first while this carries on writing, and what it writes lands on the oldest frames
 * of the file; the margin is what it has to write through before it can reach any a save is
 * reading. The header's count is written after the frames it counts, so a reader that sees N
 * can read every frame below N.
 */
class Recorder {
public:
    static constexpr int kHeaderBytes = 64;
    static constexpr int kChannels = 2;

    /** [ringFrames] is rounded up to a power of two. Allocated here and never again. */
    explicit Recorder(std::size_t ringFrames = 1u << 17);
    ~Recorder();

    Recorder(const Recorder &) = delete;
    Recorder &operator=(const Recorder &) = delete;

    /**
     * Interface thread. Opens [path] as a window of [windowFrames] and starts writing into it.
     * A file already there with this rate and shape carries on from where it stopped, so the
     * window survives the app being put away and brought back; any other file is started
     * over. Closes whatever was open first.
     */
    bool open(const std::string &path, int32_t sampleRate, int64_t windowFrames, int64_t marginFrames);

    /** Interface thread. Writes out what is in the ring, then the header, and closes. */
    void close();

    bool isOpen() const { return active_.load(std::memory_order_acquire); }

    /**
     * Audio thread. Interleaved stereo. No allocation, no lock, no I/O: a copy into the ring,
     * or, when the writer has fallen a ring behind, nothing -- the block is counted as dropped.
     */
    void write(const float *interleaved, int32_t frames);

    /** Frames on disk since the file began, as the header says it. */
    int64_t totalFrames() const { return total_.load(std::memory_order_acquire); }

    /** Frames the audio thread could not fit in the ring. */
    int64_t droppedFrames() const { return dropped_.load(std::memory_order_relaxed); }

private:
    void run();
    bool drain();
    void writeHeader();

    std::unique_ptr<float[]> ring_;
    std::size_t ringFloats_;
    std::atomic<std::size_t> head_{0}; // audio thread
    std::atomic<std::size_t> tail_{0}; // writer thread

    std::atomic<bool> active_{false};
    std::atomic<bool> stopping_{false};
    std::thread writer_;

    int fd_ = -1;
    int32_t sampleRate_ = 0;
    int64_t capacity_ = 0;
    int64_t window_ = 0;
    std::atomic<int64_t> total_{0};
    std::atomic<int64_t> dropped_{0};
};

/**
 * Saving a window: what the file holds, read back oldest first and written as a WAV.
 *
 * Here rather than in Kotlin, where it was first: converting tens of millions of samples one at
 * a time took five minutes on the phone for an eight-minute window in a debug build, against a
 * disk that writes 360MB a second. In C++ it is as fast as the storage, whatever the build.
 */
namespace recording {

/** The bit depths a save is written at. Mirrors BitDepth.code in Recording.kt. */
enum class Depth : int32_t {
    Pcm16 = 0,
    Pcm24 = 1,
    Float32 = 2,
};

/** How far a save has got, in frames, for the interface to poll. */
struct Progress {
    std::atomic<int64_t> done{0};
    std::atomic<int64_t> total{0};
};

/**
 * Writes the window in the recorder file at [path] to [fd] as a WAV at [depth], and returns
 * the frames written: 0 when there was only silence, -1 when it could not be read or written.
 * [fd] is left open; it is the caller's.
 *
 * From the first sound rather than the window's first frame -- a window begins wherever it
 * began, and minutes of the output switched off are nobody's recording -- and everything after
 * it, silences included, since a gap in the middle of something is part of it. Oldest first,
 * which is what lets the recorder go on writing meanwhile: see the margin, above.
 */
int64_t exportWav(const std::string &path, int fd, Depth depth, Progress *progress = nullptr);

} // namespace recording

