#include "engine_core.h"
#include "../effects/effect_factory.h"
#include <algorithm>
#include <chrono>
#include <cmath>

namespace wavestudio {

namespace {
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Master safety stage: transparent below 0.95, smooth knee up to 1.0, never beyond.
inline float master_soft_limit(float v) {
    const float a = std::fabs(v);
    if (a <= 0.95f) return v;
    const float o = 0.95f + 0.05f * std::tanh((a - 0.95f) / 0.05f);
    return v < 0 ? -o : o;
}
}  // namespace

EngineCore::EngineCore(std::shared_ptr<MediaLibrary> media, float engine_rate, float timeline_rate)
    : media_(std::move(media)), engine_rate_(engine_rate), timeline_rate_(timeline_rate),
      retire_(256), params_(4096), analysis_(1u << 17) {
    if (!media_) media_ = std::make_shared<MediaLibrary>();
    lufs_.set_sample_rate(engine_rate_);
    spectrum_.init(2048, engine_rate_);
    analysis_tmp_.assign(8192, 0.f);
    gain_l_.fill(1.f); gain_r_.fill(1.f);
    for (int b = 0; b < kMaxBuses; ++b) { bus_level_[b].store(1.f); bus_gain_cur_[b] = 1.f; bus_idle_[b] = 1 << 30; }
    {
        std::lock_guard<std::mutex> lk(mu_);
        publish_locked();
    }
}

EngineCore::~EngineCore() {
    if (recorder_.active()) recorder_.stop();
    std::lock_guard<std::mutex> lk(mu_);
    collect_garbage_locked();
    delete pending_.exchange(nullptr);
    delete current_;
    current_ = nullptr;
}

int64_t EngineCore::tl_to_engine(int64_t tl) const {
    return (int64_t)std::llround((double)tl * (double)engine_rate_ / (double)timeline_rate_);
}
int64_t EngineCore::engine_to_tl(int64_t e) const {
    return (int64_t)std::llround((double)e * (double)timeline_rate_ / (double)engine_rate_);
}

// ============================================================================================
// Control side
// ============================================================================================

void EngineCore::collect_garbage_locked() {
    Snapshot* s;
    while (retire_.pop(&s, 1) == 1) delete s;
}

EngineCore::MTrack* EngineCore::find_track(int id) {
    for (auto& t : tracks_) if (t.id == id) return &t;
    return nullptr;
}
const EngineCore::MTrack* EngineCore::find_track(int id) const {
    for (auto& t : tracks_) if (t.id == id) return &t;
    return nullptr;
}

std::vector<EngineCore::MEffect>& EngineCore::chain_of(int track_id, bool* ok) {
    static std::vector<MEffect> dummy;
    if (ok) *ok = true;
    if (track_id == 0) return master_fx_;
    if (track_id < 0) {
        const int b = -track_id - 1;
        if (b >= kMaxBuses) { if (ok) *ok = false; return dummy; }
        return bus_fx_[b];
    }
    MTrack* t = find_track(track_id);
    if (!t) { if (ok) *ok = false; return dummy; }
    return t->fx;
}

EngineCore::MEffect* EngineCore::find_effect(int id, MTrack** owner, int* idx, std::vector<MEffect>** chain, int* owner_id) {
    auto hit = [&](std::vector<MEffect>& c, size_t i, MTrack* o, int oid) {
        if (owner) *owner = o;
        if (idx) *idx = (int)i;
        if (chain) *chain = &c;
        if (owner_id) *owner_id = oid;
        return &c[i];
    };
    for (size_t i = 0; i < master_fx_.size(); ++i) if (master_fx_[i].id == id) return hit(master_fx_, i, nullptr, 0);
    for (int b = 0; b < kMaxBuses; ++b)
        for (size_t i = 0; i < bus_fx_[b].size(); ++i) if (bus_fx_[b][i].id == id) return hit(bus_fx_[b], i, nullptr, -(b + 1));
    for (auto& t : tracks_)
        for (size_t i = 0; i < t.fx.size(); ++i) if (t.fx[i].id == id) return hit(t.fx, i, &t, t.id);
    return nullptr;
}

void EngineCore::publish_locked() {
    auto* s = new Snapshot();
    auto make_chain = [](const std::vector<MEffect>& src, std::vector<SSlot>& dst) {
        dst.reserve(src.size());
        for (auto& e : src) {
            SSlot sl; sl.fx = e.fx.get(); sl.keep = e.fx; sl.id = e.id; sl.bypass = e.bypass;
            dst.push_back(std::move(sl));
        }
    };
    s->tracks.reserve(tracks_.size());
    for (auto& t : tracks_) {
        STrack st; st.id = t.id; st.slot = t.slot;
        make_chain(t.fx, st.fx);
        for (auto& c : clips_) {
            if (c.p.track_id != t.id || !c.buf) continue;
            SClip sc;
            sc.keep = c.buf; sc.buf = c.buf.get();
            sc.start = tl_to_engine(c.p.start);
            sc.off = std::max<int64_t>(0, tl_to_engine(c.p.offset));
            int64_t avail = c.buf->frames() - sc.off;
            if (avail <= 0) continue;
            sc.len = std::min<int64_t>(tl_to_engine(c.p.length), avail);
            if (sc.len <= 0) continue;
            sc.gain = std::pow(10.f, c.p.gain_db / 20.f);
            sc.fin = std::max<int64_t>(0, tl_to_engine(c.p.fade_in));
            sc.fout = std::max<int64_t>(0, tl_to_engine(c.p.fade_out));
            st.clips.push_back(std::move(sc));
        }
        s->tracks.push_back(std::move(st));
    }
    make_chain(master_fx_, s->master);
    for (int b = 0; b < kMaxBuses; ++b) make_chain(bus_fx_[b], s->bus[b]);
    const int64_t ls = tl_to_engine(loop_s_tl_), le = tl_to_engine(loop_e_tl_);
    s->loop = loop_en_ && le > ls + 16;
    s->loop_s = ls; s->loop_e = le;
    s->punch = punch_en_ && punch_out_tl_ > punch_in_tl_;
    s->punch_in = tl_to_engine(punch_in_tl_); s->punch_out = tl_to_engine(punch_out_tl_);

    // If the audio thread never picked up the previous pending snapshot, nobody else references it.
    Snapshot* old = pending_.exchange(s, std::memory_order_acq_rel);
    delete old;
    collect_garbage_locked();
}

bool EngineCore::add_track(int id) {
    std::lock_guard<std::mutex> lk(mu_);
    if (id <= 0 || find_track(id)) return false;
    int slot = -1;
    for (int i = 0; i < kMaxTracks; ++i) if (!slot_used_[i]) { slot = i; break; }
    if (slot < 0) return false;
    slot_used_[slot] = true;
    MTrack t; t.id = id; t.slot = slot;
    tracks_.push_back(t);
    TrackState& ts = tstate_[slot];
    for (int b = 0; b < kMaxBuses; ++b) { ts.send[b].store(0.f); send_cur_[slot][b] = 0.f; }
    ts.vol = 1.f; ts.pan = 0.f; ts.mute = false; ts.solo = false; ts.peak_l = 0.f; ts.peak_r = 0.f; ts.fresh = true;
    publish_locked();
    return true;
}

bool EngineCore::remove_track(int id) {
    std::lock_guard<std::mutex> lk(mu_);
    for (size_t i = 0; i < tracks_.size(); ++i) {
        if (tracks_[i].id != id) continue;
        slot_used_[tracks_[i].slot] = false;
        tracks_.erase(tracks_.begin() + i);
        clips_.erase(std::remove_if(clips_.begin(), clips_.end(),
                                    [id](const MClip& c) { return c.p.track_id == id; }), clips_.end());
        publish_locked();
        return true;
    }
    return false;
}

bool EngineCore::has_track(int id) const {
    std::lock_guard<std::mutex> lk(mu_);
    return find_track(id) != nullptr;
}

void EngineCore::set_track_volume(int id, float v) {
    std::lock_guard<std::mutex> lk(mu_);
    if (MTrack* t = find_track(id)) { t->vol = clampf(v == v ? v : 1.f, 0.f, 4.f); tstate_[t->slot].vol.store(t->vol); }
}
void EngineCore::set_track_pan(int id, float p) {
    std::lock_guard<std::mutex> lk(mu_);
    if (MTrack* t = find_track(id)) { t->pan = clampf(p == p ? p : 0.f, -1.f, 1.f); tstate_[t->slot].pan.store(t->pan); }
}
void EngineCore::set_track_mute(int id, bool m) {
    std::lock_guard<std::mutex> lk(mu_);
    if (MTrack* t = find_track(id)) { t->mute = m; tstate_[t->slot].mute.store(m); }
}
void EngineCore::set_track_solo(int id, bool s) {
    std::lock_guard<std::mutex> lk(mu_);
    if (MTrack* t = find_track(id)) { t->solo = s; tstate_[t->slot].solo.store(s); }
}
void EngineCore::set_track_send(int id, int bus, float level) {
    if (bus < 0 || bus >= kMaxBuses) return;
    std::lock_guard<std::mutex> lk(mu_);
    if (MTrack* t = find_track(id)) { t->send[bus] = clampf(level == level ? level : 0.f, 0.f, 2.f); tstate_[t->slot].send[bus].store(t->send[bus]); }
}
void EngineCore::set_bus_level(int bus, float level) {
    if (bus < 0 || bus >= kMaxBuses) return;
    bus_level_[bus].store(clampf(level == level ? level : 1.f, 0.f, 2.f));
}
void EngineCore::set_master_volume(float v) { master_vol_.store(clampf(v == v ? v : 1.f, 0.f, 2.f)); }

int EngineCore::set_clip(const ClipParams& c) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (!find_track(c.track_id)) return -1;
    }
    // Decode / resample without holding the control lock (can take seconds for long files).
    auto res = media_->get(c.path, engine_rate_);
    if (!res.buffer) return res.error ? res.error : kMediaOpenFailed;
    std::lock_guard<std::mutex> lk(mu_);
    if (!find_track(c.track_id)) return -1;
    MClip mc; mc.p = c; mc.buf = res.buffer;
    bool replaced = false;
    for (auto& e : clips_) if (e.p.id == c.id) { e = mc; replaced = true; break; }
    if (!replaced) clips_.push_back(mc);
    publish_locked();
    return res.error;   // 0 or kMediaTruncated (usable)
}

