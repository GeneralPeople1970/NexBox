



#include "IO.h"
#include <algorithm>
#include <atomic>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

jmethodID getAbsolutePathMethodId;

namespace {
constexpr size_t noRule = std::numeric_limits<size_t>::max();

struct PrefixNode {
    std::vector<std::pair<unsigned char, size_t>> children;
    size_t source = noRule;
    size_t destination = noRule;
};

struct PathMatch {
    size_t source = noRule;
    size_t destination = noRule;
};

// Published snapshots are immutable. A reader holds one shared owner while traversing
// both source and destination prefixes, so publication never invalidates its strings.
struct RuleSnapshot {
    std::vector<IO::RelocateInfo> rules;
    std::vector<PrefixNode> nodes{1};

    explicit RuleSnapshot(std::vector<IO::RelocateInfo> entries = {}) : rules(std::move(entries)) {
        for (size_t index = 0; index < rules.size(); ++index) {
            addPrefix(rules[index].targetPath, index, false);
            addPrefix(rules[index].relocatePath, index, true);
        }
        for (auto &node : nodes) std::sort(node.children.begin(), node.children.end());
    }

    void addPrefix(const std::string &prefix, size_t rule, bool destination) {
        if (prefix.empty()) return;
        size_t current = 0;
        for (unsigned char character : prefix) {
            size_t next = noRule;
            for (const auto &child : nodes[current].children) {
                if (child.first == character) {
                    next = child.second;
                    break;
                }
            }
            if (next == noRule) {
                next = nodes.size();
                nodes.emplace_back();
                nodes[current].children.emplace_back(character, next);
            }
            current = next;
        }
        size_t &entry = destination ? nodes[current].destination : nodes[current].source;
        // Several historical aliases may share one destination. Reverse lookup keeps
        // the first registered alias, matching the previous longest-prefix search.
        if (entry == noRule) entry = rule;
    }

    PathMatch match(const char *path, bool stopAtDestination) const {
        PathMatch result;
        size_t current = 0;
        for (size_t length = 0; path[length] != '\0'; ++length) {
            const auto &children = nodes[current].children;
            unsigned char character = static_cast<unsigned char>(path[length]);
            auto child = std::lower_bound(children.begin(), children.end(), character,
                    [](const auto &edge, unsigned char value) { return edge.first < value; });
            if (child == children.end() || child->first != character) break;
            current = child->second;
            if (character != '/' && path[length + 1] != '\0' && path[length + 1] != '/') continue;
            const auto &node = nodes[current];
            if (node.source != noRule) result.source = node.source;
            if (node.destination != noRule) {
                result.destination = node.destination;
                if (stopAtDestination) break;
            }
        }
        return result;
    }
};

std::shared_ptr<const RuleSnapshot> ruleSnapshot = std::make_shared<const RuleSnapshot>();
std::mutex ruleMutex;
}

const char *IO::redirectPath(const char *path, std::string &storage) {
    if (path == nullptr || path[0] != '/') return path;
    auto snapshot = std::atomic_load_explicit(&ruleSnapshot, std::memory_order_acquire);
    PathMatch match = snapshot->match(path, true);
    // Already-relocated destinations take precedence over every source prefix.
    if (match.destination != noRule || match.source == noRule) return path;
    const auto &rule = snapshot->rules[match.source];
    storage = rule.relocatePath + (path + rule.targetPath.size());
    return storage.c_str();
}

std::string IO::redirectPath(const char *path) {
    std::string storage;
    const char *mapped = redirectPath(path, storage);
    return mapped == path ? (path == nullptr ? std::string() : std::string(path)) : storage;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    return BoxCore::redirectPathString(env, path);
}

const char *IO::logicalPath(const char *path, std::string &storage) {
    if (path == nullptr) return path;
    auto snapshot = std::atomic_load_explicit(&ruleSnapshot, std::memory_order_acquire);
    PathMatch match = snapshot->match(path, false);
    if (match.destination == noRule) return path;
    const auto &rule = snapshot->rules[match.destination];
    storage = rule.targetPath + (path + rule.relocatePath.size());
    return storage.c_str();
}

std::string IO::logicalPath(const char *path) {
    std::string storage;
    const char *mapped = logicalPath(path, storage);
    return mapped == path ? (path == nullptr ? std::string() : std::string(path)) : storage;
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    if (targetPath == nullptr || relocatePath == nullptr) return;
    std::lock_guard<std::mutex> lock(ruleMutex);
    auto snapshot = std::atomic_load_explicit(&ruleSnapshot, std::memory_order_acquire);
    for (const auto &info : snapshot->rules) {
        if (info.targetPath == targetPath) return;
    }
    auto entries = snapshot->rules;
    entries.push_back({targetPath, relocatePath});
    std::shared_ptr<const RuleSnapshot> next = std::make_shared<const RuleSnapshot>(std::move(entries));
    std::atomic_store_explicit(&ruleSnapshot, std::move(next), std::memory_order_release);
}

void IO::init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    if (tmpFile == nullptr) return;
    getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
    env->DeleteLocalRef(tmpFile);
}
