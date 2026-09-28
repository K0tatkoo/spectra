cmake_minimum_required(VERSION 3.22.1)
project(spectra_cpu C)

# The app's one native file of its own; see CpuAffinity.kt.
add_library(spectra_cpu SHARED cpu_affinity.c)
target_compile_options(spectra_cpu PRIVATE -O2 -Wall -Wextra -Werror)
# 16 KB pages: what newer Android devices use, and the NDK r28 default.
target_link_options(spectra_cpu PRIVATE -Wl,-z,max-page-size=16384)