bool EngineCore::remove_clip(int clip_id) {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = std::remove_if(clips_.begin(), clips_.end(), [clip_id](const MClip& c) { return c.p.id == clip_id; });
    if (it == clips_.end()) return false;
    clips_.erase(it, clips_.end());
    publish_locked();
    return true;
}

void EngineCore::clear_clips() {
    std::lock_guard<std::mutex> lk(mu_);
    clips_.clear();
    publish_locked();
}

int64_t EngineCore::project_end() const {
    std::lock_guard<std::mutex> lk(mu_);
    int64_t end = 0;
    for (auto& c : clips_) {
        if (!c.buf) continue;
        const int64_t src_tl = engine_to_tl(c.buf->frames()) - c.p.offset;
        end = std::max(end, c.p.start + std::max<int64_t>(0, std::min(c.p.length, src_tl)));
    }
    return end;
}

bool EngineCore::get_peaks(const std::string& path, double t0, double t1, int bins, float* out) {
    if (bins <= 0 || !out) return false;
    auto res = media_->get(path, engine_rate_);
    if (!res.buffer) { std::fill(out, out + (size_t)bins * 2, 0.f); return false; }
    const int64_t s = (int64_t)std::llround(t0 * engine_rate_), e = (int64_t)std::llround(t1 * engine_rate_);
    res.buffer->get_peaks(s, e, bins, out);
    return true;
}

