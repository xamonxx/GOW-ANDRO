// gow3.ini graphics keys (gow3_settings.cpp, gow3_graphics.h).
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include "gow3_graphics.h"
#include "gow3_settings.h"
#include "gow3_threads.h"

namespace G = Gow3Graphics;

#ifndef _WIN32
// Linux (CI's launcher-check job): an empty value is ignored by Load like an unset one.
static int _putenv_s(const char* name, const char* value) { return setenv(name, value, 1); }
#endif

static std::string Read(const char* path) {
    std::ifstream in(path);
    std::stringstream s;
    s << in.rdbuf();
    return s.str();
}

int main() {
    assert(G::ParseDisplayMode("borderless") == G::Borderless && G::ParseDisplayMode("bogus") == G::Windowed);
    assert(G::ParseFpsLimit(240) == 240 && G::ParseFpsLimit(75) == 0);
    assert(G::ParseResolution("1440p") == 4 && G::ParseResolution("800p") == 3 && G::ParseResolution("8K") == 0);
    assert(G::ParseEngineFps(240) == 240 && G::ParseEngineFps(90) == 120);
    assert(G::ParseFramesQueued(2) == 2 && G::ParseFramesQueued(0) == 0 && G::ParseFramesQueued(5) == 1);
    assert(G::ParseRcasStrength(100) == 100 && G::ParseRcasStrength(101) == 75 && G::ParseRcasStrength(-1) == 75);
    assert(G::RcasAttenuation(100) == 0.0f && G::RcasAttenuation(0) == 2.0f && G::RcasAttenuation(75) == 0.5f);
    // A window larger than the display loses its right and bottom: 1920x1080 on Android's 720p
    // X screen was cut off instead of shown whole.
    assert((G::ResolveWindowSize({1920, 1080}, {1280, 720}) == G::Size{1280, 720}));
    assert((G::ResolveWindowSize({1920, 1080}, {1920, 1080}) == G::Size{1920, 1080}));
    assert((G::ResolveWindowSize({1920, 1080}, {2560, 1440}) == G::Size{1920, 1080}));  // fits: unchanged
    assert((G::ResolveWindowSize({1920, 1080}, {0, 0}) == G::Size{1920, 1080}));  // no display
    assert((G::ResolveWindowSize({1920, 1080}, {1280, -720}) == G::Size{1920, 1080}));  // invalid
    assert((G::ResolveWindowSize({0, 0}, {1280, 720}) == G::Size{1280, 720}));  // no request
    assert((G::ResolveWindowSize({0, 0}, {0, 0}) == G::DefaultWindowSize));  // neither: 1920x1080
    assert(G::FsrPassRuns(true, false, 1280, 720, 2560, 1440) && !G::FsrPassRuns(false, false, 1280, 720, 2560, 1440));
    assert(G::FsrPassRuns(false, true, 1920, 1080, 1920, 1080) && !G::FsrPassRuns(true, false, 1920, 1080, 1920, 1080));
    assert(!G::FsrPassRuns(true, true, 2560, 1440, 1920, 1080));  // larger image: regular scaling
    assert(G::EffectiveFps(0, 120) == 120 && G::EffectiveFps(240, 120) == 120 && G::EffectiveFps(60, 240) == 60);
    // The thermal cap lowers, never raises; 0 is no cap.
    assert(G::ThermalLimit(60, 30) == 30 && G::ThermalLimit(0, 30) == 30 && G::ThermalLimit(30, 60) == 30);
    assert(G::ThermalLimit(60, 0) == 60 && G::ThermalLimit(0, 0) == 0);
    assert(Gow3Settings::ParseThermalCap("30\n") == 30 && Gow3Settings::ParseThermalCap("45") == 0);
    assert(Gow3Settings::ParseThermalCap("") == 0 && Gow3Settings::ParseThermalCap(nullptr) == 0);
    {
        const char* cap_path = "graphics-settings-test.cap";
        std::ofstream(cap_path) << "30\n";
        _putenv_s("GOW3_THERMAL_CAP_FILE", cap_path);
        assert(Gow3Settings::ThermalCap() == 30);
        std::remove(cap_path);
        assert(Gow3Settings::ThermalCap() == 30);  // read at most once a second
    }
    // iQOO 15R: 6 x 3.32 GHz + 2 x 3.80 GHz -> cores 6 and 7; alike or unknown cores -> none.
    using CoreList = std::vector<unsigned>;
    assert(Gow3Threads::PrimeCores({3321600, 3321600, 3321600, 3321600, 3321600, 3321600, 3801600, 3801600}) ==
           (CoreList{6, 7}));
    assert(Gow3Threads::PrimeCores({2000000, 2000000, 2000000, 2000000}).empty());
    assert(Gow3Threads::PrimeCores({3321600, 0, 3801600}).empty());
    assert(Gow3Threads::PrimeCores({}).empty());

    const char* path = "graphics-settings-test.ini";
    {
        std::ofstream out(path);
        out << "display_mode=borderless\nvsync=0\nfps_limit=240\nasync_shaders=0\n"
               "render_resolution=1440p\nengine_fps=240\ndeferred_readback=0\nstale_readback=1\n"
               "fsr1=1\nrcas=0\nrcas_strength=40\nframes_queued=2\n";
    }
    _putenv_s("GOW3_CONFIG", path);
    _putenv_s("GOW3_ASYNC_SHADERS", "");
    auto& v = Gow3Settings::Get();
    Gow3Settings::Load();
    assert(v.display_mode == G::Borderless && !v.vsync && v.fps_limit == 240 && !v.async_shaders);
    assert(v.render_resolution == 4 && v.engine_fps == 240 && !v.deferred_readback && v.stale_readback);
    assert(v.fsr1 && !v.rcas && v.rcas_strength == 40 && v.frames_queued == 2);
    assert(!Gow3Settings::GraphicsNeedRestart());
    v.engine_fps = 120;
    assert(Gow3Settings::GraphicsNeedRestart());
    v.fps_limit = 60;  // live: no restart because of it
    v.engine_fps = 240;
    assert(!Gow3Settings::GraphicsNeedRestart());

    Gow3Settings::Save();
    const std::string saved = Read(path);
    for (const char* line : {"display_mode=borderless\n", "vsync=0\n", "fps_limit=60\n", "async_shaders=0\n",
                             "render_resolution=1440p\n", "engine_fps=240\n", "deferred_readback=0\n",
                             "stale_readback=1\n", "fsr1=1\n", "rcas=0\n", "rcas_strength=40\n",
                             "frames_queued=2\n"}) {
        assert(saved.find(line) != std::string::npos);
    }

    {
        std::ofstream out(path);
        out << "display_mode=huge\nfps_limit=7\nrender_resolution=8K\nengine_fps=1\n"
               "rcas_strength=250\nframes_queued=9\n";
    }
    _putenv_s("GOW3_ASYNC_SHADERS", "1");  // the environment still wins at start
    v.async_shaders = false;
    Gow3Settings::Load();
    assert(v.display_mode == G::Windowed && v.fps_limit == 0 && v.render_resolution == 0 && v.engine_fps == 120);
    assert(v.rcas_strength == 75 && v.frames_queued == 1);
    assert(v.async_shaders);
    {
        std::ofstream out(path);
        out << "cheat_double_jumps=1\ncheat_very_hard=1\n";
    }
    Gow3Settings::Load();
    Gow3Settings::Save();
    const std::string cheats = Read(path);
    assert(cheats.find("cheat_double_jumps=1\n") != std::string::npos);
    assert(cheats.find("cheat_very_hard=1\n") != std::string::npos);

    // The in-game menu's Save keeps the keys it does not own (Android launcher, LSFG, pad
    // mapping), one line each with the last value, and still writes its own.
    {
        std::ofstream out(path);
        out << "# comment\ngpu_boost=1\ncpu_mode=fast\nlsfg_multiplier=2\npad.cross=a\n"
               "cpu_mode=accurate\nframes_queued=2\nvsync=0\n";
    }
    Gow3Settings::Load();
    Gow3Settings::Save();
    Gow3Settings::Save();  // twice: kept keys do not multiply
    const std::string kept = Read(path);
    for (const char* line : {"gpu_boost=1\n", "cpu_mode=accurate\n", "lsfg_multiplier=2\n", "pad.cross=a\n",
                             "frames_queued=2\n", "vsync=0\n"}) {
        const auto at = kept.find(line);
        assert(at != std::string::npos);
        assert(kept.find(line, at + 1) == std::string::npos);
    }
    assert(kept.find("cpu_mode=fast") == std::string::npos);
    std::remove(path);
    std::puts("PASS: graphics settings");
}
