// Weighting a training source (xk-lm-build --weight): a little everyday speech
// against a lot of news. Made-up sentences; the models are trained here.
#include <catch2/catch_test_macros.hpp>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "xrnkey.h"

namespace {

std::string tmp(const std::string& name) { return std::string(XRNKEY_TEST_TMP) + "/" + name; }

void write(const std::string& path, const std::vector<std::string>& lines, int times = 1) {
    std::ofstream f(path);
    for (int i = 0; i < times; ++i)
        for (const auto& l : lines) f << l << "\n";
}

std::string train(const std::string& name, int chat_weight) {
    const std::string out = tmp(name);
    const std::string cmd = std::string(XRNKEY_LM_BUILD) + " --order 3 --min-count 1 --prune 0,1 -o " + out +
                            " --weight 1 " + tmp("news.tok") + " --weight " + std::to_string(chat_weight) + " " +
                            tmp("chat.tok") + " 2>/dev/null";
    REQUIRE(std::system(cmd.c_str()) == 0);
    return out;
}

std::string restore(const std::string& model, std::vector<const char*> typed) {
    XKLm* vi = xk_lm_open(model.c_str());
    REQUIRE(vi != nullptr);
    XKModels* m = xk_models_new(vi, nullptr);
    std::vector<const char*> ctx = {"<s>"};
    std::vector<char*> out(typed.size());
    const int n = xk_restore_run(m, ctx.data(), 1, typed.data(), static_cast<int>(typed.size()), nullptr, out.data());
    std::string s;
    for (int i = 0; i < n; ++i) {
        s += (i ? " " : "") + std::string(out[i]);
        std::free(out[i]);
    }
    xk_models_free(m);
    xk_lm_free(vi);
    return s;
}

}  // namespace

TEST_CASE("everyday speech weighed against the news restores a greeting", "[lm][weights]") {
    // The news offers shares ("chào bán") far more often than anyone greets a friend.
    write(tmp("news.tok"), {
        "công ty chào bán cổ phiếu",
        "đợt chào bán trái phiếu",
        "giá chào bán thấp",
        "ngân hàng chào bán vốn",
        "xin chào các vị khách",
        "xin chào quý vị",
        "lễ chào mừng năm mới",
    }, 8);
    write(tmp("chat.tok"), {"xin chào bạn", "chào bạn nhé", "cảm ơn bạn"});
    // Counted once, the greeting is pruned and the news decides.
    CHECK(restore(train("weights-1.xklm", 1), {"xin", "chao", "ban"}) == "xin chào bán");
    // Counted twenty times, it stays and wins.
    CHECK(restore(train("weights-20.xklm", 20), {"xin", "chao", "ban"}) == "xin chào bạn");
    // The news still reads as news.
    CHECK(restore(train("weights-20b.xklm", 20), {"cong", "ty", "chao", "ban"}) == "công ty chào bán");
}

TEST_CASE("a weight out of range is refused", "[lm][weights]") {
    const std::string cmd = std::string(XRNKEY_LM_BUILD) + " --weight 0 -o " + tmp("w0.xklm") + " " + tmp("chat.tok") + " 2>/dev/null";
    CHECK(std::system(cmd.c_str()) != 0);
}