int EngineCore::add_effect(int track_id, const std::string& type, int forced_id, int index) {
    std::unique_ptr<Effect> fx = create_effect(type);
    if (!fx) return -2;
    fx->set_sample_rate(engine_rate_);
    std::lock_guard<std::mutex> lk(mu_);
    bool ok = true;
    auto& chain = chain_of(track_id, &ok);
    if (!ok) return -1;
    if ((int)chain.size() >= kMaxEffectsPerChain) return -3;
    MEffect e;
    e.id = forced_id > 0 ? forced_id : next_effect_id_;
    if (find_effect(e.id, nullptr, nullptr)) return -4;
    next_effect_id_ = std::max(next_effect_id_, e.id + 1);
    e.type = type;
    e.fx = std::shared_ptr<Effect>(fx.release());
    for (int i = 0; i < e.fx->param_count(); ++i) e.values.push_back(e.fx->param(i).value);
    if (index < 0 || index > (int)chain.size()) index = (int)chain.size();
    chain.insert(chain.begin() + index, std::move(e));
    const int id = chain[index].id;
    publish_locked();
    return id;
}

bool EngineCore::remove_effect(int effect_id) {
    std::lock_guard<std::mutex> lk(mu_);
    int idx = 0; std::vector<MEffect>* cp = nullptr;
    if (!find_effect(effect_id, nullptr, &idx, &cp)) return false;
    auto& chain = *cp;
    chain.erase(chain.begin() + idx);
    publish_locked();
    return true;
}

bool EngineCore::move_effect(int effect_id, int new_index) {
    std::lock_guard<std::mutex> lk(mu_);
    int idx = 0; std::vector<MEffect>* cp = nullptr;
    if (!find_effect(effect_id, nullptr, &idx, &cp)) return false;
    auto& chain = *cp;
    new_index = std::max(0, std::min(new_index, (int)chain.size() - 1));
    if (new_index == idx) return true;
    MEffect e = std::move(chain[idx]);
    chain.erase(chain.begin() + idx);
    chain.insert(chain.begin() + new_index, std::move(e));
    publish_locked();
    return true;
}

bool EngineCore::set_effect_param(int effect_id, int pi, float value) {
    std::lock_guard<std::mutex> lk(mu_);
    MEffect* e = find_effect(effect_id, nullptr, nullptr);
    if (!e || pi < 0 || pi >= (int)e->values.size() || !(value == value)) return false;
    const auto& pr = e->fx->param(pi);          // min/max are immutable after construction
    value = clampf(value, pr.min, pr.max);
    ParamMsg m{effect_id, pi, value};
    if (params_.push(&m, 1) != 1) return false;  // queue full: report instead of blocking the UI
    e->values[pi] = value;
    return true;
}

bool EngineCore::set_effect_bypass(int effect_id, bool bypass) {
    std::lock_guard<std::mutex> lk(mu_);
    MEffect* e = find_effect(effect_id, nullptr, nullptr);
    if (!e) return false;
    if (e->bypass != bypass) { e->bypass = bypass; publish_locked(); }
    return true;
}

