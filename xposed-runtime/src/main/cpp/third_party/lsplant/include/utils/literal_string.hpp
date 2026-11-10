// NexBox addition (2026-10-11), under LSPlant's LGPL-3.0 license:
// retain every literal byte in a standard structural template argument.
#pragma once

#include <cstddef>

namespace lsplant {

template <std::size_t N>
struct LiteralString {
    char data[N];

    consteval LiteralString(const char (&literal)[N]) {
        for (std::size_t index = 0; index < N; ++index) {
            data[index] = literal[index];
        }
    }
};

}  // namespace lsplant
