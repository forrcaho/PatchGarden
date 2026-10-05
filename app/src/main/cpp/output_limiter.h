#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>

/**
 * The last thing between the patch and the converter: a lookahead peak limiter, linked across
 * both channels, that never lets a sample past its ceiling and touches nothing below its knee.
 *
 * It replaced DaisySP's `Limiter` inside `OutNode` on 2026-10-05. That one ran every sample
 * through `SoftLimit`, a curve with no threshold at all, so every patch was saturated whether
 * or not it was loud: 1.8% THD at half scale, measured on the phone as harmonics of an FM tone
 * that fell 27dB when the tone fell 13dB, which is third order. It also passed the first
 * millisecond of a sudden hit at full scale and above, and held the whole mix down for two
 * seconds after one, since its release was a one-second time constant.
 *
 * It lives here rather than in `OutNode` because its lookahead is a delay: inside the graph,
 * every timing the graph tests measure through Out would move by it, and the first sample of
 * a patch could no longer arrive in the first block. The engine runs it on the graph's output,
 * before the output switch's fade, so the capture and the recording hear what the converter does.
 *
 * Per frame: the louder channel's level sets a target gain from a static curve -- unity to the
 * knee's foot, a quadratic in decibels across the knee, and flat at the ceiling above it, so
 * the curve's value and slope are both continuous. The hold is the lowest target of the last
 * 20ms, a true sliding minimum; the release follows it down at once and up over 150ms; that is
 * averaged over the lookahead; and the audio is delayed by exactly the lookahead. That alignment
 * is the guarantee: a peak's target bounds every gain the average reads when the peak comes
 * out, so the peak lands at the ceiling or under it. The average also makes every gain change a
 * ramp across the lookahead rather than a step, which is what keeps riding the gain from
 * distorting what it rides.
 *
 * The hold is a sliding minimum rather than "the lowest so far, until 20ms pass without one as
 * low", which was the first version: a sampled sine's peaks differ in the fifth decimal, so the
 * slightly smaller ones failed to restart it, it lapsed between peaks, and the gain crept up and
 * was knocked back once a cycle -- 0.5% of ripple at a +6dB overload, which is distortion.
 *
 * Audio thread only, apart from prepare(), which runs while no stream does. Fixed storage, so
 * a sample rate past what it holds is clamped rather than allocated for.
 */
class OutputLimiter {
public:
    /** Where it levels off: -3dB of full scale. Sustained peaks sit here. */
    static constexpr float kCeilingDb = -3.0f;
    /** The knee is this wide, centered on the ceiling, so it starts bending at -6dB. */
    static constexpr float kKneeDb = 6.0f;
    static constexpr float kLookaheadSeconds = 0.001f;
    /** Longer than a 50Hz half-cycle, so a low note's own peaks do not wobble the gain. */
    static constexpr float kHoldSeconds = 0.020f;
    static constexpr float kReleaseSeconds = 0.150f;
    /** What the storage is sized for; past it, the lookahead and the hold are clamped. */
    static constexpr int32_t kMaxRate = 192000;
    static constexpr int32_t kMaxLookahead = 192;  // a millisecond at kMaxRate
    static constexpr int32_t kMaxHold = 3840;      // kHoldSeconds at kMaxRate

    void prepare(int32_t sampleRate) {
        const double rate = sampleRate > 0 ? sampleRate : 48000;
        lookahead_ = static_cast<int32_t>(std::lround(rate * kLookaheadSeconds));
        if (lookahead_ < 1) lookahead_ = 1;
        if (lookahead_ > kMaxLookahead) lookahead_ = kMaxLookahead;
        window_ = lookahead_ + 1;
        // At least the window, or the hold would expire while the average still reads it.
        hold_ = static_cast<int32_t>(std::lround(rate * kHoldSeconds));
        if (hold_ > kMaxHold) hold_ = kMaxHold;
        if (hold_ < window_) hold_ = window_;
        release_ = static_cast<float>(std::exp(-1.0 / (rate * kReleaseSeconds)));
        reset();
    }

    void reset() {
        delayLeft_.fill(0.0f);
        delayRight_.fill(0.0f);
        gains_.fill(1.0f);
        sum_ = window_;
        released_ = 1.0f;
        frame_ = 0;
        minHead_ = 0;
        minCount_ = 0;
        delayAt_ = 0;
        gainAt_ = 0;
    }