bool EngineCore::get_effect_info(int effect_id, EffectInfo& out) const {
    std::lock_guard<std::mutex> lk(mu_);
    auto* self = const_cast<EngineCore*>(this);
    int idx = 0, owner_id = 0;
    MEffect* e = self->find_effect(effect_id, nullptr, &idx, nullptr, &owner_id);
    if (!e) return false;
    out = EffectInfo();
    out.id = e->id; out.track_id = owner_id; out.type = e->type;
    out.name = e->fx->name(); out.bypass = e->bypass;
    for (int i = 0; i < e->fx->param_count(); ++i) {
        const auto& p = e->fx->param(i);
        EffectParamInfo pi; pi.id = p.id; pi.display = p.display; pi.unit = p.unit;
        pi.min = p.min; pi.max = p.max; pi.def = p.def; pi.value = e->values[i]; pi.options = p.options;
        out.params.push_back(std::move(pi));
    }
    return true;
}

std::vector<int> EngineCore::list_effects(int track_id) const {
    std::lock_guard<std::mutex> lk(mu_);
    std::vector<int> ids;
    const std::vector<MEffect>* chain = &master_fx_;
    if (track_id < 0) {
        const int b = -track_id - 1;
        if (b >= kMaxBuses) return ids;
        chain = &bus_fx_[b];
    } else if (track_id != 0) {
        const MTrack* t = find_track(track_id);
        if (!t) return ids;
        chain = &t->fx;
    }
    for (auto& e : *chain) ids.push_back(e.id);
    return ids;
}

// ---- transport ----
void EngineCore::play() { play_req_.store(1, std::memory_order_release); }
void EngineCore::pause() { play_req_.store(0, std::memory_order_release); }
void EngineCore::seek(int64_t tl) {
    tl = std::max<int64_t>(0, tl);
    seek_req_.store(tl_to_engine(tl), std::memory_order_release);
    pos_pub_.store(tl_to_engine(tl), std::memory_order_release);   // visible even while the device is closed
}
int64_t EngineCore::position() const { return engine_to_tl(pos_pub_.load(std::memory_order_acquire)); }

void EngineCore::set_loop(int64_t s, int64_t e, bool en) {
    std::lock_guard<std::mutex> lk(mu_);
    loop_s_tl_ = std::max<int64_t>(0, s); loop_e_tl_ = std::max<int64_t>(0, e); loop_en_ = en;
    publish_locked();
}
void EngineCore::set_punch(bool en, int64_t in, int64_t out) {
    std::lock_guard<std::mutex> lk(mu_);
    punch_en_ = en; punch_in_tl_ = in; punch_out_tl_ = out;
    publish_locked();
}
void EngineCore::set_metronome(bool on, float bpm, int beats) {
    bpm_.store(clampf(bpm == bpm ? bpm : 120.f, 20.f, 400.f));
    beats_.store(std::max(1, std::min(beats, 16)));
    metro_on_.store(on);
}
void EngineCore::set_count_in_bars(int bars) { count_in_bars_.store(std::max(0, std::min(bars, 4))); }
void EngineCore::set_monitor(bool on, float gain) { monitor_gain_.store(clampf(gain, 0.f, 2.f)); monitor_on_.store(on); }
float EngineCore::input_peak() { return in_peak_.exchange(0.f); }

// ---- recording ----
bool EngineCore::start_recording(const std::string& path, int ch, int bits) {
    std::lock_guard<std::mutex> lk(mu_);
    if (recorder_.active()) return false;
    rec_first_pos_.store(-1);
    rec_gen_.fetch_add(1, std::memory_order_acq_rel);   // before the recorder becomes active
    return recorder_.start(path, ch, (int)engine_rate_, bits);
}

Recorder::Result EngineCore::stop_recording(int64_t* timeline_start) {
    Recorder::Result r = recorder_.stop();
    const int64_t first = rec_first_pos_.load();
    if (timeline_start) {
        // Shift earlier by the input+output latency so the take lines up with what the performer heard.
        const int64_t e = first < 0 ? pos_pub_.load() : first - latency_comp_.load();
        *timeline_start = std::max<int64_t>(0, engine_to_tl(e));
    }
    return r;
}

// ---- meters ----
MasterMeters EngineCore::master_meters() {
    MasterMeters m;
    m.peak_l = m_peak_l_.exchange(0.f); m.peak_r = m_peak_r_.exchange(0.f);
    m.rms_l = m_rms_l_.load(); m.rms_r = m_rms_r_.load();
    m.clip_count = clip_count_.load();
    return m;
}
bool EngineCore::track_meters(int id, float* l, float* r) {
    std::lock_guard<std::mutex> lk(mu_);
    const MTrack* t = find_track(id);
    if (!t) return false;
    if (l) *l = tstate_[t->slot].peak_l.exchange(0.f);
    if (r) *r = tstate_[t->slot].peak_r.exchange(0.f);
    return true;
}

