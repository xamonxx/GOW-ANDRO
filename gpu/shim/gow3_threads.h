// SPDX-License-Identifier: GPL-2.0-or-later
// gow3: helper thread sizing. Counts follow the hardware threads this process may run on
// (the affinity mask, so `taskset` can emulate a Steam Deck), and speculative helpers run as
// SCHED_IDLE: they use cores the game leaves idle and never take time from its threads.

#pragma once

#include <algorithm>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <bit>
#include <windows.h>
#else
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>
#endif

namespace Gow3Threads {

/// The prime cores of a hybrid CPU: the cores whose maximum frequency is the highest, when some
/// other core is slower (iQOO 15R: 6 x 3.32 GHz + 2 x 3.80 GHz -> the two 3.80 GHz cores). Empty
/// when every core is alike or a frequency is unknown (0): then nothing is moved.
inline std::vector<unsigned> PrimeCores(const std::vector<long>& max_khz) {
    if (max_khz.empty() || std::find(max_khz.begin(), max_khz.end(), 0L) != max_khz.end()) {
        return {};
    }
    const long top = *std::max_element(max_khz.begin(), max_khz.end());
    std::vector<unsigned> prime;
    for (unsigned cpu = 0; cpu < max_khz.size(); ++cpu) {
        if (max_khz[cpu] == top) {
            prime.push_back(cpu);
        }
    }
    return prime.size() == max_khz.size() ? std::vector<unsigned>{} : prime;
}
#ifdef _WIN32
/// Hardware threads available to the process (its affinity mask).
inline unsigned Available() {
    DWORD_PTR process = 0, system = 0;
    if (GetProcessAffinityMask(GetCurrentProcess(), &process, &system) && process) {
        return std::max(1, std::popcount(static_cast<unsigned long long>(process)));
    }
    return std::max(1u, std::thread::hardware_concurrency());
}

/// The calling thread only runs when cores are otherwise idle (lowest Windows priority).
inline void MakeBackground() {
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_IDLE);
}

/// Linux/Android only (GOW3_CPU_PLACEMENT).
inline void KeepOffPrimeCores() {}
#else

/// Hardware threads available to the process.
inline unsigned Available() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        return std::max(1, CPU_COUNT(&set));
    }
    return std::max(1u, std::thread::hardware_concurrency());
}

/// The calling thread only runs on otherwise idle cores (falls back to the lowest nice level).
inline void MakeBackground() {
    sched_param param{};
    if (sched_setscheduler(0, SCHED_IDLE, &param) != 0) {
        setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), 19);
    }
}

/// GOW3_CPU_PLACEMENT=1 (run-gow3.sh, gow3.ini cpu_placement): the calling helper thread runs on
/// the cores other than the prime ones, which stay for the game's main thread and the GPU threads
/// (those are not pinned: the scheduler still places them). No change without a hybrid CPU.
inline void KeepOffPrimeCores() {
    static const cpu_set_t* const helpers = [] () -> const cpu_set_t* {
        const char* env = std::getenv("GOW3_CPU_PLACEMENT");
        if (!env || std::strcmp(env, "1") != 0) {
            return nullptr;
        }
        const long count = sysconf(_SC_NPROCESSORS_CONF);
        std::vector<long> max_khz;
        for (long cpu = 0; cpu < count && cpu < CPU_SETSIZE; ++cpu) {
            char path[96];
            std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", cpu);
            long khz = 0;
            if (FILE* file = std::fopen(path, "r")) {
                if (std::fscanf(file, "%ld", &khz) != 1) {
                    khz = 0;
                }
                std::fclose(file);
            }
            max_khz.push_back(khz);
        }
        const auto prime = PrimeCores(max_khz);
        static cpu_set_t set;
        CPU_ZERO(&set);
        if (prime.empty() || sched_getaffinity(0, sizeof(set), &set) != 0) {
            std::printf("CPU: helper placement unavailable (no hybrid core frequencies)\n");
            return nullptr;
        }
        for (const unsigned cpu : prime) {
            CPU_CLR(cpu, &set);
        }
        if (CPU_COUNT(&set) == 0) {
            return nullptr;
        }
        std::printf("CPU: %zu prime cores kept for the game and GPU threads; helpers on %d others\n",
                    prime.size(), CPU_COUNT(&set));
        return &set;
    }();
    if (helpers) {
        sched_setaffinity(0, sizeof(*helpers), helpers);
    }
}
#endif

} // namespace Gow3Threads
