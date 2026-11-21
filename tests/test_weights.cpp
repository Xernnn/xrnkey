// Weighting a training source (xk-lm-build --weight): a little everyday speech
// against a lot of news. Made-up sentences; the models are trained here.
#include <catch2/catch_test_macros.hpp>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "lm_internal.h"
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

TEST_CASE("each order has its own weight", "[lm][weights]") {
    // One chat sentence, against a pruning that drops what was seen twice or fewer: counted 8
    // times at every order it keeps its four words; counted twice as four words, it keeps its
    // pairs and triples only, and so can't decide a four-word phrase on its own
    // ("cám ơn bạn nhiều" against the common "cảm ơn").
    write(tmp("news4.tok"), {"cảm ơn bạn", "cảm ơn các bạn"}, 12);
    write(tmp("chat4.tok"), {"cám ơn bạn nhiều"});
    auto has = [](const std::string& model, std::vector<const char*> gram) {
        XKLm* lm = xk_lm_open(model.c_str());
        REQUIRE(lm != nullptr);
        const xrnkey::Lm& m = xrnkey::lm_of(lm);
        std::vector<uint32_t> ids;
        for (const char* w : gram) ids.push_back(xrnkey::lm_id(m, w));
        const bool found = xrnkey::lm_find(m, ids.data(), static_cast<int>(ids.size())) >= 0;
        xk_lm_free(lm);
        return found;
    };
    auto model = [](const std::string& name, const std::string& weight) {
        const std::string out = tmp(name);
        const std::string cmd = std::string(XRNKEY_LM_BUILD) + " --order 4 --min-count 1 --prune 0,2,2 -o " + out +
                                " --weight 1 " + tmp("news4.tok") + " --weight " + weight + " " + tmp("chat4.tok") +
                                " 2>/dev/null";
        REQUIRE(std::system(cmd.c_str()) == 0);
        return out;
    };
    const std::string all8 = model("w8.xklm", "8");
    CHECK(has(all8, {"cám", "ơn", "bạn", "nhiều"}));
    const std::string m8882 = model("w8882.xklm", "8,8,8,2");
    CHECK_FALSE(has(m8882, {"cám", "ơn", "bạn", "nhiều"}));
    CHECK(has(m8882, {"cám", "ơn", "bạn"}));
    CHECK(has(m8882, {"ơn", "bạn", "nhiều"}));
}

TEST_CASE("a weight out of range is refused", "[lm][weights]") {
    for (const char* w : {"0", "8,0", "2000"}) {
        const std::string cmd = std::string(XRNKEY_LM_BUILD) + " --weight " + w + " -o " + tmp("w0.xklm") + " " +
                                tmp("chat.tok") + " 2>/dev/null";
        CHECK(std::system(cmd.c_str()) != 0);
    }
}