void EngineCore::pump_analysis_locked() {
    // Bounded work per call: a producer running faster than real time (offline/test) must not trap
    // the caller. If analysis falls behind, the excess is simply dropped (best-effort meters).
    for (int iter = 0; iter < 8; ++iter) {
        size_t got = analysis_.pop(analysis_tmp_.data(), analysis_tmp_.size());
        if (got < 2) break;
        const int frames = (int)(got / 2);
        lufs_.process_interleaved(analysis_tmp_.data(), frames, 2);
        spectrum_.push(analysis_tmp_.data(), frames, 2);
        float l[256], r[256];
        for (int base = 0; base < frames; base += 256) {
            const int n = std::min(256, frames - base);
            for (int i = 0; i < n; ++i) { l[i] = analysis_tmp_[(base + i) * 2]; r[i] = analysis_tmp_[(base + i) * 2 + 1]; }
            phase_.process(l, r, n);
        }
        if (got < analysis_tmp_.size()) break;
    }
}
float EngineCore::lufs_integrated() { std::lock_guard<std::mutex> l(mu_); pump_analysis_locked(); return lufs_.integrated(); }
float EngineCore::lufs_momentary() { std::lock_guard<std::mutex> l(mu_); pump_analysis_locked(); return lufs_.momentary(); }
float EngineCore::lufs_short_term() { std::lock_guard<std::mutex> l(mu_); pump_analysis_locked(); return lufs_.short_term(); }
float EngineCore::correlation() { std::lock_guard<std::mutex> l(mu_); pump_analysis_locked(); return phase_.correlation(); }
void EngineCore::reset_loudness() { std::lock_guard<std::mutex> l(mu_); pump_analysis_locked(); lufs_.reset(); }
void EngineCore::get_spectrum(float* bins, int n) { std::lock_guard<std::mutex> l(mu_); pump_analysis_locked(); spectrum_.get_magnitudes(bins, n); }
float EngineCore::max_render_load() { return max_load_.exchange(0.f); }

// ---- clone / offline ----
std::unique_ptr<EngineCore> EngineCore::clone(float new_rate) const {
    std::lock_guard<std::mutex> lk(mu_);
    auto dst = std::make_unique<EngineCore>(media_, new_rate, timeline_rate_);
    dst->set_master_volume(master_vol_.load());
    for (auto& t : tracks_) {
        dst->add_track(t.id);
        dst->set_track_volume(t.id, t.vol); dst->set_track_pan(t.id, t.pan);
        dst->set_track_mute(t.id, t.mute); dst->set_track_solo(t.id, t.solo);
        for (int b = 0; b < kMaxBuses; ++b) dst->set_track_send(t.id, b, t.send[b]);
    }
    for (int b = 0; b < kMaxBuses; ++b) dst->set_bus_level(b, bus_level_[b].load());
    auto copy_chain = [&](int track_id, const std::vector<MEffect>& chain) {
        for (auto& e : chain) {
            const int id = dst->add_effect(track_id, e.type, e.id);
            if (id < 0) continue;
            std::lock_guard<std::mutex> l2(dst->mu_);
            MEffect* de = dst->find_effect(id, nullptr, nullptr);
            for (size_t i = 0; de && i < e.values.size(); ++i) { de->fx->set_param((int)i, e.values[i]); de->values[i] = e.values[i]; }
            if (de && e.bypass) { de->bypass = true; dst->publish_locked(); }
        }
    };
    copy_chain(0, master_fx_);
    for (int b = 0; b < kMaxBuses; ++b) copy_chain(-(b + 1), bus_fx_[b]);
    for (auto& t : tracks_) copy_chain(t.id, t.fx);
    for (auto& c : clips_) dst->set_clip(c.p);
    return dst;
}

void EngineCore::prepare_offline() {
    set_loop(0, 0, false);
    set_punch(false, 0, 0);
    metro_on_.store(false);
    count_in_bars_.store(0);
    monitor_on_.store(false);
}

bool EngineCore::render_offline(int64_t start_tl, int64_t frames, float* out, int64_t chunk) {
    if (!out || frames < 0) return false;
    prepare_offline();
    seek(start_tl);
    play();
    chunk = std::max<int64_t>(64, std::min<int64_t>(chunk, 16384));
    int64_t done = 0;
    while (done < frames) {
        const int n = (int)std::min<int64_t>(chunk, frames - done);
        render(out + done * 2, n, nullptr, 0);
        done += n;
    }
    pause();
    return true;
}

// ============================================================================================
// Audio side
// ============================================================================================

void EngineCore::apply_params() {
    ParamMsg m;
    int budget = 512;
    while (budget-- > 0 && params_.pop(&m, 1) == 1) {
        if (!current_) continue;
        bool done = false;
        for (auto& t : current_->tracks) {
            for (auto& s : t.fx) if (s.id == m.effect_id) { s.fx->set_param(m.index, m.value); done = true; break; }
            if (done) break;
        }
        if (!done) for (auto& s : current_->master) if (s.id == m.effect_id) { s.fx->set_param(m.index, m.value); done = true; break; }
        for (int bi = 0; !done && bi < kMaxBuses; ++bi)
            for (auto& s : current_->bus[bi]) if (s.id == m.effect_id) { s.fx->set_param(m.index, m.value); done = true; break; }
    }
}

