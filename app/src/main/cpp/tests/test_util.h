#pragma once
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <string>

// Minimal dependency-free test harness (no network / gtest needed).
static int g_failures = 0, g_checks = 0;
#define CHECK(cond) do { ++g_checks; if (!(cond)) { ++g_failures; \
    std::printf("  FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define CHECK_MSG(cond, ...) do { ++g_checks; if (!(cond)) { ++g_failures; \
    std::printf("  FAIL %s:%d  %s  -> ", __FILE__, __LINE__, #cond); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)
#define CHECK_NEAR(a, b, tol) do { ++g_checks; double _a=(a), _b=(b); if (!(std::fabs(_a-_b) <= (tol))) { ++g_failures; \
    std::printf("  FAIL %s:%d  %s ~= %s  (%g vs %g, tol %g)\n", __FILE__, __LINE__, #a, #b, _a, _b, (double)(tol)); } } while (0)
#define RUN(fn) do { int before = g_failures; std::printf("[ RUN  ] %s\n", #fn); fn(); \
    std::printf("[ %s ] %s\n", g_failures == before ? " OK " : "FAIL", #fn); } while (0)
#define TEST_MAIN_END() do { std::printf("\n%d checks, %d failures\n", g_checks, g_failures); return g_failures ? 1 : 0; } while (0)
