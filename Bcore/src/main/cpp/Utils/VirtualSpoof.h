#pragma once

#include <string>
#include <unordered_map>

void setSimSpoofValues(const std::unordered_map<std::string, std::string> &values);

void setDeviceSpoofValues(
        const char *manufacturer,
        const char *brand,
        const char *model,
        const char *device,
        const char *product,
        const char *fingerprint,
        const char *serial);