void EngineCore::render(float* out, int frames, const float* in, int in_ch) {
    const auto t0 = std::chrono::steady_clock::now();
    enable_flush_denormals();
    if (Snapshot* p = pending_.exchange(nullptr, std::memory_order_acq_rel)) {
        if (current_) {
            if (retire_.push(&current_, 1) != 1) { /* retire ring full: leak rather than free in the callback */ }
        }
        current_ = p;
    }
    apply_params();
    int done = 0;
    while (done < frames) {
        const int want = std::min(kMaxChunk, frames - done);
        const int used = render_chunk_planned(out + (size_t)done * 2, want, in ? in + (size_t)done * std::max(1, in_ch) : nullptr, in_ch);
        done += std::max(1, used);
    }
    renders_.fetch_add(1, std::memory_order_relaxed);
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    const float load = (float)(secs / ((double)frames / (double)engine_rate_));
    if (load > max_load_.load(std::memory_order_relaxed)) max_load_.store(load, std::memory_order_relaxed);
}

void EngineCore::run_chain(const std::vector<SSlot>& chain, float* L, float* R, int n) {
    float* ch[2] = {L, R};
    for (const auto& s : chain) {
        if (s.bypass || !s.fx) continue;
        s.fx->process(ch, n, 2);
    }
}

void EngineCore::mix_clip(const SClip& c, int64_t pos, int n, float* L, float* R) {
    const int64_t a = std::max(pos, c.start);
    int64_t b = std::min(pos + n, c.start + c.len);
    if (b <= a) return;
    const int64_t s0 = c.off + (a - c.start);
    const int64_t total = c.buf->frames();
    if (s0 >= total) return;
    b = std::min(b, a + (total - s0));
    const int ch = c.buf->channels();
    const float* d = c.buf->data().data();
    const bool fades = c.fin > 0 || c.fout > 0;
    for (int64_t f = a; f < b; ++f) {
        float g = c.gain;
        if (fades) {
            const int64_t rel = f - c.start;
            if (c.fin > 0 && rel < c.fin) g *= ((float)rel + 0.5f) / (float)c.fin;
            const int64_t left = c.len - rel;
            if (c.fout > 0 && left <= c.fout) g *= ((float)left - 0.5f) / (float)c.fout;
        }
        const int64_t i = s0 + (f - a);
        const float l = d[i * ch];
        const float r = ch > 1 ? d[i * ch + 1] : l;
        L[f - pos] += l * g;
        R[f - pos] += r * g;
    }
}

void EngineCore::metronome_chunk(float* L, float* R, int n, int64_t beat_pos) {
    const double beat_len = (double)engine_rate_ * 60.0 / (double)bpm_.load();
    const int beats = beats_.load();
    // First beat boundary at or after beat_pos.
    int64_t k = (int64_t)std::ceil((double)beat_pos / beat_len);
    int64_t t = (int64_t)std::llround((double)k * beat_len);
    int next_off = (int)(t - beat_pos);
    for (int i = 0; i < n; ++i) {
        if (i == next_off) {
            const bool down = (k % beats) == 0;
            click_inc_ = (down ? 1600.f : 1000.f) / engine_rate_;
            click_total_ = click_left_ = (int)(0.035f * engine_rate_);
            click_phase_ = 0.f; click_amp_ = down ? 0.55f : 0.38f;
            ++k;
            next_off = (int)(std::llround((double)k * beat_len) - beat_pos);
        }
        if (click_left_ > 0) {
            const float env = (float)click_left_ / (float)click_total_;
            const float s = std::sin(6.2831853f * click_phase_) * click_amp_ * env * env;
            click_phase_ += click_inc_; if (click_phase_ >= 1.f) click_phase_ -= 1.f;
            --click_left_;
            L[i] += s; R[i] += s;
        }
    }
}