    /** Frames by which everything comes out late. */
    int32_t latency() const { return lookahead_; }

    /** Takes one frame in and leaves the frame from [latency] ago, limited, in its place. */
    void process(float &left, float &right) {
        // A filter that blew up must not poison the gain forever, nor reach the converter.
        if (!finite(left)) left = 0.0f;
        if (!finite(right)) right = 0.0f;

        const float held = hold(targetGain(std::fmax(std::fabs(left), std::fabs(right))));
        // Down at once, up over the release -- so from below, never past what is held.
        released_ = held <= released_ ? held : held + (released_ - held) * release_;

        sum_ += static_cast<double>(released_) - gains_[gainAt_];
        gains_[gainAt_] = released_;
        if (++gainAt_ == window_) {
            gainAt_ = 0;
            // Re-summed once a window, so a running sum's rounding cannot drift for hours.
            sum_ = 0.0;
            for (int32_t i = 0; i < window_; ++i) sum_ += gains_[i];
        }
        const auto gain = static_cast<float>(sum_ / window_);

        const float outLeft = delayLeft_[delayAt_];
        const float outRight = delayRight_[delayAt_];
        delayLeft_[delayAt_] = left;
        delayRight_[delayAt_] = right;
        if (++delayAt_ == lookahead_) delayAt_ = 0;

        left = outLeft * gain;
        right = outRight * gain;
    }

    /** The static curve: the gain a sustained level settles to. Public for the tests. */
    static float targetGain(float level) {
        constexpr float kFoot = 0.50118723f;  // -6dB, where the knee starts
        if (level <= kFoot) return 1.0f;
        const float db = 20.0f * std::log10(level);
        const float foot = kCeilingDb - kKneeDb * 0.5f;
        const float gainDb = db >= kCeilingDb + kKneeDb * 0.5f
                ? kCeilingDb - db
                : -(db - foot) * (db - foot) / (2.0f * kKneeDb);
        return std::pow(10.0f, gainDb / 20.0f);
    }

private:
    /**
     * The lowest target of the last [hold_] frames, as a monotonic queue: each entry is lower
     * than every one behind it, so the front is the minimum, and a new target first drops
     * every queued one it undercuts, since none of those can be the minimum again. Amortized
     * one push and one pop a frame.
     */
    float hold(float target) {
        while (minCount_ > 0 && minValue_[slot(minCount_ - 1)] >= target) --minCount_;
        minValue_[slot(minCount_)] = target;
        minFrame_[slot(minCount_)] = frame_;
        ++minCount_;
        if (frame_ - minFrame_[minHead_] >= static_cast<uint32_t>(hold_)) {
            minHead_ = slot(1);
            --minCount_;
        }
        ++frame_;
        return minValue_[minHead_];
    }

    int32_t slot(int32_t offset) const { return (minHead_ + offset) % (kMaxHold + 1); }

    /**
     * By the bits, because the app builds with -ffast-math, under which the compiler may
     * assume no float is ever NaN or infinite and fold std::isfinite to true.
     */
    static bool finite(float x) {
        uint32_t bits;
        std::memcpy(&bits, &x, sizeof bits);
        return (bits & 0x7f800000u) != 0x7f800000u;
    }

    int32_t lookahead_ = 48;
    int32_t window_ = 49;
    int32_t hold_ = 960;
    float release_ = 0.99986f;

    std::array<float, kMaxLookahead> delayLeft_{};
    std::array<float, kMaxLookahead> delayRight_{};
    std::array<float, kMaxLookahead + 1> gains_{};
    double sum_ = 0.0;
    float released_ = 1.0f;
    int32_t delayAt_ = 0;
    int32_t gainAt_ = 0;

    // The hold's queue: a ring of kMaxHold + 1, since at most hold_ entries are ever in it.
    std::array<float, kMaxHold + 1> minValue_{};
    std::array<uint32_t, kMaxHold + 1> minFrame_{};
    int32_t minHead_ = 0;
    int32_t minCount_ = 0;
    uint32_t frame_ = 0;  // wraps; only differences are read
};