int EngineCore::render_chunk_planned(float* out, int n, const float* in, int in_ch) {
    float* const L = bufL_.data();
    float* const R = bufR_.data();
    Snapshot* snap = current_;
    if (!snap) { std::fill(out, out + (size_t)n * 2, 0.f); return n; }

    // ---- control requests ----
    const int flags = reset_req_.exchange(0, std::memory_order_acq_rel);
    (void)flags;
    const int64_t sk = seek_req_.exchange(-1, std::memory_order_acq_rel);
    if (sk >= 0) pos_ = sk;
    const bool want_play = play_req_.load(std::memory_order_acquire) != 0;
    if (want_play && !playing_a_ && !counting_a_) {
        for (auto& t : snap->tracks) for (auto& s : t.fx) s.fx->reset();
        for (auto& s : snap->master) s.fx->reset();
        const int bars = count_in_bars_.load();
        if (bars > 0) {
            counting_a_ = true; count_pos_ = 0;
            count_left_ = (int64_t)std::llround((double)bars * beats_.load() * engine_rate_ * 60.0 / bpm_.load());
        } else {
            playing_a_ = true;
        }
        tail_left_ = 0;
        click_left_ = 0;
        snap_gains_ = true;
    } else if (!want_play && (playing_a_ || counting_a_)) {
        playing_a_ = counting_a_ = false;
        tail_left_ = (int64_t)(2.5 * engine_rate_);
    }
    counting_pub_.store(counting_a_, std::memory_order_relaxed);

    // ---- chunk planning: never cross a loop end or the end of a count-in ----
    if (playing_a_ && snap->loop && pos_ >= snap->loop_e) pos_ = snap->loop_s;
    if (counting_a_) n = (int)std::min<int64_t>(n, std::max<int64_t>(1, count_left_));
    else if (playing_a_ && snap->loop) n = (int)std::min<int64_t>(n, snap->loop_e - pos_);
    if (n < 1) n = 1;

    std::fill(L, L + n, 0.f);
    std::fill(R, R + n, 0.f);

    // ---- input: sanitise, meter, monitor ----
    float* const iL = inL_.data();
    float* const iR = inR_.data();
    const bool have_in = in != nullptr && in_ch >= 1;
    if (have_in) {
        float pk = 0.f;
        for (int i = 0; i < n; ++i) {
            float a = in[i * in_ch];
            float b = in_ch > 1 ? in[i * in_ch + 1] : a;
            if (!is_finite_bits(a)) a = 0.f;
            if (!is_finite_bits(b)) b = 0.f;
            iL[i] = a; iR[i] = b;
            pk = std::max(pk, std::max(std::fabs(a), std::fabs(b)));
        }
        if (pk > in_peak_.load(std::memory_order_relaxed)) in_peak_.store(pk, std::memory_order_relaxed);
    }

    // ---- tracks ----
    const bool process_tracks = playing_a_ || tail_left_ > 0;
    if (process_tracks) {
        bool solo_any = false;
        for (auto& t : snap->tracks) if (tstate_[t.slot].solo.load(std::memory_order_relaxed)) { solo_any = true; break; }
        float* const tL = trkL_.data();
        float* const tR = trkR_.data();
        float* const pfL = pfL_.data();
        float* const pfR = pfR_.data();
        bool bus_fed[kMaxBuses] = {false, false, false, false};
        for (auto& t : snap->tracks) {
            TrackState& ts = tstate_[t.slot];
            const bool audible = !ts.mute.load(std::memory_order_relaxed) &&
                                 (!solo_any || ts.solo.load(std::memory_order_relaxed));
            const float vol = audible ? ts.vol.load(std::memory_order_relaxed) : 0.f;
            const float pan = ts.pan.load(std::memory_order_relaxed);
            // Balance law: centre = unity on both sides, panning attenuates the far side.
            const float tgl = vol * (pan > 0.f ? 1.f - pan : 1.f);
            const float tgr = vol * (pan < 0.f ? 1.f + pan : 1.f);
            float& cgl = gain_l_[t.slot];
            float& cgr = gain_r_[t.slot];
            const bool snapped = ts.fresh.exchange(false, std::memory_order_relaxed) || snap_gains_;
            if (snapped) { cgl = tgl; cgr = tgr; }
            if (tgl == 0.f && tgr == 0.f && cgl < 1e-5f && cgr < 1e-5f) { cgl = cgr = 0.f; continue; }
            std::fill(tL, tL + n, 0.f);
            std::fill(tR, tR + n, 0.f);
            if (playing_a_) for (auto& c : t.clips) mix_clip(c, pos_, n, tL, tR);
            run_chain(t.fx, tL, tR, n);
            const float dl = (tgl - cgl) / (float)n, dr = (tgr - cgr) / (float)n;
            float gl = cgl, gr = cgr, pl = 0.f, pr = 0.f;
            for (int i = 0; i < n; ++i) {
                gl += dl; gr += dr;
                const float a = tL[i] * gl, b = tR[i] * gr;
                L[i] += a; R[i] += b;
                pfL[i] = a; pfR[i] = b;
                pl = std::max(pl, std::fabs(a)); pr = std::max(pr, std::fabs(b));
            }
            cgl = tgl; cgr = tgr;
            // aux sends (post-fader, post-pan; a muted/silent track sends nothing)
            for (int bi = 0; bi < kMaxBuses; ++bi) {
                const float tgt = ts.send[bi].load(std::memory_order_relaxed);
                float& cs = send_cur_[t.slot][bi];
                if (snapped) cs = tgt;
                if (tgt == 0.f && cs < 1e-5f) { cs = 0.f; continue; }
                float* bl = busL_[bi].data(); float* br = busR_[bi].data();
                if (!bus_fed[bi]) { std::fill(bl, bl + n, 0.f); std::fill(br, br + n, 0.f); bus_fed[bi] = true; }
                const float ds = (tgt - cs) / (float)n;
                float s = cs;
                for (int i = 0; i < n; ++i) { s += ds; bl[i] += pfL[i] * s; br[i] += pfR[i] * s; }
                cs = tgt;
            }
            if (pl > ts.peak_l.load(std::memory_order_relaxed)) ts.peak_l.store(pl, std::memory_order_relaxed);
            if (pr > ts.peak_r.load(std::memory_order_relaxed)) ts.peak_r.store(pr, std::memory_order_relaxed);
        }
        // aux buses: effect chain on the summed sends, then the return level into the master mix.
        // A bus that is no longer fed keeps running (reverb/delay tails) until it has been idle for 6 s.
        const int64_t idle_limit = (int64_t)(6.0 * engine_rate_);
        for (int bi = 0; bi < kMaxBuses; ++bi) {
            float* bl = busL_[bi].data(); float* br = busR_[bi].data();
            if (bus_fed[bi]) bus_idle_[bi] = 0;
            else {
                if (snap->bus[bi].empty() || bus_idle_[bi] >= idle_limit) continue;
                std::fill(bl, bl + n, 0.f); std::fill(br, br + n, 0.f);
                bus_idle_[bi] += n;
            }
            run_chain(snap->bus[bi], bl, br, n);
            const float tg = bus_level_[bi].load(std::memory_order_relaxed);
            const float d = (tg - bus_gain_cur_[bi]) / (float)n;
            float g = bus_gain_cur_[bi];
            for (int i = 0; i < n; ++i) { g += d; L[i] += bl[i] * g; R[i] += br[i] * g; }
            bus_gain_cur_[bi] = tg;
        }
        run_chain(snap->master, L, R, n);
        snap_gains_ = false;
        if (!playing_a_) tail_left_ -= n;
    }

    // ---- master volume (ramped) ----
    {
        const float tg = master_vol_.load(std::memory_order_relaxed);
        const float d = (tg - master_gain_cur_) / (float)n;
        float g = master_gain_cur_;
        for (int i = 0; i < n; ++i) { g += d; L[i] *= g; R[i] *= g; }
        master_gain_cur_ = tg;
    }

    // ---- metronome / count-in clicks ----
    if (counting_a_) metronome_chunk(L, R, n, count_pos_);
    else if (playing_a_ && metro_on_.load(std::memory_order_relaxed)) metronome_chunk(L, R, n, pos_);

    // ---- monitoring ----
    if (have_in && monitor_on_.load(std::memory_order_relaxed)) {
        const float g = monitor_gain_.load(std::memory_order_relaxed);
        for (int i = 0; i < n; ++i) { L[i] += iL[i] * g; R[i] += iR[i] * g; }
    }

    // ---- recording hand-off (only while the transport really runs: sample-accurate alignment) ----
    if (recorder_.active()) {
        const int gen = rec_gen_.load(std::memory_order_acquire);
        if (gen != rec_gen_seen_) { rec_gen_seen_ = gen; rec_stalled_ = false; rec_expected_pos_ = -1; }   // new take
    }
    if (recorder_.active() && !rec_stalled_) {
        if (!playing_a_ || !have_in) {
            if (rec_expected_pos_ >= 0) rec_stalled_ = true;     // contiguity lost
        } else {
            int a = 0, b = n;
            if (snap->punch) {
                a = (int)std::max<int64_t>(0, snap->punch_in - pos_);
                b = (int)std::min<int64_t>(n, snap->punch_out - pos_);
            }
            if (b > a) {
                if (rec_expected_pos_ < 0) rec_first_pos_.store(pos_ + a, std::memory_order_release);
                else if (pos_ + a != rec_expected_pos_) rec_stalled_ = true;
                if (!rec_stalled_) {
                    const int rc = recorder_.channels();
                    float* rb = recbuf_.data();
                    for (int i = a; i < b; ++i) {
                        rb[(i - a) * rc] = iL[i];
                        if (rc > 1) rb[(i - a) * rc + 1] = (in_ch > 1) ? iR[i] : iL[i];
                    }
                    recorder_.push(rb, b - a);
                    rec_expected_pos_ = pos_ + b;
                }
            } else if (rec_expected_pos_ >= 0) {
                rec_stalled_ = true;                              // left the punch range
            }
        }
    }

    // ---- output stage: safety limiter, clip counting, meters ----
    float pl = 0.f, pr = 0.f;
    double sl = 0, sr = 0;
    int clipped = 0;
    for (int i = 0; i < n; ++i) {
        float a = L[i], b = R[i];
        if (!is_finite_bits(a)) a = 0.f;
        if (!is_finite_bits(b)) b = 0.f;
        if (std::fabs(a) > 1.f || std::fabs(b) > 1.f) ++clipped;
        a = master_soft_limit(a); b = master_soft_limit(b);
        a = clampf(a, -1.f, 1.f); b = clampf(b, -1.f, 1.f);
        out[i * 2] = a; out[i * 2 + 1] = b;
        pl = std::max(pl, std::fabs(a)); pr = std::max(pr, std::fabs(b));
        sl += (double)a * a; sr += (double)b * b;
    }
    if (clipped) clip_count_.fetch_add(clipped, std::memory_order_relaxed);
    if (pl > m_peak_l_.load(std::memory_order_relaxed)) m_peak_l_.store(pl, std::memory_order_relaxed);
    if (pr > m_peak_r_.load(std::memory_order_relaxed)) m_peak_r_.store(pr, std::memory_order_relaxed);
    m_rms_l_.store((float)std::sqrt(sl / n), std::memory_order_relaxed);
    m_rms_r_.store((float)std::sqrt(sr / n), std::memory_order_relaxed);
    analysis_.push(out, (size_t)n * 2);          // drops when full; analysis is best-effort

    // ---- advance the transport ----
    if (counting_a_) {
        count_pos_ += n; count_left_ -= n;
        if (count_left_ <= 0) { counting_a_ = false; playing_a_ = true; }
    } else if (playing_a_) {
        pos_ += n;
        if (snap->loop && pos_ >= snap->loop_e) pos_ = snap->loop_s;
    }
    pos_pub_.store(pos_, std::memory_order_release);
    return n;
}

}  // namespace wavestudio
